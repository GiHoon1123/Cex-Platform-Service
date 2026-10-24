-- 원래 판단과 작성자를 고치지 않고 후속 업무 인계 요청·결정만 추가한다.
CREATE TABLE IF NOT EXISTS customer_work_custody_requests (
 id uuid PRIMARY KEY,review_id uuid NOT NULL REFERENCES customer_exit_review_requests(id),user_id bigint NOT NULL REFERENCES users(id),
 generation bigint NOT NULL CHECK(generation>0),requester_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 primary_id bigint NOT NULL REFERENCES users(id),checker_id bigint NOT NULL REFERENCES users(id),
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,reason_cipher bytea NOT NULL,expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(requester_id,request_key),UNIQUE(id,review_id,generation)
);
CREATE INDEX IF NOT EXISTS customer_work_custody_request_review ON customer_work_custody_requests(review_id,generation);
CREATE TABLE IF NOT EXISTS customer_work_custody_views (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES customer_work_custody_requests(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,payload_hash varchar(64) NOT NULL,
 viewed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS customer_work_custody_decisions (
 request_id uuid PRIMARY KEY,review_id uuid NOT NULL,generation bigint NOT NULL,
 approver_id bigint NOT NULL REFERENCES users(id),approver_person uuid NOT NULL,approved boolean NOT NULL,
 decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(request_id,review_id,generation) REFERENCES customer_work_custody_requests(id,review_id,generation)
);
-- 같은 세대의 인계 승인은 하나만 허용한다. 반려·만료한 요청은 지우지 않고 새로 요청한다.
CREATE UNIQUE INDEX IF NOT EXISTS customer_work_custody_one_approval ON customer_work_custody_decisions(review_id,generation) WHERE approved;
DROP TRIGGER IF EXISTS customer_work_custody_request_no_change ON customer_work_custody_requests;
CREATE TRIGGER customer_work_custody_request_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_work_custody_requests FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_work_custody_view_no_change ON customer_work_custody_views;
CREATE TRIGGER customer_work_custody_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_work_custody_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_work_custody_decision_no_change ON customer_work_custody_decisions;
CREATE TRIGGER customer_work_custody_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_work_custody_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
