-- 이름·금액·수취 참조 등 관찰 내용은 암호문으로만 남긴다. 두 자료를 묶은 검토·결정은 불변 이력이다.
CREATE TABLE IF NOT EXISTS edd_funds_reviews (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES edd_cases(id),origin_evidence uuid NOT NULL REFERENCES edd_evidence(id),receipt_evidence uuid NOT NULL REFERENCES edd_evidence(id),
 CHECK(origin_evidence<>receipt_evidence),
 user_id bigint NOT NULL REFERENCES users(id),requester_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,facts_cipher bytea NOT NULL,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(requester_id,request_key)
);
CREATE INDEX IF NOT EXISTS edd_funds_review_case ON edd_funds_reviews(case_id,created_at);
CREATE TABLE IF NOT EXISTS edd_funds_decisions (
 review_id uuid PRIMARY KEY REFERENCES edd_funds_reviews(id),approver_id bigint NOT NULL REFERENCES users(id),
 approver_person uuid NOT NULL,origin_view uuid NOT NULL REFERENCES edd_evidence_views(id),receipt_view uuid NOT NULL REFERENCES edd_evidence_views(id),
 approved boolean NOT NULL,decided_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
DROP TRIGGER IF EXISTS edd_funds_review_no_change ON edd_funds_reviews;
CREATE TRIGGER edd_funds_review_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_funds_reviews FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS edd_funds_decision_no_change ON edd_funds_decisions;
CREATE TRIGGER edd_funds_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_funds_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
