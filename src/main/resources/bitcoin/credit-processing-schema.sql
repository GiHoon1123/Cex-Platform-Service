-- 작업 일정은 금융 허가가 아니다. 실제 의도/실행/결과는 별도 불변 표에서 복원한다.
CREATE TABLE IF NOT EXISTS bitcoin_credit_jobs (
 receipt_id uuid PRIMARY KEY REFERENCES bitcoin_pending_deposits(receipt_id),
 state varchar(24) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RETRY','HELD','WAITING_EVENT','ENGINE_REJECTED','SETTLED')),
 next_attempt_at timestamptz DEFAULT clock_timestamp(),lease_id uuid,lease_until timestamptz,
 failures integer NOT NULL DEFAULT 0 CHECK(failures>=0),last_reason varchar(64),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((lease_id IS NULL)=(lease_until IS NULL)),
 CHECK((state IN ('ENGINE_REJECTED','SETTLED'))=(next_attempt_at IS NULL))
);
CREATE INDEX IF NOT EXISTS bitcoin_credit_job_due ON bitcoin_credit_jobs(next_attempt_at,receipt_id) WHERE next_attempt_at IS NOT NULL;
CREATE OR REPLACE FUNCTION bitcoin_credit_job_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION '입금 처리 작업을 삭제할 수 없습니다';END IF;
 IF TG_OP='UPDATE' AND(NEW.receipt_id<>OLD.receipt_id OR NEW.created_at<>OLD.created_at OR(OLD.state IN ('SETTLED','ENGINE_REJECTED') AND NEW.state<>OLD.state)) THEN
  RAISE EXCEPTION '입금 처리 작업의 원본과 종료 사실을 바꿀 수 없습니다';END IF;
 IF NEW.state='SETTLED' AND NOT EXISTS(SELECT 1 FROM bitcoin_credited_deposits WHERE receipt_id=NEW.receipt_id) THEN RAISE EXCEPTION '실제 금융 결과 없이 입금 작업을 완료할 수 없습니다';END IF;
 IF NEW.state IN ('WAITING_EVENT','ENGINE_REJECTED') AND NOT EXISTS(
  SELECT 1 FROM bitcoin_credit_dispatch_attempts a JOIN bitcoin_credit_dispatch_results r ON r.attempt_id=a.id
   WHERE a.receipt_id=NEW.receipt_id AND r.outcome=CASE WHEN NEW.state='WAITING_EVENT' THEN 'DURABLY_CREDITED' ELSE 'DURABLY_REJECTED' END
 ) THEN RAISE EXCEPTION '실제 확정 응답 없이 입금 작업 상태를 바꿀 수 없습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_credit_job_binding_check ON bitcoin_credit_jobs;
CREATE TRIGGER bitcoin_credit_job_binding_check BEFORE INSERT OR UPDATE OR DELETE ON bitcoin_credit_jobs FOR EACH ROW EXECUTE FUNCTION bitcoin_credit_job_binding();
DROP TRIGGER IF EXISTS bitcoin_credit_jobs_no_truncate ON bitcoin_credit_jobs;
CREATE TRIGGER bitcoin_credit_jobs_no_truncate BEFORE TRUNCATE ON bitcoin_credit_jobs FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
