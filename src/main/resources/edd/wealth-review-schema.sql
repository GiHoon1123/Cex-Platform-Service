-- 이름·금액·수취 참조 등 관찰 내용은 암호문으로만 남긴다. 세 자료를 묶은 검토·결정은 불변 이력이다.
CREATE TABLE IF NOT EXISTS edd_wealth_reviews (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES edd_cases(id),opening_evidence uuid NOT NULL REFERENCES edd_evidence(id),changes_evidence uuid NOT NULL REFERENCES edd_evidence(id),closing_evidence uuid NOT NULL REFERENCES edd_evidence(id),
 CHECK(opening_evidence<>changes_evidence AND opening_evidence<>closing_evidence AND changes_evidence<>closing_evidence),
 user_id bigint NOT NULL REFERENCES users(id),requester_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,facts_cipher bytea NOT NULL,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(requester_id,request_key)
);
CREATE INDEX IF NOT EXISTS edd_wealth_review_case ON edd_wealth_reviews(case_id,created_at);
CREATE TABLE IF NOT EXISTS edd_wealth_decisions (
 review_id uuid PRIMARY KEY REFERENCES edd_wealth_reviews(id),approver_id bigint NOT NULL REFERENCES users(id),
 approver_person uuid NOT NULL,opening_view uuid NOT NULL REFERENCES edd_evidence_views(id),changes_view uuid NOT NULL REFERENCES edd_evidence_views(id),closing_view uuid NOT NULL REFERENCES edd_evidence_views(id),
 approved boolean NOT NULL,decided_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
DROP TRIGGER IF EXISTS edd_wealth_review_no_change ON edd_wealth_reviews;
CREATE TRIGGER edd_wealth_review_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_wealth_reviews FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS edd_wealth_decision_no_change ON edd_wealth_decisions;
CREATE TRIGGER edd_wealth_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_wealth_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
