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
