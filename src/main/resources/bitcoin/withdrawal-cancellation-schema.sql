-- 취소 의도는 새 지급 권한이 아니다. 같은 출금 원본의 총 예약금 반환만 요청한다.
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_cancellations (
 withdrawal_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawals(id),user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_release_attempts (
 id uuid PRIMARY KEY,withdrawal_id uuid NOT NULL REFERENCES bitcoin_withdrawal_cancellations(withdrawal_id),position bigserial UNIQUE,
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE INDEX IF NOT EXISTS bitcoin_withdrawal_release_due ON bitcoin_withdrawal_release_attempts(withdrawal_id,position DESC);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_release_results (
 attempt_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawal_release_attempts(id),
 outcome varchar(32) NOT NULL CHECK(outcome IN('DURABLY_RELEASED','DURABLY_REJECTED','NOT_ACCEPTED','OUTCOME_UNKNOWN')),
 completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_release_events (
 withdrawal_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawal_cancellations(withdrawal_id),
 event_id varchar(120) NOT NULL UNIQUE REFERENCES engine_event_receipts(event_id),engine_id uuid NOT NULL,
 event_time text NOT NULL,payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
 operation_key varchar(160) NOT NULL UNIQUE REFERENCES ledger_transactions(operation_key) DEFERRABLE INITIALLY DEFERRED,
 created_txid bigint NOT NULL DEFAULT txid_current()
);
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_withdrawal_cancellations','bitcoin_withdrawal_release_attempts','bitcoin_withdrawal_release_results','bitcoin_withdrawal_release_events'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);
  EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
END $$;
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_cancel_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM id FROM users WHERE id=NEW.user_id FOR UPDATE;
 IF NEW.created_txid<>txid_current() OR NEW.requested_at<clock_timestamp()-interval '10 seconds' OR NEW.requested_at>clock_timestamp()
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawals w WHERE w.id=NEW.withdrawal_id AND w.user_id=NEW.user_id)
  OR NOT EXISTS(SELECT 1 FROM refresh_tokens r JOIN users u ON u.id=r.user_id WHERE r.session_id=NEW.session_id
   AND r.user_id=NEW.user_id AND NOT r.revoked AND r.expires_at>localtimestamp AND u.enabled AND u.role='CUSTOMER')
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_reservation_events e WHERE e.withdrawal_id=NEW.withdrawal_id AND e.event_type='withdrawal_rejected') THEN
  RAISE EXCEPTION '취소는 본인의 아직 반환되지 않은 출금 원본에만 요청할 수 있습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_cancel_binding_check ON bitcoin_withdrawal_cancellations;
CREATE TRIGGER bitcoin_withdrawal_cancel_binding_check BEFORE INSERT ON bitcoin_withdrawal_cancellations FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_cancel_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_cancel_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM security_audit WHERE actor_id=NEW.user_id AND action='BTC_WITHDRAWAL_CANCEL_REQUESTED'
  AND target='bitcoin-withdrawal:'||NEW.withdrawal_id::text AND outcome='SUCCESS' AND detail='RETURN_ORIGINAL_GROSS') THEN
  RAISE EXCEPTION '출금 취소 의도와 필수 감사를 함께 보존해야 합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_cancel_audit_check ON bitcoin_withdrawal_cancellations;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_cancel_audit_check AFTER INSERT ON bitcoin_withdrawal_cancellations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_cancel_audit();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_release_attempt_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE latest record;
BEGIN
 PERFORM u.id FROM users u JOIN bitcoin_withdrawals w ON w.user_id=u.id WHERE w.id=NEW.withdrawal_id FOR UPDATE OF u;
 IF NEW.created_txid<>txid_current() OR NEW.started_at<clock_timestamp()-interval '10 seconds' OR NEW.started_at>clock_timestamp()
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_reservation_events WHERE withdrawal_id=NEW.withdrawal_id AND event_type='withdrawal_reserved')
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_events WHERE withdrawal_id=NEW.withdrawal_id)
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_attempts a JOIN bitcoin_withdrawal_release_results r ON r.attempt_id=a.id
   WHERE a.withdrawal_id=NEW.withdrawal_id AND r.outcome IN('DURABLY_RELEASED','DURABLY_REJECTED')) THEN
  RAISE EXCEPTION '출금 해제 전송은 취소 요청과 확정 예약 뒤에만 허용합니다';END IF;
 SELECT a.position,a.started_at,r.completed_at INTO latest FROM bitcoin_withdrawal_release_attempts a
  LEFT JOIN bitcoin_withdrawal_release_results r ON r.attempt_id=a.id WHERE a.withdrawal_id=NEW.withdrawal_id ORDER BY a.position DESC LIMIT 1;
 IF FOUND AND (NEW.position<=latest.position OR latest.completed_at IS NULL AND latest.started_at>clock_timestamp()-interval '30 seconds'
  OR latest.completed_at IS NOT NULL AND latest.completed_at>clock_timestamp()-interval '5 seconds') THEN
  RAISE EXCEPTION '출금 해제 재확인의 영속 대기 시간이 지나지 않았습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_release_attempt_binding_check ON bitcoin_withdrawal_release_attempts;
CREATE TRIGGER bitcoin_withdrawal_release_attempt_binding_check BEFORE INSERT ON bitcoin_withdrawal_release_attempts FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_release_attempt_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_release_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE attempt record;
BEGIN
 SELECT a.started_at,w.user_id INTO attempt FROM bitcoin_withdrawal_release_attempts a JOIN bitcoin_withdrawals w ON w.id=a.withdrawal_id WHERE a.id=NEW.attempt_id;
 IF NOT FOUND THEN RAISE EXCEPTION '출금 해제 전송 시도가 없습니다';END IF;
 PERFORM id FROM users WHERE id=attempt.user_id FOR UPDATE;
 IF NEW.created_txid<>txid_current() OR NEW.completed_at<attempt.started_at OR NEW.completed_at>clock_timestamp() THEN
  RAISE EXCEPTION '출금 해제 전송 결과의 시각·원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_release_result_binding_check ON bitcoin_withdrawal_release_results;
CREATE TRIGGER bitcoin_withdrawal_release_result_binding_check BEFORE INSERT ON bitcoin_withdrawal_release_results FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_release_result_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_release_dispatch_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE action_name text;target_name text;detail_value text;
BEGIN
 IF TG_TABLE_NAME='bitcoin_withdrawal_release_attempts' THEN
  action_name:='BTC_WITHDRAWAL_RELEASE_STARTED';target_name:='bitcoin-withdrawal-release:'||NEW.id::text;detail_value:='WITHDRAWAL:'||NEW.withdrawal_id::text;
 ELSE
  action_name:='BTC_WITHDRAWAL_RELEASE_FINISHED';target_name:='bitcoin-withdrawal-release:'||NEW.attempt_id::text;detail_value:='OUTCOME:'||NEW.outcome;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit WHERE action=action_name AND target=target_name AND outcome='SUCCESS' AND detail=detail_value) THEN
  RAISE EXCEPTION '출금 해제 전송과 필수 감사를 함께 보존해야 합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_release_attempt_audit_check ON bitcoin_withdrawal_release_attempts;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_release_attempt_audit_check AFTER INSERT ON bitcoin_withdrawal_release_attempts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_release_dispatch_audit();
DROP TRIGGER IF EXISTS bitcoin_withdrawal_release_result_audit_check ON bitcoin_withdrawal_release_results;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_release_result_audit_check AFTER INSERT ON bitcoin_withdrawal_release_results DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_release_dispatch_audit();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_release_event_commit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original record;
BEGIN
 SELECT user_id,total INTO original FROM bitcoin_withdrawals WHERE id=NEW.withdrawal_id;
 IF NEW.created_txid<>txid_current() OR NEW.operation_key<>'engine:'||NEW.event_id
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_attempts WHERE withdrawal_id=NEW.withdrawal_id)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_reservation_events WHERE withdrawal_id=NEW.withdrawal_id AND event_type='withdrawal_reserved' AND engine_id=NEW.engine_id
   AND split_part(event_id,':',2)::bigint<split_part(NEW.event_id,':',2)::bigint)
  OR NOT EXISTS(SELECT 1 FROM engine_event_receipts r WHERE r.event_id=NEW.event_id AND r.engine_id=NEW.engine_id::text AND r.payload_hash=NEW.payload_hash)
  OR NOT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.operation_key AND kind='BTC_WITHDRAWAL_RELEASED' AND created_txid=txid_current())
  OR NOT EXISTS(SELECT 1 FROM security_audit WHERE action='BTC_WITHDRAWAL_RELEASED' AND target='bitcoin-withdrawal:'||NEW.withdrawal_id::text AND outcome='SUCCESS' AND detail='EVENT:'||NEW.event_id)
  OR(SELECT count(*) FROM ledger_entries WHERE operation_key=NEW.operation_key)<>2
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key AND owner_id=original.user_id AND asset='BTC' AND bucket='LOCKED' AND delta=-original.total)
  OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key AND owner_id=original.user_id AND asset='BTC' AND bucket='AVAILABLE' AND delta=original.total) THEN
  RAISE EXCEPTION '출금 해제의 원본·확정 예약·수신 키·정확한 반환·감사를 함께 확인하세요';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_release_event_commit_check ON bitcoin_withdrawal_release_events;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_release_event_commit_check AFTER INSERT ON bitcoin_withdrawal_release_events DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_release_event_commit();
