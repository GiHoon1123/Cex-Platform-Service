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

-- 열람 사실은 당시 인물·버전·세션과 함께 보존한다. 과거 일반 감사를 새 영수증으로 추측해 채우지 않는다.
CREATE TABLE IF NOT EXISTS customer_exit_review_views (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES customer_exit_review_requests(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,provided_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS customer_review_personal_view ON customer_exit_review_views(request_id,actor_id,session_id,provided_at);
CREATE TABLE IF NOT EXISTS customer_exit_review_notes (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES customer_exit_review_requests(id),sequence bigint NOT NULL CHECK(sequence>0),
 actor_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,progress varchar(32) NOT NULL CHECK(progress IN ('INVESTIGATING','ADDITIONAL_CHECK_REQUIRED')),
 note_cipher bytea NOT NULL,content_mac varchar(64) NOT NULL,evidence_key_id varchar(64) NOT NULL,fingerprint_key_id varchar(64) NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(actor_id,request_key),UNIQUE(request_id,sequence)
);
DROP TRIGGER IF EXISTS customer_review_view_no_change ON customer_exit_review_views;
CREATE TRIGGER customer_review_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_exit_review_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_review_note_no_change ON customer_exit_review_notes;
CREATE TRIGGER customer_review_note_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_exit_review_notes FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
