-- 서비스 메일 선택은 주소별로 보존한다. 기본은 미선택이며 이전 선택 행을 덮어쓰지 않는다.
CREATE TABLE IF NOT EXISTS customer_service_mail_preferences (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),sequence bigint NOT NULL CHECK(sequence>0),request_key uuid NOT NULL,
 enabled boolean NOT NULL,email_mac varchar(64),key_id varchar(64) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(user_id,sequence),UNIQUE(user_id,request_key),CHECK((enabled AND email_mac IS NOT NULL) OR (NOT enabled AND email_mac IS NULL))
);
DROP TRIGGER IF EXISTS customer_service_mail_preference_no_change ON customer_service_mail_preferences;
CREATE TRIGGER customer_service_mail_preference_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_service_mail_preferences FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
