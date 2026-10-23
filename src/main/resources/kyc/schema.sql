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

-- 시도별 대사 작업을 영속 보관한다. 임대 소유권과 로컬 변경 번호로 늦은 HTTP 응답을 배제한다.
-- 기존 고객의 확인 상태를 추측해 채우지 않는다. 새 표의 확인 기한이 없으면 주문은 닫힌다.
CREATE TABLE IF NOT EXISTS kyc_reconciliations (
 attempt_id uuid PRIMARY KEY REFERENCES kyc_attempts(id),
 revision bigint NOT NULL DEFAULT 0, fresh_at timestamptz,
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '1 minute',
 lease_id uuid, lease_until timestamptz, failures integer NOT NULL DEFAULT 0,
 outcome varchar(32)
);
CREATE INDEX IF NOT EXISTS kyc_reconciliation_due ON kyc_reconciliations(next_attempt_at);
-- 조회 원문·신분증·서명·확인 링크 대신 최소 해시와 실행 결과만 남긴다.
CREATE TABLE IF NOT EXISTS kyc_reconciliation_runs (
 id uuid PRIMARY KEY, attempt_id uuid NOT NULL REFERENCES kyc_attempts(id),
 outcome varchar(32) NOT NULL, decision_hash varchar(64), delivery_count integer NOT NULL,
 completed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
DROP TRIGGER IF EXISTS kyc_reconciliation_run_no_change ON kyc_reconciliation_runs;
CREATE TRIGGER kyc_reconciliation_run_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON kyc_reconciliation_runs
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 한 고객의 429 응답 뒤 다른 고객을 즉시 조회해 같은 공급사 제한을 더 악화시키지 않는다.
CREATE TABLE IF NOT EXISTS kyc_provider_cooldowns (
 application_id uuid PRIMARY KEY, not_before timestamptz NOT NULL
);
