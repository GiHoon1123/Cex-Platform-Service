-- 의도 기록과 실제 전송 권한을 구분한다. 최초 전송 직전의 현재 근거는 별도로 보존한다.
CREATE TABLE IF NOT EXISTS bitcoin_credit_authorizations (
 receipt_id uuid PRIMARY KEY REFERENCES bitcoin_credit_intents(receipt_id),user_id bigint NOT NULL REFERENCES users(id),
 asset varchar(16) NOT NULL CHECK(asset='BTC'),amount numeric(30,9) NOT NULL CHECK(amount>0 AND amount=trunc(amount,8)),
 policy_id varchar(40) NOT NULL CHECK(policy_id='BTC_VERIFIED_VASP_CREDIT_V1'),
 basis text NOT NULL CHECK(octet_length(basis)<=131072 AND jsonb_typeof(basis::jsonb)='object'),
 basis_hash varchar(64) NOT NULL CHECK(basis_hash=encode(sha256(convert_to(basis,'UTF8')),'hex')),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
-- 현재 고객/체인/출처/조사/대사의 같은 검증을 사용한다. 과거 의도가 현재 허가를 대신하지 않는다.
DROP TRIGGER IF EXISTS bitcoin_credit_authorization_binding_check ON bitcoin_credit_authorizations;
CREATE TRIGGER bitcoin_credit_authorization_binding_check BEFORE INSERT ON bitcoin_credit_authorizations FOR EACH ROW EXECUTE FUNCTION bitcoin_credit_intent_binding();
CREATE TABLE IF NOT EXISTS bitcoin_credit_dispatch_attempts (
 id uuid PRIMARY KEY,position bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
 receipt_id uuid NOT NULL REFERENCES bitcoin_credit_authorizations(receipt_id),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE INDEX IF NOT EXISTS bitcoin_credit_attempt_receipt ON bitcoin_credit_dispatch_attempts(receipt_id,position DESC);
CREATE TABLE IF NOT EXISTS bitcoin_credit_dispatch_results (
 attempt_id uuid PRIMARY KEY REFERENCES bitcoin_credit_dispatch_attempts(id),
 outcome varchar(32) NOT NULL CHECK(outcome IN ('DURABLY_CREDITED','DURABLY_REJECTED','NOT_ACCEPTED','OUTCOME_UNKNOWN')),
 completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE OR REPLACE FUNCTION bitcoin_credit_attempt_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous record;
BEGIN
 -- 같은 고객의 실행 시작을 직렬화한다. RPC는 이 TX가 끝난 뒤에만 수행한다.
 PERFORM u.id FROM users u JOIN bitcoin_credit_authorizations a ON a.user_id=u.id WHERE a.receipt_id=NEW.receipt_id FOR UPDATE OF u;
 IF NEW.created_txid<>txid_current() OR NEW.started_at>clock_timestamp() OR NEW.started_at<clock_timestamp()-interval '10 seconds'
  OR EXISTS(SELECT 1 FROM bitcoin_credit_dispatch_attempts a JOIN bitcoin_credit_dispatch_results r ON r.attempt_id=a.id WHERE a.receipt_id=NEW.receipt_id AND r.outcome IN ('DURABLY_CREDITED','DURABLY_REJECTED')) THEN
  RAISE EXCEPTION '입금 전송은 같은 원본의 미확정 실행에만 추가할 수 있습니다';END IF;
 SELECT a.position,a.started_at,r.completed_at INTO previous FROM bitcoin_credit_dispatch_attempts a LEFT JOIN bitcoin_credit_dispatch_results r ON r.attempt_id=a.id
  WHERE a.receipt_id=NEW.receipt_id ORDER BY a.position DESC LIMIT 1;
 IF FOUND AND (NEW.position<=previous.position OR (previous.completed_at IS NULL AND previous.started_at>clock_timestamp()-interval '30 seconds')
  OR (previous.completed_at IS NOT NULL AND previous.completed_at>clock_timestamp()-interval '5 seconds')) THEN
  RAISE EXCEPTION '입금 전송 재확인 대기 시간이 지나지 않았습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_credit_attempt_binding_check ON bitcoin_credit_dispatch_attempts;
CREATE TRIGGER bitcoin_credit_attempt_binding_check BEFORE INSERT ON bitcoin_credit_dispatch_attempts FOR EACH ROW EXECUTE FUNCTION bitcoin_credit_attempt_binding();
CREATE OR REPLACE FUNCTION bitcoin_credit_dispatch_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source record;
BEGIN
 SELECT a.receipt_id,a.started_at,c.user_id INTO source FROM bitcoin_credit_dispatch_attempts a JOIN bitcoin_credit_authorizations c ON c.receipt_id=a.receipt_id WHERE a.id=NEW.attempt_id;
 IF NOT FOUND THEN RAISE EXCEPTION '입금 전송 시도 원본이 없습니다';END IF;
 PERFORM id FROM users WHERE id=source.user_id FOR UPDATE;
 IF NEW.created_txid<>txid_current() OR NEW.completed_at<source.started_at OR NEW.completed_at>clock_timestamp()
  OR (NEW.outcome='DURABLY_REJECTED' AND EXISTS(SELECT 1 FROM bitcoin_credited_deposits WHERE receipt_id=source.receipt_id))
  OR EXISTS(SELECT 1 FROM bitcoin_credit_dispatch_attempts a JOIN bitcoin_credit_dispatch_results r ON r.attempt_id=a.id
   WHERE a.receipt_id=source.receipt_id AND r.outcome IN ('DURABLY_CREDITED','DURABLY_REJECTED') AND NEW.outcome IN ('DURABLY_CREDITED','DURABLY_REJECTED') AND r.outcome<>NEW.outcome) THEN
  RAISE EXCEPTION '입금 전송 결과와 원본 또는 이미 확정한 결과가 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_credit_result_binding_check ON bitcoin_credit_dispatch_results;
CREATE TRIGGER bitcoin_credit_result_binding_check BEFORE INSERT ON bitcoin_credit_dispatch_results FOR EACH ROW EXECUTE FUNCTION bitcoin_credit_dispatch_result_binding();
CREATE OR REPLACE FUNCTION bitcoin_credit_dispatch_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE action_name text;target_name text;detail_value text;after_time timestamptz;
BEGIN
 IF TG_TABLE_NAME='bitcoin_credit_authorizations' THEN
  action_name:='BTC_CREDIT_DISPATCH_AUTHORIZED';target_name:='bitcoin-credit:'||NEW.receipt_id::text;detail_value:='BASIS:'||NEW.basis_hash;after_time:=NEW.recorded_at;
  IF NOT EXISTS(SELECT 1 FROM bitcoin_credit_dispatch_attempts a WHERE a.receipt_id=NEW.receipt_id AND a.created_txid=NEW.created_txid) THEN RAISE EXCEPTION '입금 실행 권한에는 같은 TX의 최초 전송 시도가 필요합니다';END IF;
 ELSIF TG_TABLE_NAME='bitcoin_credit_dispatch_attempts' THEN
  action_name:='BTC_CREDIT_DISPATCH_STARTED';target_name:='bitcoin-credit-attempt:'||NEW.id::text;detail_value:='RECEIPT:'||NEW.receipt_id::text;after_time:=NEW.started_at;
 ELSE
  action_name:='BTC_CREDIT_DISPATCH_FINISHED';target_name:='bitcoin-credit-attempt:'||NEW.attempt_id::text;detail_value:='OUTCOME:'||NEW.outcome;after_time:=NEW.completed_at;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NULL AND a.action=action_name AND a.target=target_name AND a.outcome='SUCCESS' AND a.detail=detail_value AND a.occurred_at>=after_time AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '입금 실행 권한과 시도/결과에는 같은 원본의 필수 감사가 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_credit_authorization_audit_check ON bitcoin_credit_authorizations;
CREATE CONSTRAINT TRIGGER bitcoin_credit_authorization_audit_check AFTER INSERT ON bitcoin_credit_authorizations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_credit_dispatch_audit();
DROP TRIGGER IF EXISTS bitcoin_credit_attempt_audit_check ON bitcoin_credit_dispatch_attempts;
CREATE CONSTRAINT TRIGGER bitcoin_credit_attempt_audit_check AFTER INSERT ON bitcoin_credit_dispatch_attempts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_credit_dispatch_audit();
DROP TRIGGER IF EXISTS bitcoin_credit_result_audit_check ON bitcoin_credit_dispatch_results;
CREATE CONSTRAINT TRIGGER bitcoin_credit_result_audit_check AFTER INSERT ON bitcoin_credit_dispatch_results DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_credit_dispatch_audit();
DROP TRIGGER IF EXISTS bitcoin_credit_authorizations_immutable ON bitcoin_credit_authorizations;
CREATE TRIGGER bitcoin_credit_authorizations_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_credit_authorizations FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS bitcoin_credit_attempts_immutable ON bitcoin_credit_dispatch_attempts;
CREATE TRIGGER bitcoin_credit_attempts_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_credit_dispatch_attempts FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS bitcoin_credit_results_immutable ON bitcoin_credit_dispatch_results;
CREATE TRIGGER bitcoin_credit_results_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_credit_dispatch_results FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 엔진 확정 이벤트와 금융 반영의 원본 연결이다. HTTP 접수/성공으로 이 행을 만들지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_credited_deposits (
 receipt_id uuid PRIMARY KEY REFERENCES bitcoin_credit_authorizations(receipt_id),
 event_id varchar(120) NOT NULL UNIQUE REFERENCES engine_event_receipts(event_id),engine_id uuid NOT NULL,
 event_time varchar(40) NOT NULL CHECK(length(event_time)>0),payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
 operation_key varchar(160) NOT NULL UNIQUE REFERENCES ledger_transactions(operation_key),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE OR REPLACE FUNCTION bitcoin_credited_deposit_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original record;
BEGIN
 SELECT * INTO original FROM bitcoin_credit_authorizations WHERE receipt_id=NEW.receipt_id;
 IF NOT FOUND THEN RAISE EXCEPTION '입금 실행 권한 원본이 없습니다';END IF;
 PERFORM id FROM users WHERE id=original.user_id FOR UPDATE;
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at>clock_timestamp() OR NEW.operation_key<>'engine:'||NEW.event_id
  OR NEW.event_id !~ ('^'||NEW.engine_id::text||':[1-9][0-9]*:0$')
  OR NOT EXISTS(SELECT 1 FROM engine_event_receipts e WHERE e.event_id=NEW.event_id AND e.engine_id=NEW.engine_id::text AND e.payload_hash=NEW.payload_hash)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_credit_dispatch_attempts a WHERE a.receipt_id=NEW.receipt_id)
  OR EXISTS(SELECT 1 FROM bitcoin_credit_dispatch_attempts a JOIN bitcoin_credit_dispatch_results r ON r.attempt_id=a.id WHERE a.receipt_id=NEW.receipt_id AND r.outcome='DURABLY_REJECTED') THEN
  RAISE EXCEPTION '입금 확정 이벤트와 실행/수신 원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_credited_deposit_binding_check ON bitcoin_credited_deposits;
CREATE TRIGGER bitcoin_credited_deposit_binding_check BEFORE INSERT ON bitcoin_credited_deposits FOR EACH ROW EXECUTE FUNCTION bitcoin_credited_deposit_binding();
CREATE OR REPLACE FUNCTION bitcoin_credited_deposit_commit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original record;operation record;balance record;
BEGIN
 SELECT * INTO original FROM bitcoin_credit_authorizations WHERE receipt_id=NEW.receipt_id;
 SELECT * INTO operation FROM ledger_transactions WHERE operation_key=NEW.operation_key;
 SELECT * INTO balance FROM user_balances WHERE user_id=original.user_id AND mint_address=original.asset;
 IF operation.kind IS DISTINCT FROM 'BTC_DEPOSIT_CREDIT' OR operation.created_txid IS DISTINCT FROM NEW.created_txid
  OR(SELECT count(*) FROM ledger_entries WHERE operation_key=NEW.operation_key)<>2
  OR NOT EXISTS(SELECT 1 FROM ledger_entries e WHERE e.operation_key=NEW.operation_key AND e.owner_id=original.user_id AND e.bucket='PENDING' AND e.asset=original.asset AND e.delta=-original.amount)
  OR NOT EXISTS(SELECT 1 FROM ledger_entries e WHERE e.operation_key=NEW.operation_key AND e.owner_id=original.user_id AND e.bucket='AVAILABLE' AND e.asset=original.asset AND e.delta=original.amount)
  OR balance.id IS NULL
  OR balance.available IS DISTINCT FROM(SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id=original.user_id AND asset=original.asset AND bucket='AVAILABLE')
  OR balance.locked IS DISTINCT FROM(SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id=original.user_id AND asset=original.asset AND bucket='LOCKED') THEN
  RAISE EXCEPTION '입금 확정에는 같은 TX의 정확한 보류 해제 원장과 조회 잔액이 필요합니다';END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NULL AND a.action='BTC_DEPOSIT_CREDITED' AND a.target='bitcoin-deposit:'||NEW.receipt_id::text AND a.outcome='SUCCESS' AND a.detail='EVENT:'||NEW.event_id AND a.occurred_at>=NEW.recorded_at AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '입금 확정에는 같은 이벤트의 필수 감사가 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_credited_deposit_commit_check ON bitcoin_credited_deposits;
CREATE CONSTRAINT TRIGGER bitcoin_credited_deposit_commit_check AFTER INSERT ON bitcoin_credited_deposits DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_credited_deposit_commit();
DROP TRIGGER IF EXISTS bitcoin_credited_deposits_immutable ON bitcoin_credited_deposits;
CREATE TRIGGER bitcoin_credited_deposits_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_credited_deposits FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 입금 결과 연결 없이 업무 종류만 사칭해 원장을 만들지 못한다. 반대 방향은 위 결과 커밋 제약이 검증한다.
CREATE OR REPLACE FUNCTION bitcoin_credit_ledger_source_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bitcoin_credited_deposits c WHERE c.operation_key=NEW.operation_key AND c.created_txid=NEW.created_txid) THEN
  RAISE EXCEPTION '입금 해제 원장에는 같은 TX의 엔진 확정 결과가 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_credit_ledger_source_check ON ledger_transactions;
CREATE CONSTRAINT TRIGGER bitcoin_credit_ledger_source_check AFTER INSERT ON ledger_transactions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.kind='BTC_DEPOSIT_CREDIT') EXECUTE FUNCTION bitcoin_credit_ledger_source_commit();
