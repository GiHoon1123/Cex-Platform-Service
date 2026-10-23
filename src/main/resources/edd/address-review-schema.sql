-- 열람본을 제공한 사실을 고객·자료·세션·현재 평가에 묶는다. 사람이 실제 읽었다는 자동 증명은 아니다.
CREATE TABLE IF NOT EXISTS edd_evidence_views (
 id uuid PRIMARY KEY,evidence_id uuid NOT NULL REFERENCES edd_evidence(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,
 assessment_id uuid NOT NULL REFERENCES cdd_assessments(id),case_revision bigint NOT NULL,key_id varchar(64) NOT NULL,
 viewed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS edd_evidence_view_actor ON edd_evidence_views(actor_id,viewed_at);
-- 이름·주소 등 읽어 적은 내용은 암호문으로만 남기고 공개 승인 내용에는 독립 키 지문을 넣는다.
CREATE TABLE IF NOT EXISTS edd_address_reviews (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES edd_cases(id),evidence_id uuid NOT NULL REFERENCES edd_evidence(id),
 user_id bigint NOT NULL REFERENCES users(id),requester_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,facts_cipher bytea NOT NULL,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(requester_id,request_key)
);
CREATE INDEX IF NOT EXISTS edd_address_review_case ON edd_address_reviews(case_id,created_at);
CREATE TABLE IF NOT EXISTS edd_address_decisions (
 review_id uuid PRIMARY KEY REFERENCES edd_address_reviews(id),approver_id bigint NOT NULL REFERENCES users(id),
 approver_person uuid NOT NULL,view_id uuid NOT NULL REFERENCES edd_evidence_views(id),
 approved boolean NOT NULL,decided_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
DROP TRIGGER IF EXISTS edd_evidence_view_no_change ON edd_evidence_views;
CREATE TRIGGER edd_evidence_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_evidence_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS edd_address_review_no_change ON edd_address_reviews;
CREATE TRIGGER edd_address_review_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_address_reviews FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS edd_address_decision_no_change ON edd_address_decisions;
CREATE TRIGGER edd_address_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_address_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
