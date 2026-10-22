-- 외부 호출 전에 시도를 확정한다. 결과 불명이어도 새 POST로 조용히 대체하지 않는다.
CREATE TABLE IF NOT EXISTS kyc_attempts (
 id uuid PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), request_id uuid NOT NULL,
 application_id uuid NOT NULL, environment varchar(8) NOT NULL CHECK(environment IN ('live','sandbox')),
 workflow_id uuid NOT NULL, workflow_version integer NOT NULL CHECK(workflow_version>0),
 session_id uuid UNIQUE, link_cipher bytea, creation_state varchar(16) NOT NULL CHECK(creation_state IN ('CREATING','READY','UNKNOWN')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(user_id,request_id)
);
CREATE INDEX IF NOT EXISTS kyc_attempt_user_time ON kyc_attempts(user_id,created_at);
CREATE TABLE IF NOT EXISTS kyc_profiles (
 user_id bigint PRIMARY KEY REFERENCES users(id), active_attempt uuid NOT NULL REFERENCES kyc_attempts(id),
 identity_status varchar(24) NOT NULL CHECK(identity_status IN ('PENDING','VERIFIED','NOT_VERIFIED','CONFLICT')),
 provider_status varchar(40), valid_until timestamptz, event_time bigint NOT NULL DEFAULT -1,
 status_digest varchar(64), conflict_at bigint
);
-- 재전송의 timestamp는 달라질 수 있으므로 그것을 뺀 본문의 해시로 같은 이벤트를 식별한다.
-- 서명 원문·신분증·이름·생년월일·생체 자료는 이 표에 저장하지 않는다.
CREATE TABLE IF NOT EXISTS kyc_events (
 event_id uuid PRIMARY KEY, attempt_id uuid NOT NULL REFERENCES kyc_attempts(id), payload_hash varchar(64) NOT NULL,
 provider_time bigint NOT NULL, provider_status varchar(40) NOT NULL,
 disposition varchar(24) NOT NULL, received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE OR REPLACE FUNCTION kyc_event_immutable() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION '고객 확인 수신 이력은 변경하거나 삭제할 수 없습니다'; END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS kyc_event_no_change ON kyc_events;
CREATE TRIGGER kyc_event_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON kyc_events
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
