-- 확인 토큰 원문은 발송용 암호문에만 있다. 검증 표에는 고엔트로피 토큰의 해시만 남긴다.
CREATE TABLE IF NOT EXISTS customer_email_challenges (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,email_mac varchar(64) NOT NULL,
 rate_mac varchar(64) NOT NULL,key_id varchar(64) NOT NULL,token_hash varchar(64) NOT NULL,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(user_id,request_key)
);
CREATE INDEX IF NOT EXISTS customer_email_user_rate ON customer_email_challenges(user_id,created_at);
CREATE INDEX IF NOT EXISTS customer_email_address_rate ON customer_email_challenges(rate_mac,created_at);
CREATE TABLE IF NOT EXISTS customer_email_confirmations (
 challenge_id uuid PRIMARY KEY REFERENCES customer_email_challenges(id),user_id bigint NOT NULL REFERENCES users(id),
 email_mac varchar(64) NOT NULL,key_id varchar(64) NOT NULL,confirmed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS customer_email_verified_address ON customer_email_confirmations(user_id,email_mac,key_id);
DROP TRIGGER IF EXISTS customer_email_challenge_no_change ON customer_email_challenges;
CREATE TRIGGER customer_email_challenge_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_email_challenges FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS customer_email_confirmation_no_change ON customer_email_confirmations;
CREATE TRIGGER customer_email_confirmation_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_email_confirmations FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 최초 키 등록은 실제 이메일 토큰을 확인한 세션에 결합한다. 과거 확인 이력의 세션을 추측해서 채우지 않는다.
CREATE TABLE IF NOT EXISTS customer_email_confirmation_sessions (
 challenge_id uuid PRIMARY KEY REFERENCES customer_email_confirmations(challenge_id),session_id uuid NOT NULL
);
DROP TRIGGER IF EXISTS customer_email_confirmation_session_no_change ON customer_email_confirmation_sessions;
CREATE TRIGGER customer_email_confirmation_session_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON customer_email_confirmation_sessions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
