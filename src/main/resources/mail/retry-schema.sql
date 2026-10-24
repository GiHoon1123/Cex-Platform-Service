-- 재발송은 원문 수정 없이 한 번의 추가 시도 예산만 승인한다.
CREATE TABLE IF NOT EXISTS customer_mail_retry_requests (
 id uuid PRIMARY KEY,job_id uuid NOT NULL REFERENCES customer_mail_jobs(id),requester_id bigint NOT NULL REFERENCES users(id),
 checker_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,payload text NOT NULL,payload_hash varchar(64) NOT NULL,
 payload_mac varchar(64) NOT NULL,key_id varchar(64) NOT NULL,expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(requester_id,request_key),UNIQUE(id,job_id),CHECK(requester_id<>checker_id)
);
CREATE INDEX IF NOT EXISTS customer_mail_retry_job ON customer_mail_retry_requests(job_id,created_at);
CREATE TABLE IF NOT EXISTS customer_mail_retry_views (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES customer_mail_retry_requests(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,payload_hash varchar(64) NOT NULL,viewed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS customer_mail_retry_decisions (
 request_id uuid PRIMARY KEY,job_id uuid NOT NULL,approver_id bigint NOT NULL REFERENCES users(id),approved boolean NOT NULL,
 decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),FOREIGN KEY(request_id,job_id) REFERENCES customer_mail_retry_requests(id,job_id),UNIQUE(request_id,job_id,approved)
);
CREATE TABLE IF NOT EXISTS customer_mail_retry_grants (
 job_id uuid PRIMARY KEY,request_id uuid NOT NULL UNIQUE,approved boolean NOT NULL CHECK(approved),max_attempts integer NOT NULL CHECK(max_attempts=10),
 FOREIGN KEY(request_id,job_id,approved) REFERENCES customer_mail_retry_decisions(request_id,job_id,approved)
);
DROP TRIGGER IF EXISTS customer_mail_retry_request_no_change ON customer_mail_retry_requests;
CREATE TRIGGER customer_mail_retry_request_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_mail_retry_requests FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_mail_retry_view_no_change ON customer_mail_retry_views;
CREATE TRIGGER customer_mail_retry_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_mail_retry_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_mail_retry_decision_no_change ON customer_mail_retry_decisions;
CREATE TRIGGER customer_mail_retry_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_mail_retry_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_mail_retry_grant_no_change ON customer_mail_retry_grants;
CREATE TRIGGER customer_mail_retry_grant_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_mail_retry_grants FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
