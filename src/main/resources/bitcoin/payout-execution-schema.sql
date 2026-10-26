-- 키 사용 전에 고정 지급 원본·비용 예약·첫 서명 요청을 함께 확정한다. 고객 금액은 여기서 차감하지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_payout_executions (
 withdrawal_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawals(id),review_id uuid NOT NULL UNIQUE REFERENCES bitcoin_withdrawal_payout_decisions(review_id),
 candidate_id uuid NOT NULL UNIQUE REFERENCES bitcoin_payout_candidates(id),user_id bigint NOT NULL REFERENCES users(id),network varchar(32) NOT NULL,
 unsigned_txid varchar(64) NOT NULL,lock_attempt uuid NOT NULL REFERENCES bitcoin_payout_wallet_results(attempt_id),
 company_exposure numeric(16,8) NOT NULL CHECK(company_exposure>=0 AND company_exposure<=0.0001),
 key_id varchar(64) NOT NULL,ciphertext bytea NOT NULL,content_mac varchar(64) NOT NULL,authorized_at timestamptz NOT NULL,
 created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(network,unsigned_txid),
 CHECK(unsigned_txid ~ '^[0-9a-f]{64}$' AND key_id ~ '^[0-9a-f]{64}$' AND content_mac ~ '^[0-9a-f]{64}$' AND octet_length(ciphertext) BETWEEN 28 AND 1048604)
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_sign_requests (
 id uuid PRIMARY KEY,withdrawal_id uuid NOT NULL UNIQUE REFERENCES bitcoin_payout_executions(withdrawal_id),execution_mac varchar(64) NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),CHECK(id=withdrawal_id)
);
CREATE OR REPLACE FUNCTION bitcoin_native_execution_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c record;r record;w record;observed record;income numeric;allocated numeric;
BEGIN
 SELECT * INTO c FROM bitcoin_payout_candidates WHERE id=NEW.candidate_id;
 SELECT * INTO r FROM bitcoin_withdrawal_payout_reviews WHERE id=NEW.review_id;
 SELECT * INTO w FROM bitcoin_withdrawals WHERE id=NEW.withdrawal_id;
 PERFORM id FROM users WHERE id IN(r.user_id,r.requester_id,r.checker_id) ORDER BY id FOR UPDATE;
 SELECT a.*,o.status,o.locked,o.recorded_at INTO observed FROM bitcoin_payout_wallet_attempts a JOIN bitcoin_payout_wallet_results o ON o.attempt_id=a.id WHERE a.id=NEW.lock_attempt;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.authorized_at>clock_timestamp() OR NEW.withdrawal_id<>r.withdrawal_id OR NEW.user_id<>r.user_id
  OR c.review_id<>r.id OR c.withdrawal_id<>w.id OR c.user_id<>NEW.user_id OR NEW.network<>c.network OR NEW.unsigned_txid<>c.unsigned_txid
  OR NEW.company_exposure<>greatest(c.native_fee-w.fee,0) OR NOT bitcoin_payout_source(r.id)
  OR observed.candidate_id<>c.id OR observed.operation<>'LOCK' OR observed.status<>'OBSERVED' OR NOT observed.locked
  OR observed.recorded_at<clock_timestamp()-interval '30 seconds' OR observed.recorded_at>clock_timestamp(),true)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_decisions WHERE review_id=r.id AND approved)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_payout_candidate_sources WHERE candidate_id=c.id)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_payout_active_candidates WHERE withdrawal_id=w.id AND candidate_id=c.id)
  OR(SELECT count(*) FROM bitcoin_payout_input_reservations WHERE candidate_id=c.id)<>c.input_count
  OR EXISTS(SELECT 1 FROM bitcoin_payout_wallet_attempts WHERE candidate_id=c.id AND(operation='UNLOCK' OR position>observed.position))
  OR EXISTS(SELECT 1 FROM bitcoin_payout_candidate_releases WHERE candidate_id=c.id) THEN
  RAISE EXCEPTION '현재 승인·입력 배정·잠금 관측과 고정 지급 원본이 다릅니다';END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cex:btc-withdrawal-fee-budget',0));
 SELECT coalesce(sum(delta),0) INTO income FROM ledger_entries WHERE owner_id=0 AND bucket='FEE' AND asset='BTC';
 SELECT coalesce(sum(e.company_exposure),0) INTO allocated FROM bitcoin_payout_executions e WHERE NOT EXISTS(
  SELECT 1 FROM ledger_transactions t WHERE t.operation_key='btc:withdrawal:complete:'||e.withdrawal_id AND t.kind='BTC_WITHDRAWAL_COMPLETE');
 IF income-allocated<NEW.company_exposure THEN RAISE EXCEPTION '고객 수수료를 초과하는 광부 비용의 회사 여력이 부족합니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_native_execution_binding_check ON bitcoin_payout_executions;
CREATE TRIGGER bitcoin_native_execution_binding_check BEFORE INSERT ON bitcoin_payout_executions FOR EACH ROW EXECUTE FUNCTION bitcoin_native_execution_binding();
CREATE OR REPLACE FUNCTION bitcoin_native_execution_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE e record;r record;
BEGIN
 IF TG_TABLE_NAME='bitcoin_payout_executions' THEN e:=NEW;
 ELSE SELECT * INTO e FROM bitcoin_payout_executions WHERE withdrawal_id=NEW.withdrawal_id;END IF;
 SELECT * INTO r FROM bitcoin_withdrawal_payout_reviews WHERE id=e.review_id;
 IF coalesce(e.created_txid<>txid_current(),true)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments c WHERE c.withdrawal_id=e.withdrawal_id AND c.review_id=e.review_id AND c.user_id=e.user_id AND c.payload_hash=r.payload_hash AND c.created_txid=e.created_txid)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_payout_sign_requests s WHERE s.id=e.withdrawal_id AND s.withdrawal_id=e.withdrawal_id AND s.execution_mac=e.content_mac AND s.created_txid=e.created_txid)
  OR NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NULL AND a.action='BTC_NATIVE_PAYOUT_AUTHORIZED' AND a.target='bitcoin-withdrawal:'||e.withdrawal_id AND a.outcome='SUCCESS' AND a.detail=e.content_mac AND a.occurred_at>=e.authorized_at)
  OR NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NULL AND a.action='BTC_NATIVE_SIGN_REQUESTED' AND a.target='bitcoin-sign-request:'||e.withdrawal_id AND a.outcome='SUCCESS' AND a.detail=e.content_mac AND a.occurred_at>=e.authorized_at) THEN
  RAISE EXCEPTION '고정 실행·업무 확정·서명 요청·필수 감사는 같은 커밋이어야 합니다';END IF;RETURN NULL;
END $$;
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_executions','bitcoin_payout_sign_requests','bitcoin_withdrawal_payout_commitments'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS bitcoin_native_execution_complete_check ON %I',relation);
  EXECUTE format('CREATE CONSTRAINT TRIGGER bitcoin_native_execution_complete_check AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_native_execution_complete()',relation);
 END LOOP;
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_executions','bitcoin_payout_sign_requests'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);
  EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
END $$;
