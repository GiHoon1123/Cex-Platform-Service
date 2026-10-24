-- 비밀번호 원문/해시를 변경 이력에 복사하지 않는다. 인증용 해시는 users에서만 관리한다.
CREATE TABLE IF NOT EXISTS customer_password_changes (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL UNIQUE,
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS customer_password_change_history ON customer_password_changes(user_id,changed_at,id);
DROP TRIGGER IF EXISTS customer_password_change_no_change ON customer_password_changes;
CREATE TRIGGER customer_password_change_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_password_changes FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 최초 다중 인증 사실은 키 분실·삭제 뒤에도 보존한다. 비밀번호 인증으로 자동 되돌리지 않는다.
CREATE TABLE IF NOT EXISTS customer_mfa_enrollments (
 id uuid PRIMARY KEY,user_id bigint NOT NULL UNIQUE REFERENCES users(id),credential_fingerprint varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
DROP TRIGGER IF EXISTS customer_mfa_enrollment_no_change ON customer_mfa_enrollments;
CREATE TRIGGER customer_mfa_enrollment_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_mfa_enrollments FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
-- 챌린지는 단기 인증 작업 상태다. 금융/등록 원본과 구분하며 세션·목적·정확한 확인 주소에 결합한다.
CREATE TABLE IF NOT EXISTS customer_mfa_challenges (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 purpose varchar(16) NOT NULL CHECK(purpose IN ('REGISTER','AUTHENTICATE')),
 options text NOT NULL,email_mac varchar(64),expires_at timestamptz NOT NULL,consumed boolean NOT NULL DEFAULT false,
 CHECK((purpose='REGISTER' AND email_mac IS NOT NULL) OR (purpose='AUTHENTICATE' AND email_mac IS NULL))
);
CREATE INDEX IF NOT EXISTS customer_mfa_challenge_session ON customer_mfa_challenges(session_id);

-- 공개키를 실제 폐기한 뒤에도 누가 어느 키를 추가/폐기했는지 원본 사실은 보존한다.
CREATE TABLE IF NOT EXISTS customer_mfa_key_changes (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 kind varchar(16) NOT NULL CHECK(kind IN ('ADDED','REVOKED')),
 credential_fingerprint varchar(64) NOT NULL CHECK(credential_fingerprint ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS customer_mfa_key_change_history ON customer_mfa_key_changes(user_id,created_at,id);
DROP TRIGGER IF EXISTS customer_mfa_key_change_no_change ON customer_mfa_key_changes;
CREATE TRIGGER customer_mfa_key_change_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_mfa_key_changes FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 새 비밀번호 원문/공개 해시를 저장하지 않는다. 짧은 챌린지는 계정·세션·목적별 HMAC에 결합한다.
CREATE TABLE IF NOT EXISTS customer_password_challenges (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 password_mac varchar(64) NOT NULL,key_id varchar(64) NOT NULL,options text NOT NULL,
 expires_at timestamptz NOT NULL,consumed boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS customer_password_challenge_session ON customer_password_challenges(session_id);
-- 실제 변경 원본과 사용한 챌린지/키를 불변으로 연결한다. 원본 없는 서명 성공만 커밋할 수 없다.
CREATE UNIQUE INDEX IF NOT EXISTS customer_password_change_owner ON customer_password_changes(id,user_id);
CREATE TABLE IF NOT EXISTS customer_password_authorizations (
 change_id uuid PRIMARY KEY,user_id bigint NOT NULL,
 challenge_id uuid NOT NULL UNIQUE,credential_fingerprint varchar(64) NOT NULL CHECK(credential_fingerprint ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(change_id,user_id) REFERENCES customer_password_changes(id,user_id) DEFERRABLE INITIALLY DEFERRED
);
DROP TRIGGER IF EXISTS customer_password_authorization_no_change ON customer_password_authorizations;
CREATE TRIGGER customer_password_authorization_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_password_authorizations FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 복구 코드는 해시만 저장하고 발급/사용 사실은 지우거나 덮어쓰지 않는다.
CREATE TABLE IF NOT EXISTS customer_recovery_codes (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),code_hash varchar(256) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(id,user_id)
);
CREATE TABLE IF NOT EXISTS customer_recovery_events (
 id uuid PRIMARY KEY,sequence bigserial NOT NULL UNIQUE,user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 kind varchar(24) NOT NULL CHECK(kind IN ('CODE_REPLACED','MFA_RECOVERED')),
 code_id uuid NOT NULL UNIQUE,consumed_code_id uuid UNIQUE,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((kind='CODE_REPLACED' AND consumed_code_id IS NULL) OR (kind='MFA_RECOVERED' AND consumed_code_id IS NOT NULL)),
 FOREIGN KEY(code_id,user_id) REFERENCES customer_recovery_codes(id,user_id),
 FOREIGN KEY(consumed_code_id,user_id) REFERENCES customer_recovery_codes(id,user_id)
);
CREATE INDEX IF NOT EXISTS customer_recovery_current ON customer_recovery_events(user_id,sequence DESC);
DROP TRIGGER IF EXISTS customer_recovery_code_no_change ON customer_recovery_codes;
CREATE TRIGGER customer_recovery_code_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_recovery_codes FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_recovery_event_no_change ON customer_recovery_events;
CREATE TRIGGER customer_recovery_event_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_recovery_events FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
-- 실제 서명에 쓰는 단기 작업 상태다. 불변 발급 사실과 달리 만료/소비 후 삭제한다.
CREATE TABLE IF NOT EXISTS customer_recovery_challenges (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 purpose varchar(16) NOT NULL CHECK(purpose IN ('CODE_REPLACE','MFA_RECOVER')),
 code_id uuid,options text NOT NULL,expires_at timestamptz NOT NULL,
 CHECK((purpose='CODE_REPLACE' AND code_id IS NULL) OR (purpose='MFA_RECOVER' AND code_id IS NOT NULL)),
 FOREIGN KEY(code_id,user_id) REFERENCES customer_recovery_codes(id,user_id)
);
CREATE INDEX IF NOT EXISTS customer_recovery_session ON customer_recovery_challenges(session_id);

-- 복구 한 건에 속한 키 추가/폐기를 구조적으로 연결하고 메일은 복구 원본 기준 한 건만 예약한다.
CREATE UNIQUE INDEX IF NOT EXISTS customer_recovery_event_owner ON customer_recovery_events(id,user_id);
CREATE UNIQUE INDEX IF NOT EXISTS customer_mfa_change_owner ON customer_mfa_key_changes(id,user_id);
CREATE TABLE IF NOT EXISTS customer_recovery_key_changes (
 event_id uuid NOT NULL,user_id bigint NOT NULL,change_id uuid PRIMARY KEY,
 FOREIGN KEY(event_id,user_id) REFERENCES customer_recovery_events(id,user_id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(change_id,user_id) REFERENCES customer_mfa_key_changes(id,user_id)
);
DROP TRIGGER IF EXISTS customer_recovery_key_change_no_change ON customer_recovery_key_changes;
CREATE TRIGGER customer_recovery_key_change_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_recovery_key_changes FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 본인 이력의 복합 커서와 한 복구에 속한 변경 수 조회를 각각 제한된 범위로 읽는다.
CREATE INDEX IF NOT EXISTS customer_recovery_history ON customer_recovery_events(user_id,created_at,id);
CREATE INDEX IF NOT EXISTS customer_recovery_key_source ON customer_recovery_key_changes(event_id,user_id);
