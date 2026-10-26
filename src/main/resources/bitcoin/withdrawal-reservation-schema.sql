-- 고객의 일회 서명은 견적 원본에 결합한다. 아직 서명되지 않은 챌린지는 금융 이력이 아니다.
CREATE TABLE IF NOT EXISTS customer_withdrawal_challenges (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 quote_id uuid NOT NULL REFERENCES bitcoin_withdrawal_quotes(id),quote_hash varchar(64) NOT NULL,
 scope_mac varchar(64) NOT NULL,key_id varchar(64) NOT NULL,options text NOT NULL,
 expires_at timestamptz NOT NULL,consumed boolean NOT NULL DEFAULT false,
 CHECK(quote_hash ~ '^[0-9a-f]{64}$' AND scope_mac ~ '^[0-9a-f]{64}$' AND key_id ~ '^[0-9a-f]{64}$')
);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawals (
 id uuid PRIMARY KEY REFERENCES bitcoin_withdrawal_quotes(id),user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 network varchar(32) NOT NULL CHECK(network IN('bitcoin-regtest','bitcoin-mainnet')),asset varchar(8) NOT NULL CHECK(asset='BTC'),
 amount numeric(16,8) NOT NULL CHECK(amount>0),fee numeric(16,8) NOT NULL CHECK(fee>0),total numeric(16,8) NOT NULL CHECK(total=amount+fee AND total<=21000000),
 quote_hash varchar(64) NOT NULL CHECK(quote_hash ~ '^[0-9a-f]{64}$'),
 operation_key varchar(160) NOT NULL UNIQUE REFERENCES ledger_transactions(operation_key) DEFERRABLE INITIALLY DEFERRED,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK(fee=ceil(amount*0.0001*100000000)/100000000)
);
CREATE TABLE IF NOT EXISTS customer_withdrawal_authorizations (
 withdrawal_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawals(id) DEFERRABLE INITIALLY DEFERRED,
 user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,quote_hash varchar(64) NOT NULL,
 challenge_id uuid NOT NULL UNIQUE REFERENCES customer_withdrawal_challenges(id),credential_fingerprint varchar(64) NOT NULL,
 created_txid bigint NOT NULL DEFAULT txid_current(),CHECK(credential_fingerprint ~ '^[0-9a-f]{64}$')
);
-- 출금 요청과 전송 대기는 동일한 영속 원본을 사용한다. HTTP 결과는 금융 확정과 구분한다.
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_dispatch_attempts (
 id uuid PRIMARY KEY,withdrawal_id uuid NOT NULL REFERENCES bitcoin_withdrawals(id),position bigserial UNIQUE,
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE INDEX IF NOT EXISTS bitcoin_withdrawal_dispatch_due ON bitcoin_withdrawal_dispatch_attempts(withdrawal_id,position DESC);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_dispatch_results (
 attempt_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawal_dispatch_attempts(id),
 outcome varchar(32) NOT NULL CHECK(outcome IN('DURABLY_RESERVED','DURABLY_REJECTED','NOT_ACCEPTED','OUTCOME_UNKNOWN')),
 completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_reservation_events (
 withdrawal_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawals(id),event_id varchar(120) NOT NULL UNIQUE REFERENCES engine_event_receipts(event_id),
 engine_id uuid NOT NULL,event_type varchar(32) NOT NULL CHECK(event_type IN('withdrawal_reserved','withdrawal_rejected')),
 event_time text NOT NULL,payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
 operation_key varchar(160) NOT NULL UNIQUE REFERENCES ledger_transactions(operation_key) DEFERRABLE INITIALLY DEFERRED,
 created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM id FROM users WHERE id=NEW.user_id FOR UPDATE;
 IF NEW.created_txid<>txid_current() OR NOT EXISTS(
  SELECT 1 FROM bitcoin_withdrawal_quotes q JOIN users u ON u.id=q.user_id
   WHERE q.id=NEW.id AND q.user_id=NEW.user_id AND q.session_id=NEW.session_id AND q.payload_hash=NEW.quote_hash
    AND q.network=NEW.network AND q.amount=NEW.amount AND q.fee=NEW.fee AND q.total=NEW.total
    AND q.expires_at>clock_timestamp() AND q.user_version=u.version AND u.enabled AND u.role='CUSTOMER'
    AND NEW.operation_key='withdrawal-reserve:'||NEW.id::text) THEN
  RAISE EXCEPTION '출금 요청과 현재 고객·세션·견적 원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_binding_check ON bitcoin_withdrawals;
CREATE TRIGGER bitcoin_withdrawal_binding_check BEFORE INSERT ON bitcoin_withdrawals FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM customer_withdrawal_authorizations a JOIN customer_withdrawal_challenges c ON c.id=a.challenge_id
  WHERE a.withdrawal_id=NEW.id AND a.user_id=NEW.user_id AND a.session_id=NEW.session_id AND a.quote_hash=NEW.quote_hash
   AND a.created_txid=txid_current() AND c.consumed AND c.user_id=NEW.user_id AND c.session_id=NEW.session_id AND c.quote_id=NEW.id AND c.quote_hash=NEW.quote_hash) THEN
  RAISE EXCEPTION '출금 예약에는 같은 고객·세션·원본의 일회 서명 확정이 필요합니다';END IF;
 IF NOT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.operation_key AND kind='BTC_WITHDRAWAL_RESERVE' AND created_txid=txid_current())
  OR(SELECT count(*) FROM ledger_entries WHERE operation_key=NEW.operation_key)<>2
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key AND owner_id=NEW.user_id AND bucket='AVAILABLE' AND asset='BTC' AND delta=-NEW.total)
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key AND owner_id=NEW.user_id AND bucket='LOCKED' AND asset='BTC' AND delta=NEW.total) THEN
  RAISE EXCEPTION '출금 요청과 정확한 총액의 양쪽 원장 예약이 다릅니다';END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit WHERE actor_id=NEW.user_id AND action='BTC_WITHDRAWAL_REQUESTED' AND target='bitcoin-withdrawal:'||NEW.id::text AND outcome='SUCCESS' AND detail=NEW.quote_hash)
  OR NOT EXISTS(SELECT 1 FROM security_audit WHERE actor_id=NEW.user_id AND action='CUSTOMER_WITHDRAWAL_AUTHORIZED' AND target='bitcoin-withdrawal:'||NEW.id::text AND outcome='SUCCESS') THEN
  RAISE EXCEPTION '출금 서명·원본·예약은 필수 감사와 함께 확정해야 합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_commit_check ON bitcoin_withdrawals;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_commit_check AFTER INSERT ON bitcoin_withdrawals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_commit();
-- 일반 DML과 전체 비우기로 금융 원본·서명·전송 결과를 수정하지 못하게 한다.
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_withdrawals','customer_withdrawal_authorizations','bitcoin_withdrawal_dispatch_attempts','bitcoin_withdrawal_dispatch_results','bitcoin_withdrawal_reservation_events'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);
  EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
END $$;
-- 재전송의 출발점도 고객 잠금 아래 결정한다. 오래된 불명 시도는 같은 ID/금액으로만 다시 전송한다.
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_attempt_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE latest record;
BEGIN
 PERFORM u.id FROM users u JOIN bitcoin_withdrawals w ON w.user_id=u.id WHERE w.id=NEW.withdrawal_id FOR UPDATE OF u;
 IF NEW.created_txid<>txid_current() OR NEW.started_at<clock_timestamp()-interval '10 seconds' OR NEW.started_at>clock_timestamp()
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_reservation_events e WHERE e.withdrawal_id=NEW.withdrawal_id)
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_dispatch_attempts a JOIN bitcoin_withdrawal_dispatch_results r ON r.attempt_id=a.id
   WHERE a.withdrawal_id=NEW.withdrawal_id AND r.outcome IN('DURABLY_RESERVED','DURABLY_REJECTED')) THEN
  RAISE EXCEPTION '출금 예약 전송은 아직 확정되지 않은 원본에만 추가할 수 있습니다';END IF;
 SELECT a.position,a.started_at,r.completed_at INTO latest FROM bitcoin_withdrawal_dispatch_attempts a
  LEFT JOIN bitcoin_withdrawal_dispatch_results r ON r.attempt_id=a.id WHERE a.withdrawal_id=NEW.withdrawal_id ORDER BY a.position DESC LIMIT 1;
 IF FOUND AND (NEW.position<=latest.position OR latest.completed_at IS NULL AND latest.started_at>clock_timestamp()-interval '30 seconds'
  OR latest.completed_at IS NOT NULL AND latest.completed_at>clock_timestamp()-interval '5 seconds') THEN
  RAISE EXCEPTION '출금 예약 재확인의 영속 대기 시간이 지나지 않았습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_attempt_binding_check ON bitcoin_withdrawal_dispatch_attempts;
CREATE TRIGGER bitcoin_withdrawal_attempt_binding_check BEFORE INSERT ON bitcoin_withdrawal_dispatch_attempts FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_attempt_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE attempt record;
BEGIN
 SELECT a.withdrawal_id,a.started_at,w.user_id INTO attempt FROM bitcoin_withdrawal_dispatch_attempts a
  JOIN bitcoin_withdrawals w ON w.id=a.withdrawal_id WHERE a.id=NEW.attempt_id;
 IF NOT FOUND THEN RAISE EXCEPTION '출금 예약 전송 시도가 없습니다';END IF;
 PERFORM id FROM users WHERE id=attempt.user_id FOR UPDATE;
 IF NEW.created_txid<>txid_current() OR NEW.completed_at<attempt.started_at OR NEW.completed_at>clock_timestamp() THEN
  RAISE EXCEPTION '출금 예약 전송 결과의 시간·원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_result_binding_check ON bitcoin_withdrawal_dispatch_results;
CREATE TRIGGER bitcoin_withdrawal_result_binding_check BEFORE INSERT ON bitcoin_withdrawal_dispatch_results FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_result_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_event_commit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original record;wanted_kind text;
BEGIN
 SELECT user_id,total INTO original FROM bitcoin_withdrawals WHERE id=NEW.withdrawal_id;
 wanted_kind:=CASE WHEN NEW.event_type='withdrawal_reserved' THEN 'BTC_WITHDRAWAL_RESERVED' ELSE 'BTC_WITHDRAWAL_REJECTED' END;
 IF NEW.created_txid<>txid_current() OR NEW.operation_key<>'engine:'||NEW.event_id
  OR NOT EXISTS(SELECT 1 FROM engine_event_receipts r WHERE r.event_id=NEW.event_id AND r.engine_id=NEW.engine_id::text AND r.payload_hash=NEW.payload_hash)
  OR NOT EXISTS(SELECT 1 FROM ledger_transactions t WHERE t.operation_key=NEW.operation_key AND t.kind=wanted_kind AND t.created_txid=txid_current())
  OR NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.action='BTC_WITHDRAWAL_ENGINE_RESULT'
   AND a.target='bitcoin-withdrawal:'||NEW.withdrawal_id::text AND a.outcome='SUCCESS' AND a.detail='EVENT:'||NEW.event_id) THEN
  RAISE EXCEPTION '출금 금융 결과의 수신 키·원장·감사와 같은 커밋을 확인하세요';END IF;
 IF NEW.event_type='withdrawal_reserved' AND EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key) THEN
  RAISE EXCEPTION '출금 예약 확인이 고객 잔액을 다시 이동했습니다';END IF;
 IF NEW.event_type='withdrawal_rejected' AND(
  (SELECT count(*) FROM ledger_entries WHERE operation_key=NEW.operation_key)<>2
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key AND owner_id=original.user_id AND asset='BTC' AND bucket='LOCKED' AND delta=-original.total)
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key AND owner_id=original.user_id AND asset='BTC' AND bucket='AVAILABLE' AND delta=original.total)) THEN
  RAISE EXCEPTION '출금 금융 거절은 원래 총액만 한 번 반환해야 합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_event_commit_check ON bitcoin_withdrawal_reservation_events;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_event_commit_check AFTER INSERT ON bitcoin_withdrawal_reservation_events DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_event_commit();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_dispatch_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE action_name text;target_name text;detail_value text;
BEGIN
 IF TG_TABLE_NAME='bitcoin_withdrawal_dispatch_attempts' THEN
  action_name:='BTC_WITHDRAWAL_DISPATCH_STARTED';target_name:='bitcoin-withdrawal-attempt:'||NEW.id::text;
  detail_value:='WITHDRAWAL:'||NEW.withdrawal_id::text;
 ELSE
  action_name:='BTC_WITHDRAWAL_DISPATCH_FINISHED';target_name:='bitcoin-withdrawal-attempt:'||NEW.attempt_id::text;
  detail_value:='OUTCOME:'||NEW.outcome;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.action=action_name AND a.target=target_name
  AND a.outcome='SUCCESS' AND a.detail=detail_value AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '출금 전송의 필수 감사가 없습니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_attempt_audit_check ON bitcoin_withdrawal_dispatch_attempts;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_attempt_audit_check AFTER INSERT ON bitcoin_withdrawal_dispatch_attempts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_dispatch_audit();
DROP TRIGGER IF EXISTS bitcoin_withdrawal_result_audit_check ON bitcoin_withdrawal_dispatch_results;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_result_audit_check AFTER INSERT ON bitcoin_withdrawal_dispatch_results DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_dispatch_audit();
