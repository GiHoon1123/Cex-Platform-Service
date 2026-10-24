-- 최종 판단의 고객 문구는 내부 기록과 별도로 암호화하고 승인 전 공개하지 않는다.
CREATE TABLE IF NOT EXISTS customer_review_final_requests (
 id uuid PRIMARY KEY,review_id uuid NOT NULL REFERENCES customer_exit_review_requests(id),
 requester_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,explanation_cipher bytea NOT NULL,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(requester_id,request_key),UNIQUE(id,review_id)
);
CREATE INDEX IF NOT EXISTS customer_review_final_candidates ON customer_review_final_requests(review_id,created_at,id);
CREATE TABLE IF NOT EXISTS customer_review_final_views (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES customer_review_final_requests(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,payload_hash varchar(64) NOT NULL,
 provided_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS customer_review_final_decisions (
 request_id uuid PRIMARY KEY,review_id uuid NOT NULL,approver_id bigint NOT NULL REFERENCES users(id),approver_person uuid NOT NULL,
 approved boolean NOT NULL,decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(request_id,review_id) REFERENCES customer_review_final_requests(id,review_id)
);
-- 반려 뒤 새 초안은 허용하지만 같은 고객 접수의 확정 답변을 둘 만들지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS customer_review_final_once ON customer_review_final_decisions(review_id) WHERE approved;
DROP TRIGGER IF EXISTS customer_review_final_request_no_change ON customer_review_final_requests;
CREATE TRIGGER customer_review_final_request_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_review_final_requests FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_review_final_view_no_change ON customer_review_final_views;
CREATE TRIGGER customer_review_final_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_review_final_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_review_final_decision_no_change ON customer_review_final_decisions;
CREATE TRIGGER customer_review_final_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_review_final_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
