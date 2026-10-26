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
 signer_key_id varchar(64) NOT NULL,ciphertext bytea NOT NULL,content_mac varchar(64) NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),CHECK(id=withdrawal_id),
 CHECK(signer_key_id~'^[0-9a-f]{64}$' AND content_mac~'^[0-9a-f]{64}$' AND octet_length(ciphertext) BETWEEN 28 AND 1048604)
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_sign_attempts (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES bitcoin_payout_sign_requests(id),position integer NOT NULL CHECK(position BETWEEN 1 AND 100),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(request_id,position)
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_sign_results (
 attempt_id uuid PRIMARY KEY REFERENCES bitcoin_payout_sign_attempts(id),status varchar(16) NOT NULL CHECK(status IN('SIGNED','UNKNOWN','REJECTED')),
 txid varchar(64),key_id varchar(64),ciphertext bytea,content_mac varchar(64),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK((status='SIGNED' AND txid~'^[0-9a-f]{64}$' AND key_id~'^[0-9a-f]{64}$' AND content_mac~'^[0-9a-f]{64}$' AND octet_length(ciphertext) BETWEEN 28 AND 2000028)
    OR(status<>'SIGNED' AND txid IS NULL AND key_id IS NULL AND ciphertext IS NULL AND content_mac IS NULL))
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_sign_heads (
 request_id uuid PRIMARY KEY REFERENCES bitcoin_payout_sign_requests(id),attempt_id uuid NOT NULL UNIQUE REFERENCES bitcoin_payout_sign_results(attempt_id),txid varchar(64) NOT NULL CHECK(txid~'^[0-9a-f]{64}$')
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

-- 서명된 원시 거래의 방송 시도와 확정 수신 결과를 별도 불변 이력으로 보존한다.
CREATE TABLE IF NOT EXISTS bitcoin_payout_broadcast_attempts (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES bitcoin_payout_sign_heads(request_id),position integer NOT NULL CHECK(position BETWEEN 1 AND 100),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(request_id,position)
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_broadcast_results (
 attempt_id uuid PRIMARY KEY REFERENCES bitcoin_payout_broadcast_attempts(id),status varchar(16) NOT NULL CHECK(status IN('ACCEPTED','UNKNOWN','REJECTED')),
 txid varchar(64),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK((status='ACCEPTED' AND txid~'^[0-9a-f]{64}$') OR(status<>'ACCEPTED' AND txid IS NULL))
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_broadcast_heads (
 request_id uuid PRIMARY KEY REFERENCES bitcoin_payout_sign_heads(request_id),attempt_id uuid NOT NULL UNIQUE REFERENCES bitcoin_payout_broadcast_results(attempt_id),
 txid varchar(64) NOT NULL CHECK(txid~'^[0-9a-f]{64}$')
);
-- 방송 수신과 블록 확정을 구분한다. 완료 권한은 읽기 전용 노드가 같은 거래의 여섯 확인을 증명한 뒤에만 생긴다.
CREATE TABLE IF NOT EXISTS bitcoin_payout_chain_attempts (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES bitcoin_payout_broadcast_heads(request_id),position integer NOT NULL,
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(request_id,position),CHECK(position BETWEEN 1 AND 1000000)
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_chain_results (
 attempt_id uuid PRIMARY KEY REFERENCES bitcoin_payout_chain_attempts(id),status varchar(16) NOT NULL CHECK(status IN('CONFIRMED','WAITING','UNKNOWN','CONFLICT')),
 txid varchar(64),block_hash varchar(64),block_height bigint,confirmations integer,observed_tip varchar(64),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK((status='CONFIRMED' AND txid~'^[0-9a-f]{64}$' AND block_hash~'^[0-9a-f]{64}$' AND block_height>=0 AND confirmations>=6 AND observed_tip~'^[0-9a-f]{64}$')
    OR(status<>'CONFIRMED' AND txid IS NULL AND block_hash IS NULL AND block_height IS NULL AND confirmations IS NULL AND observed_tip IS NULL))
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_chain_heads (
 request_id uuid PRIMARY KEY REFERENCES bitcoin_payout_broadcast_heads(request_id),attempt_id uuid NOT NULL UNIQUE REFERENCES bitcoin_payout_chain_results(attempt_id),
 txid varchar(64) NOT NULL,block_hash varchar(64) NOT NULL,block_height bigint NOT NULL,confirmations integer NOT NULL CHECK(confirmations>=6)
);
-- 여섯 확인 머리 뒤의 엔진 완료 전송과 Kafka 금융 완료를 각각 보존한다.
CREATE TABLE IF NOT EXISTS bitcoin_payout_completion_attempts (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES bitcoin_payout_chain_heads(request_id),position integer NOT NULL CHECK(position BETWEEN 1 AND 100),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(request_id,position)
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_completion_results (
 attempt_id uuid PRIMARY KEY REFERENCES bitcoin_payout_completion_attempts(id),outcome varchar(32) NOT NULL CHECK(outcome IN('DURABLY_COMPLETED','DURABLY_REJECTED','NOT_ACCEPTED','OUTCOME_UNKNOWN')),
 completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_completion_events (
 withdrawal_id uuid PRIMARY KEY REFERENCES bitcoin_payout_chain_heads(request_id),event_id varchar(120) NOT NULL UNIQUE REFERENCES engine_event_receipts(event_id),engine_id uuid NOT NULL,
 event_time text NOT NULL,payload_hash varchar(64) NOT NULL,operation_key varchar(160) NOT NULL UNIQUE REFERENCES ledger_transactions(operation_key) DEFERRABLE INITIALLY DEFERRED,
 miner_operation_key varchar(160) NOT NULL UNIQUE REFERENCES ledger_transactions(operation_key) DEFERRABLE INITIALLY DEFERRED,created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK(payload_hash~'^[0-9a-f]{64}$')
);
-- 기존 입금 전용 수탁 계정 보호를 유지하면서, 검증된 출금 완료의 정확한 외부 유출 두 항목만 추가 허용한다.
CREATE OR REPLACE FUNCTION bitcoin_pending_entry_kind() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.operation_key AND kind='BTC_DEPOSIT_PENDING') THEN RETURN NEW;END IF;
 IF NEW.bucket='PENDING' AND NEW.delta<0 AND EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.operation_key AND kind='BTC_DEPOSIT_CREDIT') THEN
  IF EXISTS(SELECT 1 FROM bitcoin_credited_deposits c JOIN bitcoin_credit_authorizations a ON a.receipt_id=c.receipt_id WHERE c.operation_key=NEW.operation_key AND c.created_txid=txid_current() AND a.user_id=NEW.owner_id AND a.asset=NEW.asset AND NEW.delta=-a.amount) THEN RETURN NEW;END IF;
 END IF;
 IF NEW.owner_id=0 AND NEW.bucket='CUSTODY' AND NEW.asset='BTC' THEN
  IF EXISTS(SELECT 1 FROM bitcoin_withdrawal_completion_events c JOIN bitcoin_withdrawals w ON w.id=c.withdrawal_id JOIN ledger_transactions t ON t.operation_key=c.operation_key WHERE c.operation_key=NEW.operation_key AND c.created_txid=txid_current() AND t.kind='BTC_WITHDRAWAL_COMPLETE' AND NEW.delta=w.amount) THEN RETURN NEW;END IF;
  IF EXISTS(SELECT 1 FROM bitcoin_withdrawal_completion_events c JOIN bitcoin_withdrawals w ON w.id=c.withdrawal_id JOIN bitcoin_payout_executions e ON e.withdrawal_id=w.id JOIN ledger_transactions t ON t.operation_key=c.miner_operation_key WHERE c.miner_operation_key=NEW.operation_key AND c.created_txid=txid_current() AND t.kind='BTC_WITHDRAWAL_MINER_FEE' AND NEW.delta=w.fee+e.company_exposure) THEN RETURN NEW;END IF;
 END IF;
 RAISE EXCEPTION '보류/수탁 항목은 검증된 입금 또는 체인 확정 출금 업무에서만 생성할 수 있습니다';
END $$;
CREATE OR REPLACE FUNCTION bitcoin_payout_completion_attempt_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE latest record;
BEGIN
 SELECT a.position,a.started_at,r.outcome,r.completed_at INTO latest FROM bitcoin_payout_completion_attempts a LEFT JOIN bitcoin_payout_completion_results r ON r.attempt_id=a.id WHERE a.request_id=NEW.request_id ORDER BY a.position DESC LIMIT 1;
 IF NEW.created_txid<>txid_current() OR NEW.started_at<clock_timestamp()-interval '10 seconds' OR NEW.started_at>clock_timestamp()
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_completion_events WHERE withdrawal_id=NEW.request_id)
  OR(latest.position IS NOT NULL AND(NEW.position<>latest.position+1 OR latest.outcome IN('DURABLY_COMPLETED','DURABLY_REJECTED') OR(latest.outcome IS NULL AND latest.started_at>clock_timestamp()-interval '30 seconds') OR(latest.outcome IS NOT NULL AND latest.completed_at>clock_timestamp()-interval '5 seconds')))
  OR(latest.position IS NULL AND NEW.position<>1) THEN RAISE EXCEPTION '체인 확정 출금의 다음 엔진 완료 시도만 시작할 수 있습니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_completion_attempt_binding_check ON bitcoin_payout_completion_attempts;
CREATE TRIGGER bitcoin_payout_completion_attempt_binding_check BEFORE INSERT ON bitcoin_payout_completion_attempts FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_completion_attempt_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_completion_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record;
BEGIN SELECT * INTO a FROM bitcoin_payout_completion_attempts WHERE id=NEW.attempt_id;IF NEW.created_txid<>txid_current() OR NEW.completed_at<a.started_at OR NEW.completed_at>clock_timestamp() THEN RAISE EXCEPTION '엔진 완료 결과의 시각·원본이 다릅니다';END IF;RETURN NEW;END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_completion_result_binding_check ON bitcoin_payout_completion_results;
CREATE TRIGGER bitcoin_payout_completion_result_binding_check BEFORE INSERT ON bitcoin_payout_completion_results FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_completion_result_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_completion_dispatch_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE action_name text;target_name text;detail_value text;
BEGIN IF TG_TABLE_NAME='bitcoin_payout_completion_attempts' THEN action_name:='BTC_WITHDRAWAL_COMPLETE_STARTED';target_name:='bitcoin-withdrawal-complete:'||NEW.id;detail_value:='WITHDRAWAL:'||NEW.request_id;
 ELSE action_name:='BTC_WITHDRAWAL_COMPLETE_FINISHED';target_name:='bitcoin-withdrawal-complete:'||NEW.attempt_id;detail_value:='OUTCOME:'||NEW.outcome;END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit WHERE actor_id IS NULL AND action=action_name AND target=target_name AND outcome='SUCCESS' AND detail=detail_value) THEN RAISE EXCEPTION '엔진 완료 전송과 필수 감사를 함께 보존해야 합니다';END IF;RETURN NULL;END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_completion_attempt_audit_check ON bitcoin_payout_completion_attempts;
CREATE CONSTRAINT TRIGGER bitcoin_payout_completion_attempt_audit_check AFTER INSERT ON bitcoin_payout_completion_attempts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_completion_dispatch_audit();
DROP TRIGGER IF EXISTS bitcoin_payout_completion_result_audit_check ON bitcoin_payout_completion_results;
CREATE CONSTRAINT TRIGGER bitcoin_payout_completion_result_audit_check AFTER INSERT ON bitcoin_payout_completion_results DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_completion_dispatch_audit();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_completion_event_commit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE w record;e record;
BEGIN SELECT * INTO w FROM bitcoin_withdrawals WHERE id=NEW.withdrawal_id;SELECT * INTO e FROM bitcoin_payout_executions WHERE withdrawal_id=NEW.withdrawal_id;
 IF NEW.created_txid<>txid_current() OR NEW.operation_key<>'engine:'||NEW.event_id OR NEW.miner_operation_key<>'btc:withdrawal:miner-fee:'||NEW.withdrawal_id
  OR NOT EXISTS(SELECT 1 FROM bitcoin_payout_chain_heads WHERE request_id=NEW.withdrawal_id)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_payout_completion_attempts WHERE request_id=NEW.withdrawal_id)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_reservation_events r WHERE r.withdrawal_id=NEW.withdrawal_id AND r.event_type='withdrawal_reserved' AND r.engine_id=NEW.engine_id AND split_part(r.event_id,':',2)::bigint<split_part(NEW.event_id,':',2)::bigint)
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_events WHERE withdrawal_id=NEW.withdrawal_id)
  OR NOT EXISTS(SELECT 1 FROM engine_event_receipts r WHERE r.event_id=NEW.event_id AND r.engine_id=NEW.engine_id::text AND r.payload_hash=NEW.payload_hash)
  OR NOT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.operation_key AND kind='BTC_WITHDRAWAL_COMPLETE' AND created_txid=txid_current())
  OR NOT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.miner_operation_key AND kind='BTC_WITHDRAWAL_MINER_FEE' AND created_txid=txid_current())
  OR(SELECT count(*) FROM ledger_entries WHERE operation_key=NEW.operation_key)<>3 OR(SELECT count(*) FROM ledger_entries WHERE operation_key=NEW.miner_operation_key)<>2
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key AND owner_id=w.user_id AND bucket='LOCKED' AND asset='BTC' AND delta=-w.total)
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key AND owner_id=0 AND bucket='FEE' AND asset='BTC' AND delta=w.fee)
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key AND owner_id=0 AND bucket='CUSTODY' AND asset='BTC' AND delta=w.amount)
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.miner_operation_key AND owner_id=0 AND bucket='FEE' AND asset='BTC' AND delta=-(w.fee+e.company_exposure))
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.miner_operation_key AND owner_id=0 AND bucket='CUSTODY' AND asset='BTC' AND delta=w.fee+e.company_exposure)
  OR NOT EXISTS(SELECT 1 FROM security_audit WHERE actor_id IS NULL AND action='BTC_WITHDRAWAL_COMPLETED' AND target='bitcoin-withdrawal:'||NEW.withdrawal_id AND outcome='SUCCESS' AND detail='EVENT:'||NEW.event_id) THEN RAISE EXCEPTION '체인 확정 출금의 엔진 결과·정확한 원장·감사를 함께 확인하세요';END IF;RETURN NULL;END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_completion_event_commit_check ON bitcoin_withdrawal_completion_events;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_completion_event_commit_check AFTER INSERT ON bitcoin_withdrawal_completion_events DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_completion_event_commit();
DO $$ DECLARE relation text;BEGIN FOREACH relation IN ARRAY ARRAY['bitcoin_payout_completion_attempts','bitcoin_payout_completion_results','bitcoin_withdrawal_completion_events'] LOOP EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);END LOOP;END $$;
CREATE OR REPLACE FUNCTION bitcoin_payout_chain_attempt_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE latest record;
BEGIN
 SELECT a.position,a.started_at,r.status,r.recorded_at INTO latest FROM bitcoin_payout_chain_attempts a LEFT JOIN bitcoin_payout_chain_results r ON r.attempt_id=a.id
  WHERE a.request_id=NEW.request_id ORDER BY a.position DESC LIMIT 1;
 IF NEW.created_txid<>txid_current() OR NEW.started_at<clock_timestamp()-interval '10 seconds' OR NEW.started_at>clock_timestamp()
  OR EXISTS(SELECT 1 FROM bitcoin_payout_chain_heads WHERE request_id=NEW.request_id)
  OR(latest.position IS NOT NULL AND(NEW.position<>latest.position+1 OR latest.status='CONFIRMED' OR latest.status='CONFLICT'
    OR(latest.status IS NULL AND latest.started_at>clock_timestamp()-interval '10 seconds') OR(latest.status IN('WAITING','UNKNOWN') AND latest.recorded_at>clock_timestamp()-interval '60 seconds')))
  OR(latest.position IS NULL AND NEW.position<>1) THEN RAISE EXCEPTION '현재 방송 거래의 다음 체인 관찰만 시작할 수 있습니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_chain_attempt_binding_check ON bitcoin_payout_chain_attempts;
CREATE TRIGGER bitcoin_payout_chain_attempt_binding_check BEFORE INSERT ON bitcoin_payout_chain_attempts FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_chain_attempt_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_chain_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record;b record;
BEGIN
 SELECT * INTO a FROM bitcoin_payout_chain_attempts WHERE id=NEW.attempt_id;SELECT * INTO b FROM bitcoin_payout_broadcast_heads WHERE request_id=a.request_id;
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at<a.started_at OR NEW.recorded_at>clock_timestamp()
  OR(NEW.status='CONFIRMED' AND NEW.txid<>b.txid) THEN RAISE EXCEPTION '체인 관찰이 고정 방송 거래와 다릅니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_chain_result_binding_check ON bitcoin_payout_chain_results;
CREATE TRIGGER bitcoin_payout_chain_result_binding_check BEFORE INSERT ON bitcoin_payout_chain_results FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_chain_result_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_chain_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record;r record;action_name text;
BEGIN
 IF TG_TABLE_NAME='bitcoin_payout_chain_results' THEN r:=NEW;SELECT * INTO a FROM bitcoin_payout_chain_attempts WHERE id=r.attempt_id;
 ELSE SELECT * INTO r FROM bitcoin_payout_chain_results WHERE attempt_id=NEW.attempt_id;SELECT * INTO a FROM bitcoin_payout_chain_attempts WHERE id=NEW.attempt_id;END IF;
 action_name:=CASE r.status WHEN 'CONFIRMED' THEN 'BTC_NATIVE_PAYOUT_CHAIN_CONFIRMED' ELSE 'BTC_NATIVE_PAYOUT_CHAIN_OBSERVED' END;
 IF r.created_txid<>txid_current() OR NOT EXISTS(SELECT 1 FROM security_audit x WHERE x.actor_id IS NULL AND x.action=action_name AND x.target='bitcoin-chain-attempt:'||a.id
   AND x.outcome=CASE r.status WHEN 'CONFIRMED' THEN 'SUCCESS' ELSE 'FAILURE' END AND x.detail=r.status AND x.occurred_at>=r.recorded_at)
  OR(r.status='CONFIRMED' AND NOT EXISTS(SELECT 1 FROM bitcoin_payout_chain_heads h WHERE h.request_id=a.request_id AND h.attempt_id=a.id AND h.txid=r.txid AND h.block_hash=r.block_hash AND h.block_height=r.block_height AND h.confirmations=r.confirmations))
  OR(r.status<>'CONFIRMED' AND EXISTS(SELECT 1 FROM bitcoin_payout_chain_heads h WHERE h.attempt_id=a.id)) THEN RAISE EXCEPTION '체인 관찰·확정 머리·필수 감사는 같은 커밋이어야 합니다';END IF;RETURN NULL;
END $$;
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_chain_results','bitcoin_payout_chain_heads'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS bitcoin_payout_chain_complete_check ON %I',relation);EXECUTE format('CREATE CONSTRAINT TRIGGER bitcoin_payout_chain_complete_check AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_chain_complete()',relation);
 END LOOP;
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_chain_attempts','bitcoin_payout_chain_results','bitcoin_payout_chain_heads'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
END $$;
CREATE OR REPLACE FUNCTION bitcoin_payout_broadcast_attempt_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE latest record;
BEGIN
 SELECT a.position,a.started_at,r.status,r.recorded_at INTO latest FROM bitcoin_payout_broadcast_attempts a LEFT JOIN bitcoin_payout_broadcast_results r ON r.attempt_id=a.id
  WHERE a.request_id=NEW.request_id ORDER BY a.position DESC LIMIT 1;
 IF NEW.created_txid<>txid_current() OR NEW.started_at<clock_timestamp()-interval '10 seconds' OR NEW.started_at>clock_timestamp()
  OR EXISTS(SELECT 1 FROM bitcoin_payout_broadcast_heads WHERE request_id=NEW.request_id)
  OR(latest.position IS NOT NULL AND(NEW.position<>latest.position+1 OR(latest.status IS NULL AND latest.started_at>clock_timestamp()-interval '10 seconds')
    OR latest.status='ACCEPTED' OR latest.status='REJECTED' OR(latest.status='UNKNOWN' AND latest.recorded_at>clock_timestamp()-interval '5 seconds')))
  OR(latest.position IS NULL AND NEW.position<>1) THEN RAISE EXCEPTION '현재 고정 방송 요청의 다음 시도만 시작할 수 있습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_broadcast_attempt_binding_check ON bitcoin_payout_broadcast_attempts;
CREATE TRIGGER bitcoin_payout_broadcast_attempt_binding_check BEFORE INSERT ON bitcoin_payout_broadcast_attempts FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_broadcast_attempt_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_broadcast_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record;s record;
BEGIN
 SELECT * INTO a FROM bitcoin_payout_broadcast_attempts WHERE id=NEW.attempt_id;
 SELECT * INTO s FROM bitcoin_payout_sign_heads WHERE request_id=a.request_id;
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at<a.started_at OR NEW.recorded_at>clock_timestamp()
  OR(NEW.status='ACCEPTED' AND NEW.txid<>s.txid) THEN RAISE EXCEPTION '방송 결과가 고정 서명 결과와 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_broadcast_result_binding_check ON bitcoin_payout_broadcast_results;
CREATE TRIGGER bitcoin_payout_broadcast_result_binding_check BEFORE INSERT ON bitcoin_payout_broadcast_results FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_broadcast_result_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_broadcast_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record;r record;h record;action_name text;
BEGIN
 IF TG_TABLE_NAME='bitcoin_payout_broadcast_results' THEN r:=NEW;SELECT * INTO a FROM bitcoin_payout_broadcast_attempts WHERE id=r.attempt_id;
 ELSE h:=NEW;SELECT * INTO r FROM bitcoin_payout_broadcast_results WHERE attempt_id=h.attempt_id;SELECT * INTO a FROM bitcoin_payout_broadcast_attempts WHERE id=h.attempt_id;END IF;
 action_name:=CASE r.status WHEN 'ACCEPTED' THEN 'BTC_NATIVE_PAYOUT_BROADCAST' ELSE 'BTC_NATIVE_PAYOUT_BROADCAST_FAILED' END;
 IF r.created_txid<>txid_current() OR NOT EXISTS(SELECT 1 FROM security_audit x WHERE x.actor_id IS NULL AND x.action=action_name
   AND x.target='bitcoin-broadcast-attempt:'||a.id AND x.outcome=CASE r.status WHEN 'ACCEPTED' THEN 'SUCCESS' ELSE 'FAILURE' END
   AND x.detail=r.status AND x.occurred_at>=r.recorded_at)
  OR(r.status='ACCEPTED' AND NOT EXISTS(SELECT 1 FROM bitcoin_payout_broadcast_heads x WHERE x.request_id=a.request_id AND x.attempt_id=a.id AND x.txid=r.txid))
  OR(r.status<>'ACCEPTED' AND EXISTS(SELECT 1 FROM bitcoin_payout_broadcast_heads x WHERE x.attempt_id=a.id)) THEN
  RAISE EXCEPTION '방송 결과·현재 머리·필수 감사는 같은 커밋이어야 합니다';END IF;RETURN NULL;
END $$;
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_broadcast_results','bitcoin_payout_broadcast_heads'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS bitcoin_payout_broadcast_complete_check ON %I',relation);
  EXECUTE format('CREATE CONSTRAINT TRIGGER bitcoin_payout_broadcast_complete_check AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_broadcast_complete()',relation);
 END LOOP;
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_broadcast_attempts','bitcoin_payout_broadcast_results','bitcoin_payout_broadcast_heads'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);
  EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
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
CREATE OR REPLACE FUNCTION bitcoin_payout_sign_attempt_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE latest record;
BEGIN
 SELECT a.position,a.started_at,r.status,r.recorded_at INTO latest FROM bitcoin_payout_sign_attempts a LEFT JOIN bitcoin_payout_sign_results r ON r.attempt_id=a.id
  WHERE a.request_id=NEW.request_id ORDER BY a.position DESC LIMIT 1;
 IF NEW.created_txid<>txid_current() OR NEW.started_at<clock_timestamp()-interval '10 seconds' OR NEW.started_at>clock_timestamp()
  OR EXISTS(SELECT 1 FROM bitcoin_payout_sign_heads WHERE request_id=NEW.request_id)
  OR(latest.position IS NOT NULL AND(NEW.position<>latest.position+1 OR(latest.status IS NULL AND latest.started_at>clock_timestamp()-interval '10 seconds') OR latest.status='SIGNED'
     OR(latest.status='UNKNOWN' AND latest.recorded_at>clock_timestamp()-interval '5 seconds')))
  OR(latest.position IS NULL AND NEW.position<>1) THEN RAISE EXCEPTION '현재 고정 서명 요청의 다음 시도만 시작할 수 있습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_sign_attempt_binding_check ON bitcoin_payout_sign_attempts;
CREATE TRIGGER bitcoin_payout_sign_attempt_binding_check BEFORE INSERT ON bitcoin_payout_sign_attempts FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_sign_attempt_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_sign_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record;e record;
BEGIN
 SELECT * INTO a FROM bitcoin_payout_sign_attempts WHERE id=NEW.attempt_id;
 SELECT x.* INTO e FROM bitcoin_payout_sign_requests q JOIN bitcoin_payout_executions x ON x.withdrawal_id=q.withdrawal_id WHERE q.id=a.request_id;
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at<a.started_at OR NEW.recorded_at>clock_timestamp()
  OR(NEW.status='SIGNED' AND NEW.txid<>e.unsigned_txid) THEN RAISE EXCEPTION '서명 결과가 고정 실행 원본과 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_sign_result_binding_check ON bitcoin_payout_sign_results;
CREATE TRIGGER bitcoin_payout_sign_result_binding_check BEFORE INSERT ON bitcoin_payout_sign_results FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_sign_result_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_sign_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record;r record;h record;action_name text;detail_value text;
BEGIN
 IF TG_TABLE_NAME='bitcoin_payout_sign_results' THEN r:=NEW;SELECT * INTO a FROM bitcoin_payout_sign_attempts WHERE id=r.attempt_id;
 ELSE h:=NEW;SELECT * INTO r FROM bitcoin_payout_sign_results WHERE attempt_id=h.attempt_id;SELECT * INTO a FROM bitcoin_payout_sign_attempts WHERE id=h.attempt_id;END IF;
 action_name:=CASE r.status WHEN 'SIGNED' THEN 'BTC_NATIVE_PAYOUT_SIGNED' ELSE 'BTC_NATIVE_PAYOUT_SIGN_FAILED' END;
 detail_value:=CASE r.status WHEN 'SIGNED' THEN r.content_mac ELSE r.status END;
 IF r.created_txid<>txid_current() OR NOT EXISTS(SELECT 1 FROM security_audit x WHERE x.actor_id IS NULL AND x.action=action_name
   AND x.target='bitcoin-sign-attempt:'||a.id AND x.outcome=CASE r.status WHEN 'SIGNED' THEN 'SUCCESS' ELSE 'FAILURE' END AND x.detail=detail_value AND x.occurred_at>=r.recorded_at)
  OR(r.status='SIGNED' AND NOT EXISTS(SELECT 1 FROM bitcoin_payout_sign_heads x WHERE x.request_id=a.request_id AND x.attempt_id=a.id AND x.txid=r.txid))
  OR(r.status<>'SIGNED' AND EXISTS(SELECT 1 FROM bitcoin_payout_sign_heads x WHERE x.attempt_id=a.id)) THEN RAISE EXCEPTION '서명 결과·현재 머리·필수 감사는 같은 커밋이어야 합니다';END IF;
 RETURN NULL;
END $$;
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_sign_results','bitcoin_payout_sign_heads'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS bitcoin_payout_sign_complete_check ON %I',relation);
  EXECUTE format('CREATE CONSTRAINT TRIGGER bitcoin_payout_sign_complete_check AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_sign_complete()',relation);
 END LOOP;
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_sign_attempts','bitcoin_payout_sign_results','bitcoin_payout_sign_heads'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);
  EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
END $$;
