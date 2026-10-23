-- 보고 여부 검토는 일반 EDD 목록과 분리한다. 원문 판단은 암호화하고 지정된 두 인물만 제공받는다.
CREATE TABLE IF NOT EXISTS compliance_reviews (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES edd_cases(id),user_id bigint NOT NULL REFERENCES users(id),
 referral_id uuid NOT NULL REFERENCES edd_reports(id),requester_id bigint NOT NULL REFERENCES users(id),
 checker_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,facts_cipher bytea NOT NULL,expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(requester_id,request_key)
);
CREATE INDEX IF NOT EXISTS compliance_review_referral ON compliance_reviews(referral_id);
CREATE INDEX IF NOT EXISTS compliance_review_assigned ON compliance_reviews(checker_id,created_at);
-- 전체 근거 페이지 제공은 본인 계정·인물·버전·세션과 요청 해시에 묶는다. 실제 독해를 자동 증명하지는 않는다.
CREATE TABLE IF NOT EXISTS compliance_review_views (
 id uuid PRIMARY KEY,review_id uuid NOT NULL REFERENCES compliance_reviews(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,page integer NOT NULL CHECK(page>=0),
 payload_hash varchar(64) NOT NULL,viewed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS compliance_view_actor ON compliance_review_views(review_id,actor_id,viewed_at);
CREATE TABLE IF NOT EXISTS compliance_review_decisions (
 review_id uuid PRIMARY KEY REFERENCES compliance_reviews(id),approver_id bigint NOT NULL REFERENCES users(id),
 approver_person uuid NOT NULL,approved boolean NOT NULL,decided_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- 반려·미보고 판단도 수정하지 않는다. 이후 판단과 실제 제출은 별도 기록이어야 한다.
DROP TRIGGER IF EXISTS compliance_review_no_change ON compliance_reviews;
CREATE TRIGGER compliance_review_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON compliance_reviews FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS compliance_view_no_change ON compliance_review_views;
CREATE TRIGGER compliance_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON compliance_review_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS compliance_decision_no_change ON compliance_review_decisions;
CREATE TRIGGER compliance_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON compliance_review_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
