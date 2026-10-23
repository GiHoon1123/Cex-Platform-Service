-- 신고 원문은 암호화하고 과거 신고·평가는 수정하지 않는다. 현재 포인터만 같은 사용자 잠금에서 바꾼다.
CREATE TABLE IF NOT EXISTS cdd_declarations (
 id uuid PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), request_id uuid NOT NULL,
 ciphertext bytea NOT NULL, content_mac varchar(64) NOT NULL, key_id varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(user_id,request_id)
);
CREATE INDEX IF NOT EXISTS cdd_declaration_user_time ON cdd_declarations(user_id,created_at);
CREATE TABLE IF NOT EXISTS cdd_attempt_bindings (
 attempt_id uuid PRIMARY KEY REFERENCES kyc_attempts(id), declaration_id uuid NOT NULL REFERENCES cdd_declarations(id)
);
CREATE TABLE IF NOT EXISTS cdd_assessments (
 id uuid PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), declaration_id uuid NOT NULL REFERENCES cdd_declarations(id),
 attempt_id uuid NOT NULL REFERENCES kyc_attempts(id), event_id uuid NOT NULL UNIQUE REFERENCES kyc_events(event_id),
 status_digest varchar(64) NOT NULL, key_id varchar(64) NOT NULL, policy_version varchar(32) NOT NULL,
 outcome varchar(24) NOT NULL CHECK(outcome IN ('STANDARD','REVIEW_REQUIRED')),
 reasons varchar(500) NOT NULL, identity_mac varchar(64), assessed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS cdd_profiles (
 user_id bigint PRIMARY KEY REFERENCES users(id), declaration_id uuid NOT NULL REFERENCES cdd_declarations(id),
 assessment_id uuid REFERENCES cdd_assessments(id)
);
-- 같은 지문에 대한 동시 승인은 고유 키로 직렬화한다. 이름·생일 조합은 고유 인물 증명으로 해석하지 않는다.
CREATE TABLE IF NOT EXISTS cdd_identity_claims (
 identity_mac varchar(64) NOT NULL, key_id varchar(64) NOT NULL, user_id bigint NOT NULL REFERENCES users(id),
 PRIMARY KEY(identity_mac,key_id)
);
DROP TRIGGER IF EXISTS cdd_declaration_no_change ON cdd_declarations;
CREATE TRIGGER cdd_declaration_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON cdd_declarations FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS cdd_binding_no_change ON cdd_attempt_bindings;
CREATE TRIGGER cdd_binding_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON cdd_attempt_bindings FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS cdd_assessment_no_change ON cdd_assessments;
CREATE TRIGGER cdd_assessment_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON cdd_assessments FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS cdd_claim_no_change ON cdd_identity_claims;
CREATE TRIGGER cdd_claim_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON cdd_identity_claims FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
