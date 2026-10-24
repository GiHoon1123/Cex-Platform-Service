-- 비밀번호 원문/해시를 변경 이력에 복사하지 않는다. 인증용 해시는 users에서만 관리한다.
CREATE TABLE IF NOT EXISTS customer_password_changes (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL UNIQUE,
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS customer_password_change_history ON customer_password_changes(user_id,changed_at,id);
DROP TRIGGER IF EXISTS customer_password_change_no_change ON customer_password_changes;
CREATE TRIGGER customer_password_change_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_password_changes FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
