-- 고객이 자발적으로 불필요한 원문을 보내지 않게 운영자가 자료 종류와 현재 평가를 지정한다.
CREATE TABLE IF NOT EXISTS edd_evidence_requests (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES edd_cases(id),user_id bigint NOT NULL REFERENCES users(id),
 assessment_id uuid NOT NULL REFERENCES cdd_assessments(id),
 purpose varchar(32) NOT NULL CHECK(purpose IN ('SOURCE_OF_FUNDS','SOURCE_OF_WEALTH','RESIDENTIAL_ADDRESS')),
 requester_id bigint NOT NULL REFERENCES users(id),requester_person uuid NOT NULL,request_key uuid NOT NULL,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(requester_id,request_key)
);
CREATE INDEX IF NOT EXISTS edd_evidence_request_user ON edd_evidence_requests(user_id,created_at);
-- 요청당 제출은 한 번이다. 원본과 픽셀을 다시 쓴 열람본은 독립 인증 문맥으로 암호화한다.
CREATE TABLE IF NOT EXISTS edd_evidence (
 id uuid PRIMARY KEY,request_id uuid NOT NULL UNIQUE REFERENCES edd_evidence_requests(id),user_id bigint NOT NULL REFERENCES users(id),
 original_digest varchar(64) NOT NULL,original_bytes integer NOT NULL CHECK(original_bytes BETWEEN 1 AND 1048576),
 view_bytes integer NOT NULL CHECK(view_bytes BETWEEN 1 AND 4194304),key_id varchar(64) NOT NULL,
 original_cipher bytea NOT NULL,view_cipher bytea NOT NULL,received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(octet_length(original_cipher)=original_bytes+28),CHECK(octet_length(view_cipher)=view_bytes+28)
);
CREATE INDEX IF NOT EXISTS edd_evidence_user ON edd_evidence(user_id);
DROP TRIGGER IF EXISTS edd_evidence_request_no_change ON edd_evidence_requests;
CREATE TRIGGER edd_evidence_request_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_evidence_requests FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS edd_evidence_no_change ON edd_evidence;
CREATE TRIGGER edd_evidence_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_evidence FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
