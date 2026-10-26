-- 외부 확인 의도/시도/관측 결과는 지급 권한과 구분해 보존한다. 개인 자료는 인증 암호문 안에만 둔다.
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_transfer_intents (
 id uuid PRIMARY KEY,position bigserial UNIQUE,withdrawal_id uuid NOT NULL REFERENCES bitcoin_withdrawals(id),
 user_id bigint NOT NULL REFERENCES users(id),requester_id bigint NOT NULL REFERENCES users(id),requester_person uuid NOT NULL,
 requester_version bigint NOT NULL,requester_session_id uuid NOT NULL,request_key uuid NOT NULL,
 peer_id varchar(128) NOT NULL,quote_hash varchar(64) NOT NULL,meaning_mac varchar(64) NOT NULL,
 directory_source_id varchar(64) NOT NULL REFERENCES codevasp_directory_sources(id),directory_attempt_id bigint NOT NULL REFERENCES codevasp_directory_attempts(id),
 policy_hash varchar(64) NOT NULL,key_id varchar(64) NOT NULL,ciphertext bytea NOT NULL,content_mac varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),expires_at timestamptz NOT NULL,created_txid bigint NOT NULL DEFAULT txid_current(),
 UNIQUE(requester_id,request_key),CHECK(peer_id ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$'),
 CHECK(quote_hash ~ '^[0-9a-f]{64}$' AND meaning_mac ~ '^[0-9a-f]{64}$' AND key_id ~ '^[0-9a-f]{64}$' AND content_mac ~ '^[0-9a-f]{64}$' AND policy_hash ~ '^[0-9a-f]{64}$'),
 CHECK(octet_length(ciphertext) BETWEEN 28 AND 65536 AND expires_at>created_at AND expires_at<=created_at+interval '15 minutes')
);
CREATE INDEX IF NOT EXISTS bitcoin_withdrawal_transfer_latest ON bitcoin_withdrawal_transfer_intents(withdrawal_id,position DESC);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_transfer_attempts (
 id uuid PRIMARY KEY,position bigserial UNIQUE,intent_id uuid NOT NULL REFERENCES bitcoin_withdrawal_transfer_intents(id),
 withdrawal_id uuid NOT NULL REFERENCES bitcoin_withdrawals(id),user_id bigint NOT NULL REFERENCES users(id),
 stage varchar(16) NOT NULL CHECK(stage IN('ADDRESS','TRANSFER')),actor_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 directory_attempt_id bigint NOT NULL REFERENCES codevasp_directory_attempts(id),policy_hash varchar(64) NOT NULL,peer_public_key varchar(44) NOT NULL,
 key_id varchar(64) NOT NULL,ciphertext bytea NOT NULL,content_mac varchar(64) NOT NULL,
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK(key_id ~ '^[0-9a-f]{64}$' AND content_mac ~ '^[0-9a-f]{64}$' AND policy_hash ~ '^[0-9a-f]{64}$' AND octet_length(ciphertext) BETWEEN 28 AND 65536)
);
CREATE INDEX IF NOT EXISTS bitcoin_withdrawal_transfer_attempt_latest ON bitcoin_withdrawal_transfer_attempts(intent_id,stage,position DESC);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_transfer_results (
 attempt_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawal_transfer_attempts(id),
 status varchar(24) NOT NULL CHECK(status IN('VALID','INVALID','VERIFIED','DENIED','NOT_SENT','OUTCOME_UNKNOWN')),reason varchar(64),
 key_id varchar(64),ciphertext bytea,content_mac varchar(64),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK((status IN('VALID','VERIFIED') AND reason IS NULL OR status IN('INVALID','DENIED','NOT_SENT','OUTCOME_UNKNOWN') AND reason IS NOT NULL)
  AND(status IN('VALID','INVALID','VERIFIED','DENIED') AND key_id IS NOT NULL AND ciphertext IS NOT NULL AND content_mac IS NOT NULL
   AND key_id ~ '^[0-9a-f]{64}$' AND content_mac ~ '^[0-9a-f]{64}$' AND octet_length(ciphertext) BETWEEN 28 AND 65536
   OR status IN('NOT_SENT','OUTCOME_UNKNOWN') AND key_id IS NULL AND ciphertext IS NULL AND content_mac IS NULL))
 ,CHECK(status IN('VALID','VERIFIED') OR status='INVALID' AND reason IN('NOT_FOUND_ADDRESS','NOT_SUPPORTED_SYMBOL','NOT_KYC_USER','SANCTION_LIST','LACK_OF_INFORMATION','UNKNOWN')
  OR status='DENIED' AND reason IN('NOT_FOUND_ADDRESS','NOT_SUPPORTED_SYMBOL','NOT_KYC_USER','INPUT_NAME_MISMATCHED','DOB_MISMATCHED','SANCTION_LIST','LACK_OF_INFORMATION','UNKNOWN')
  OR status IN('NOT_SENT','OUTCOME_UNKNOWN') AND reason IN('PROVIDER_UNAVAILABLE','INVALID_SOURCE'))
);
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_withdrawal_transfer_intents','bitcoin_withdrawal_transfer_attempts','bitcoin_withdrawal_transfer_results'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);
  EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
END $$;
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_transfer_intent_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous uuid;prior_status text;
BEGIN
 PERFORM id FROM users WHERE id IN(NEW.user_id,NEW.requester_id) ORDER BY id FOR UPDATE;
 IF NEW.created_txid<>txid_current() OR NEW.created_at<clock_timestamp()-interval '10 seconds' OR NEW.created_at>clock_timestamp()
  OR NOT EXISTS(SELECT 1 FROM users WHERE id=NEW.requester_id AND enabled AND role='OPERATOR' AND operator_person_id=NEW.requester_person AND version=NEW.requester_version)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawals w JOIN bitcoin_withdrawal_valuations v ON v.withdrawal_id=w.id
   JOIN bitcoin_withdrawal_reservation_events e ON e.withdrawal_id=w.id AND e.event_type='withdrawal_reserved'
   WHERE w.id=NEW.withdrawal_id AND w.user_id=NEW.user_id AND w.quote_hash=NEW.quote_hash AND v.krw_value>=1000000)
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_cancellations WHERE withdrawal_id=NEW.withdrawal_id)
  OR NOT EXISTS(SELECT 1 FROM codevasp_directory_attempts WHERE id=NEW.directory_attempt_id AND source_id=NEW.directory_source_id) THEN
  RAISE EXCEPTION '출금 이전 확인 의도의 현재 운영자·원본·평가·목록 근거를 확인하세요';END IF;
 SELECT id INTO previous FROM bitcoin_withdrawal_transfer_intents WHERE withdrawal_id=NEW.withdrawal_id ORDER BY position DESC LIMIT 1;
 IF previous IS NOT NULL THEN
  SELECT r.status INTO prior_status FROM bitcoin_withdrawal_transfer_attempts a
   LEFT JOIN bitcoin_withdrawal_transfer_results r ON r.attempt_id=a.id WHERE a.intent_id=previous AND a.stage='ADDRESS' ORDER BY a.position DESC LIMIT 1;
  IF prior_status IS DISTINCT FROM 'INVALID' OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_transfer_attempts WHERE withdrawal_id=NEW.withdrawal_id AND stage='TRANSFER') THEN
   RAISE EXCEPTION '미해결 또는 개인정보 전송 이력이 있는 출금을 새 이전 ID로 우회할 수 없습니다';END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_transfer_intent_binding_check ON bitcoin_withdrawal_transfer_intents;
CREATE TRIGGER bitcoin_withdrawal_transfer_intent_binding_check BEFORE INSERT ON bitcoin_withdrawal_transfer_intents FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_transfer_intent_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_transfer_attempt_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original record;prior record;
BEGIN
 SELECT * INTO original FROM bitcoin_withdrawal_transfer_intents WHERE id=NEW.intent_id;
 IF NOT FOUND THEN RAISE EXCEPTION '출금 이전 확인 의도가 없습니다';END IF;
 PERFORM id FROM users WHERE id IN(original.user_id,NEW.actor_id) ORDER BY id FOR UPDATE;
 IF NEW.created_txid<>txid_current() OR NEW.started_at<clock_timestamp()-interval '10 seconds' OR NEW.started_at>clock_timestamp()
  OR original.user_id<>NEW.user_id OR original.withdrawal_id<>NEW.withdrawal_id OR original.requester_id<>NEW.actor_id
  OR original.expires_at<=clock_timestamp()
  OR NOT EXISTS(SELECT 1 FROM users WHERE id=NEW.actor_id AND enabled AND role='OPERATOR'
   AND operator_person_id=original.requester_person AND version=original.requester_version)
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_transfer_intents WHERE withdrawal_id=NEW.withdrawal_id AND position>original.position)
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_cancellations WHERE withdrawal_id=NEW.withdrawal_id)
  OR NOT EXISTS(SELECT 1 FROM codevasp_directory_attempts WHERE id=NEW.directory_attempt_id AND source_id=original.directory_source_id) THEN
  RAISE EXCEPTION '현재 출금 이전 확인 의도와 같은 고객·운영자·목록에서만 시작할 수 있습니다';END IF;
 SELECT a.started_at,r.status,r.recorded_at INTO prior FROM bitcoin_withdrawal_transfer_attempts a
  LEFT JOIN bitcoin_withdrawal_transfer_results r ON r.attempt_id=a.id WHERE a.intent_id=NEW.intent_id AND a.stage='TRANSFER' ORDER BY a.position DESC LIMIT 1;
 IF FOUND AND(prior.status IS DISTINCT FROM 'NOT_SENT' OR prior.recorded_at>clock_timestamp()-interval '30 seconds') THEN
  RAISE EXCEPTION '개인정보 전송의 미해결·확정 결과를 새 실행으로 우회할 수 없습니다';END IF;
 IF NEW.stage='TRANSFER' THEN
  IF NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_transfer_attempts a JOIN bitcoin_withdrawal_transfer_results r ON r.attempt_id=a.id
   WHERE a.intent_id=NEW.intent_id AND a.stage='ADDRESS' AND r.status='VALID' AND a.peer_public_key=NEW.peer_public_key
    AND r.recorded_at>=clock_timestamp()-interval '2 minutes'
    AND a.position=(SELECT max(p.position) FROM bitcoin_withdrawal_transfer_attempts p WHERE p.intent_id=NEW.intent_id AND p.stage='ADDRESS')) THEN
   RAISE EXCEPTION '신원 제공 전에 같은 현재 상대 키의 최근 주소 소속 확인이 필요합니다';END IF;
 ELSE
  SELECT a.started_at,r.recorded_at INTO prior FROM bitcoin_withdrawal_transfer_attempts a
   LEFT JOIN bitcoin_withdrawal_transfer_results r ON r.attempt_id=a.id WHERE a.intent_id=NEW.intent_id AND a.stage='ADDRESS' ORDER BY a.position DESC LIMIT 1;
  IF FOUND AND(coalesce(prior.recorded_at,prior.started_at)>clock_timestamp()-interval '30 seconds') THEN
   RAISE EXCEPTION '주소 소속 재확인의 영속 대기 시간이 지나지 않았습니다';END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_transfer_attempt_binding_check ON bitcoin_withdrawal_transfer_attempts;
CREATE TRIGGER bitcoin_withdrawal_transfer_attempt_binding_check BEFORE INSERT ON bitcoin_withdrawal_transfer_attempts FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_transfer_attempt_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_transfer_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE attempt record;
BEGIN
 SELECT * INTO attempt FROM bitcoin_withdrawal_transfer_attempts WHERE id=NEW.attempt_id;
 IF NOT FOUND OR NEW.created_txid<>txid_current() OR NEW.recorded_at<attempt.started_at OR NEW.recorded_at>clock_timestamp()
  OR attempt.stage='ADDRESS' AND NEW.status IN('VERIFIED','DENIED') OR attempt.stage='TRANSFER' AND NEW.status IN('VALID','INVALID') THEN
  RAISE EXCEPTION '이전 확인 시도와 같은 단계·시간의 관측 결과만 보존할 수 있습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_transfer_result_binding_check ON bitcoin_withdrawal_transfer_results;
CREATE TRIGGER bitcoin_withdrawal_transfer_result_binding_check BEFORE INSERT ON bitcoin_withdrawal_transfer_results FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_transfer_result_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_transfer_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE action_name text;target_name text;detail_value text;owner bigint;
BEGIN
 IF TG_TABLE_NAME='bitcoin_withdrawal_transfer_intents' THEN
  action_name:='BTC_WITHDRAWAL_TRANSFER_PREPARED';target_name:='bitcoin-withdrawal-transfer:'||NEW.id::text;detail_value:='MEANING:'||NEW.meaning_mac;owner:=NEW.requester_id;
 ELSIF TG_TABLE_NAME='bitcoin_withdrawal_transfer_attempts' THEN
  action_name:='BTC_WITHDRAWAL_TRANSFER_STARTED';target_name:='bitcoin-withdrawal-transfer-attempt:'||NEW.id::text;detail_value:='INTENT:'||NEW.intent_id::text||':STAGE:'||NEW.stage;owner:=NEW.actor_id;
 ELSE
  action_name:='BTC_WITHDRAWAL_TRANSFER_FINISHED';target_name:='bitcoin-withdrawal-transfer-attempt:'||NEW.attempt_id::text;detail_value:='STATUS:'||NEW.status||':REASON:'||coalesce(NEW.reason,'NONE');owner:=NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit WHERE actor_id IS NOT DISTINCT FROM owner AND action=action_name AND target=target_name AND outcome='SUCCESS' AND detail=detail_value) THEN
  RAISE EXCEPTION '출금 이전 확인의 원본·시도·결과는 필수 감사와 함께 확정해야 합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_transfer_intent_audit_check ON bitcoin_withdrawal_transfer_intents;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_transfer_intent_audit_check AFTER INSERT ON bitcoin_withdrawal_transfer_intents DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_transfer_audit();
DROP TRIGGER IF EXISTS bitcoin_withdrawal_transfer_attempt_audit_check ON bitcoin_withdrawal_transfer_attempts;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_transfer_attempt_audit_check AFTER INSERT ON bitcoin_withdrawal_transfer_attempts DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_transfer_audit();
DROP TRIGGER IF EXISTS bitcoin_withdrawal_transfer_result_audit_check ON bitcoin_withdrawal_transfer_results;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_transfer_result_audit_check AFTER INSERT ON bitcoin_withdrawal_transfer_results DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_transfer_audit();
