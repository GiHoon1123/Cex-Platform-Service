-- 발송할 요청은 불변이다. 외부 주소/토큰은 암호화하고 운영 임대/재시도 상태만 별도 갱신한다.
CREATE TABLE IF NOT EXISTS customer_mail_jobs (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),kind varchar(32) NOT NULL,reference_id uuid NOT NULL,
 content_mac varchar(64) NOT NULL,key_id varchar(64) NOT NULL,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(kind,reference_id)
);
-- 운영 목록의 시각/식별자 순회에 사용한다. 암호문 표를 읽거나 큐 행을 잠그지 않는다.
CREATE INDEX IF NOT EXISTS customer_mail_candidate_order ON customer_mail_jobs(created_at,id);
CREATE TABLE IF NOT EXISTS customer_mail_payloads (job_id uuid PRIMARY KEY REFERENCES customer_mail_jobs(id),payload_cipher bytea NOT NULL);
CREATE TABLE IF NOT EXISTS customer_mail_delivery (
 job_id uuid PRIMARY KEY REFERENCES customer_mail_jobs(id),status varchar(24) NOT NULL CHECK(status IN ('PENDING','SENDING','RETRY','SMTP_ACCEPTED','SUPPRESSED','EXHAUSTED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),lease_id uuid,lease_until timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),last_reason varchar(32) NOT NULL DEFAULT 'QUEUED'
);
CREATE INDEX IF NOT EXISTS customer_mail_due ON customer_mail_delivery(next_attempt_at,job_id) WHERE status IN ('PENDING','RETRY','SENDING');
CREATE TABLE IF NOT EXISTS customer_mail_attempts (
 id uuid PRIMARY KEY,job_id uuid NOT NULL REFERENCES customer_mail_jobs(id),lease_id uuid NOT NULL,attempt integer NOT NULL,
 phase varchar(16) NOT NULL CHECK(phase IN ('CLAIMED','RESULT')),outcome varchar(32) NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(job_id,lease_id,phase)
);
DROP TRIGGER IF EXISTS customer_mail_job_no_change ON customer_mail_jobs;
CREATE TRIGGER customer_mail_job_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_mail_jobs FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_mail_attempt_no_change ON customer_mail_attempts;
CREATE TRIGGER customer_mail_attempt_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_mail_attempts FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 발송 감사와 주소/일회 토큰의 보존은 다르다. 만료 후 하루가 지난 종료 건의 암호문만 제거할 수 있다.
CREATE OR REPLACE FUNCTION customer_mail_payload_retention_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM customer_mail_jobs j JOIN customer_mail_delivery d ON d.job_id=j.id WHERE j.id=OLD.job_id AND j.expires_at<clock_timestamp()-interval '1 day' AND d.status IN ('SMTP_ACCEPTED','SUPPRESSED','EXHAUSTED')) THEN RAISE EXCEPTION '메일 암호문 보존 조건이 충족되지 않았습니다'; END IF;
 RETURN OLD;
END $$;
DROP TRIGGER IF EXISTS customer_mail_payload_no_change ON customer_mail_payloads;
CREATE TRIGGER customer_mail_payload_no_change BEFORE UPDATE OR TRUNCATE ON customer_mail_payloads FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_mail_payload_retention ON customer_mail_payloads;
CREATE TRIGGER customer_mail_payload_retention BEFORE DELETE ON customer_mail_payloads FOR EACH ROW EXECUTE FUNCTION customer_mail_payload_retention_guard();
