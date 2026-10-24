-- 고객의 재검토 요청은 내부 종료 사유와 다른 입력이다. 접수 후 수정/삭제하지 않고 보존한다.
CREATE TABLE IF NOT EXISTS customer_exit_review_requests (
 id uuid PRIMARY KEY,user_id bigint NOT NULL,exit_id uuid NOT NULL,request_key uuid NOT NULL,
 reason_cipher bytea NOT NULL,content_mac varchar(64) NOT NULL,fingerprint_key_id varchar(64) NOT NULL,
 evidence_key_id varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(exit_id,user_id) REFERENCES relationship_exit_decisions(review_id,user_id),UNIQUE(user_id,request_key)
);
CREATE INDEX IF NOT EXISTS customer_exit_review_own ON customer_exit_review_requests(user_id,created_at,id);
CREATE INDEX IF NOT EXISTS customer_exit_review_origin ON customer_exit_review_requests(exit_id,created_at,id);
DROP TRIGGER IF EXISTS customer_exit_review_no_change ON customer_exit_review_requests;
CREATE TRIGGER customer_exit_review_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_exit_review_requests FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
