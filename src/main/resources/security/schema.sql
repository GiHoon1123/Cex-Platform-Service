-- 감사 기록은 업무 변경과 같은 트랜잭션에서 추가한다. 앱에 수정·삭제 경로를 제공하지 않는다.
CREATE TABLE IF NOT EXISTS security_audit (
 id uuid PRIMARY KEY, correlation_id uuid NOT NULL, actor_id bigint,
 action varchar(64) NOT NULL, target varchar(160) NOT NULL,
 actor_role varchar(16), actor_person uuid, source_address varchar(64) NOT NULL,
 outcome varchar(16) NOT NULL CHECK (outcome IN ('SUCCESS','DENIED','ALLOWED')),
 detail varchar(500) NOT NULL, occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS security_audit_time ON security_audit(occurred_at,id);
CREATE INDEX IF NOT EXISTS security_audit_actor_action ON security_audit(actor_id,action,occurred_at);
CREATE OR REPLACE FUNCTION security_audit_immutable() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION '감사 기록은 변경하거나 삭제할 수 없습니다'; END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS security_audit_no_change ON security_audit;
CREATE TRIGGER security_audit_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON security_audit
 FOR EACH STATEMENT EXECUTE FUNCTION security_audit_immutable();

CREATE TABLE IF NOT EXISTS operator_requests (
 id uuid PRIMARY KEY, requester_id bigint NOT NULL, requester_person uuid NOT NULL,
 target_id bigint NOT NULL, target_version bigint NOT NULL, restricted boolean NOT NULL,
 reason varchar(300) NOT NULL, payload_hash varchar(64) NOT NULL,
 expires_at timestamptz NOT NULL, status varchar(16) NOT NULL DEFAULT 'PENDING',
 approver_id bigint, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- 승인 대상은 생성 이후 바뀌지 않는다. 만료·거부·완료 상태도 재사용할 수 없다.
CREATE OR REPLACE FUNCTION operator_request_immutable() RETURNS trigger AS $$
BEGIN
 IF TG_OP <> 'UPDATE' THEN RAISE EXCEPTION '승인 요청은 삭제할 수 없습니다'; END IF;
 IF OLD.status <> 'PENDING' OR NEW.status NOT IN ('APPROVED','REJECTED')
    OR (to_jsonb(NEW)-'status'-'approver_id') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'approver_id')
    OR NEW.approver_id IS NULL THEN RAISE EXCEPTION '승인 대상 변경 또는 재사용 금지'; END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS operator_request_no_change ON operator_requests;
CREATE TRIGGER operator_request_no_change BEFORE UPDATE OR DELETE ON operator_requests
 FOR EACH ROW EXECUTE FUNCTION operator_request_immutable();
DROP TRIGGER IF EXISTS operator_request_no_truncate ON operator_requests;
CREATE TRIGGER operator_request_no_truncate BEFORE TRUNCATE ON operator_requests
 FOR EACH STATEMENT EXECUTE FUNCTION security_audit_immutable();

-- 보안키 사용자·공개키는 Spring Security의 JDBC 저장 계약을 따른다. 개인키는 서버에 오지 않는다.
CREATE TABLE IF NOT EXISTS user_entities (
 id varchar(1000) PRIMARY KEY, name varchar(100) NOT NULL UNIQUE, display_name varchar(200) NOT NULL
);
CREATE TABLE IF NOT EXISTS user_credentials (
 credential_id varchar(1400) PRIMARY KEY, user_entity_user_id varchar(1000) NOT NULL REFERENCES user_entities(id),
 public_key bytea NOT NULL, signature_count bigint NOT NULL, uv_initialized boolean NOT NULL,
 backup_eligible boolean NOT NULL, authenticator_transports varchar(100) NOT NULL,
 public_key_credential_type varchar(100) NOT NULL, backup_state boolean NOT NULL,
 attestation_object bytea NOT NULL, attestation_client_data_json bytea NOT NULL,
 created timestamp NOT NULL, last_used timestamp NOT NULL, label varchar(100) NOT NULL
);
CREATE TABLE IF NOT EXISTS operator_enrollment (
 user_id bigint PRIMARY KEY REFERENCES users(id), token_hash varchar(64) NOT NULL,
 expires_at timestamptz NOT NULL, consumed boolean NOT NULL DEFAULT false
);
-- 챌린지는 사용자·세션·목적에 묶는다. 재시작 후에도 일회성 소비 여부가 유지된다.
CREATE TABLE IF NOT EXISTS operator_mfa_challenges (
 id uuid PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), session_id uuid NOT NULL,
 purpose varchar(16) NOT NULL CHECK(purpose IN ('REGISTER','AUTHENTICATE')),
 options text NOT NULL, expires_at timestamptz NOT NULL, consumed boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS operator_mfa_challenge_session ON operator_mfa_challenges(session_id);

-- 복구·재검토 자료는 생성 뒤 바꾸지 못한다. 재신청은 새 ID·새 대상 버전으로 남긴다.
CREATE TABLE IF NOT EXISTS operator_security_requests (
 id uuid PRIMARY KEY, kind varchar(20) NOT NULL CHECK(kind IN ('RECOVER_MFA','REVIEW_ACCESS')),
 requester_id bigint NOT NULL, requester_person uuid NOT NULL, target_id bigint NOT NULL, target_person uuid NOT NULL,
 target_version bigint NOT NULL, access_until timestamptz, reason varchar(200) NOT NULL,
 identity_evidence varchar(100) NOT NULL, notification_evidence varchar(100) NOT NULL,
 payload_hash varchar(64) NOT NULL, expires_at timestamptz NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING', approver_id bigint, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
DROP TRIGGER IF EXISTS operator_security_requests_no_change ON operator_security_requests;
CREATE TRIGGER operator_security_requests_no_change BEFORE UPDATE OR DELETE ON operator_security_requests
 FOR EACH ROW EXECUTE FUNCTION operator_request_immutable();
DROP TRIGGER IF EXISTS operator_security_requests_no_truncate ON operator_security_requests;
CREATE TRIGGER operator_security_requests_no_truncate BEFORE TRUNCATE ON operator_security_requests
 FOR EACH STATEMENT EXECUTE FUNCTION security_audit_immutable();

-- 일반 역할 부여·변경도 업무 내용이 고정된 요청으로 보존한다. 기존 금융·감사 기록은 유지한다.
CREATE TABLE IF NOT EXISTS operator_role_requests (
 id uuid PRIMARY KEY, requester_id bigint NOT NULL, requester_person uuid NOT NULL, requester_version bigint NOT NULL,
 target_id bigint NOT NULL, target_person uuid NOT NULL, target_version bigint NOT NULL,
 previous_role varchar(16) NOT NULL CHECK(previous_role IN ('CUSTOMER','OPERATOR','APPROVER','AUDITOR')),
 requested_role varchar(16) NOT NULL CHECK(requested_role IN ('OPERATOR','APPROVER','AUDITOR')),
 access_until timestamptz NOT NULL, reason varchar(200) NOT NULL,
 identity_evidence varchar(100) NOT NULL, notification_evidence varchar(100) NOT NULL,
 payload_hash varchar(64) NOT NULL, expires_at timestamptz NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING', approver_id bigint, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(previous_role<>requested_role)
);
DROP TRIGGER IF EXISTS operator_role_requests_no_change ON operator_role_requests;
CREATE TRIGGER operator_role_requests_no_change BEFORE UPDATE OR DELETE ON operator_role_requests
 FOR EACH ROW EXECUTE FUNCTION operator_request_immutable();
DROP TRIGGER IF EXISTS operator_role_requests_no_truncate ON operator_role_requests;
CREATE TRIGGER operator_role_requests_no_truncate BEFORE TRUNCATE ON operator_role_requests
 FOR EACH STATEMENT EXECUTE FUNCTION security_audit_immutable();
-- 승인 근거는 원래 요청과 별도로 불변 보관한다. 과거 업무·감사 표를 바꾸거나 이전하지 않는다.
-- 요청 당시 담당자 버전까지 고정해 역할을 바꿨다 되돌려도 예전 요청이 살아나지 못하게 한다.
CREATE TABLE IF NOT EXISTS operator_approval_bindings (
 kind varchar(24) NOT NULL CHECK(kind IN ('ROLE','MFA_RECOVERY','ACCESS_REVIEW','TRADING_RESTRICTION','EDD_REVIEW','EDD_ADDRESS_REVIEW','EDD_FUNDS_REVIEW')),
 request_id uuid NOT NULL, requester_id bigint NOT NULL REFERENCES users(id), requester_version bigint NOT NULL,
 payload_hash varchar(64) NOT NULL, expires_at timestamptz NOT NULL,
 PRIMARY KEY(kind,request_id)
);
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='operator_approval_bindings'::regclass
  AND contype='c' AND conname='operator_approval_bindings_kind_check' AND pg_get_constraintdef(oid) LIKE '%EDD_REVIEW%' AND pg_get_constraintdef(oid) LIKE '%EDD_ADDRESS_REVIEW%' AND pg_get_constraintdef(oid) LIKE '%EDD_FUNDS_REVIEW%')
 THEN RAISE EXCEPTION '현재 승인 스키마가 필요합니다. 과거 요청을 자동 변환하지 않습니다'; END IF;
END $$;
DROP TRIGGER IF EXISTS operator_approval_binding_no_change ON operator_approval_bindings;
CREATE TRIGGER operator_approval_binding_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON operator_approval_bindings
 FOR EACH STATEMENT EXECUTE FUNCTION security_audit_immutable();
-- 옛 역할 전용 옵션은 단기 인증 상태일 뿐 업무·감사 이력이 아니다. 우회·호환 경로 없이 폐기한다.
DROP TABLE IF EXISTS operator_role_challenges;
CREATE TABLE IF NOT EXISTS operator_authorization_challenges (
 id uuid PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), session_id uuid NOT NULL,
 kind varchar(24) NOT NULL, request_id uuid NOT NULL, payload_hash varchar(64) NOT NULL,
 approve boolean NOT NULL, options text NOT NULL, expires_at timestamptz NOT NULL, consumed boolean NOT NULL DEFAULT false,
 FOREIGN KEY(kind,request_id) REFERENCES operator_approval_bindings(kind,request_id)
);
CREATE INDEX IF NOT EXISTS operator_authorization_challenge_session ON operator_authorization_challenges(session_id);
