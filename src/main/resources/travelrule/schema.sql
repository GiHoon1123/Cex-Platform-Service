-- 공급사 접속 원점/등록 주체/등록 공개키가 다르면 같은 현재 자료로 사용하지 않는다.
CREATE TABLE IF NOT EXISTS codevasp_directory_sources (
 id varchar(64) PRIMARY KEY,api_origin varchar(1024) NOT NULL,entity_id varchar(128) NOT NULL,self_public_key varchar(44) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(api_origin,entity_id,self_public_key),
 CHECK(id=encode(sha256(convert_to(api_origin||E'\n'||entity_id||E'\n'||self_public_key,'UTF8')),'hex')),
 CHECK(position(E'\n' in api_origin)=0 AND entity_id ~ '^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$'),
 CHECK(length(self_public_key)=44 AND octet_length(decode(self_public_key,'base64'))=32 AND encode(decode(self_public_key,'base64'),'base64')=self_public_key)
);
CREATE TABLE IF NOT EXISTS codevasp_directory_attempts (
 id bigserial PRIMARY KEY,source_id varchar(64) NOT NULL REFERENCES codevasp_directory_sources(id),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(id,source_id)
);
CREATE TABLE IF NOT EXISTS codevasp_directory_heads (
 source_id varchar(64) PRIMARY KEY REFERENCES codevasp_directory_sources(id),attempt_id bigint NOT NULL,
 FOREIGN KEY(attempt_id,source_id) REFERENCES codevasp_directory_attempts(id,source_id)
);
-- 검증한 기술 목록 내용은 한 번만 보관한다. 각 조회의 새 시각/결과는 아래 별도 원본에 남긴다.
CREATE TABLE IF NOT EXISTS codevasp_directory_contents (
 id bigserial PRIMARY KEY,source_id varchar(64) NOT NULL REFERENCES codevasp_directory_sources(id),payload_hash varchar(64) NOT NULL,payload text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(source_id,payload_hash),UNIQUE(id,source_id),
 CHECK(octet_length(payload)<=4194304 AND payload_hash=encode(sha256(convert_to(payload,'UTF8')),'hex')),
 CHECK(jsonb_typeof(payload::jsonb)='array' AND jsonb_array_length(payload::jsonb)<=5000)
);
CREATE TABLE IF NOT EXISTS codevasp_directory_results (
 attempt_id bigint PRIMARY KEY,source_id varchar(64) NOT NULL,status varchar(16) NOT NULL CHECK(status IN ('SUCCESS','FAILED')),
 failure_code varchar(32),content_id bigint,client_started_at timestamptz,client_finished_at timestamptz,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(attempt_id,source_id) REFERENCES codevasp_directory_attempts(id,source_id),FOREIGN KEY(content_id,source_id) REFERENCES codevasp_directory_contents(id,source_id),
 CHECK((status='SUCCESS' AND failure_code IS NULL AND content_id IS NOT NULL AND client_started_at IS NOT NULL AND client_finished_at IS NOT NULL AND client_started_at<=client_finished_at)
  OR(status='FAILED' AND failure_code IN ('PROVIDER_UNAVAILABLE','INVALID_SOURCE','INTERNAL_FAILURE') AND failure_code IS NOT NULL AND content_id IS NULL AND client_started_at IS NULL AND client_finished_at IS NULL))
);
CREATE OR REPLACE FUNCTION codevasp_directory_head_forward() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND (NEW.source_id<>OLD.source_id OR NEW.attempt_id<=OLD.attempt_id) THEN RAISE EXCEPTION '사업자 목록의 현재 조회를 과거로 되돌릴 수 없습니다'; END IF;
 IF NOT EXISTS(SELECT 1 FROM codevasp_directory_attempts a WHERE a.id=NEW.attempt_id AND a.source_id=NEW.source_id AND a.created_txid=txid_current()) THEN
  RAISE EXCEPTION '사업자 목록 현재 위치는 새 조회 시작과 같은 트랜잭션에서 확정해야 합니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_head_forward ON codevasp_directory_heads;
CREATE TRIGGER codevasp_head_forward BEFORE INSERT OR UPDATE ON codevasp_directory_heads FOR EACH ROW EXECUTE FUNCTION codevasp_directory_head_forward();
CREATE OR REPLACE FUNCTION codevasp_directory_result_time() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='SUCCESS' AND NOT EXISTS(SELECT 1 FROM codevasp_directory_attempts a WHERE a.id=NEW.attempt_id AND a.source_id=NEW.source_id
   AND NEW.client_started_at>=a.started_at-interval '10 seconds' AND NEW.client_finished_at<=clock_timestamp()+interval '10 seconds') THEN
  RAISE EXCEPTION '사업자 목록의 읽기 시각이 조회 원본과 다릅니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_result_time ON codevasp_directory_results;
CREATE TRIGGER codevasp_result_time BEFORE INSERT ON codevasp_directory_results FOR EACH ROW EXECUTE FUNCTION codevasp_directory_result_time();
DROP TRIGGER IF EXISTS codevasp_source_immutable ON codevasp_directory_sources;
CREATE TRIGGER codevasp_source_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_directory_sources FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS codevasp_attempt_immutable ON codevasp_directory_attempts;
CREATE TRIGGER codevasp_attempt_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_directory_attempts FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS codevasp_content_immutable ON codevasp_directory_contents;
CREATE TRIGGER codevasp_content_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_directory_contents FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS codevasp_result_immutable ON codevasp_directory_results;
CREATE TRIGGER codevasp_result_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_directory_results FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS codevasp_head_no_delete ON codevasp_directory_heads;
CREATE TRIGGER codevasp_head_no_delete BEFORE DELETE OR TRUNCATE ON codevasp_directory_heads FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 요청 본문을 중복 복제하지 않고 불변 입금/주소 원본과 접속 주체에 결합한다. 외부 접수 전에 이 ID를 확정한다.
CREATE TABLE IF NOT EXISTS codevasp_deposit_searches (
 id uuid PRIMARY KEY,receipt_id uuid NOT NULL REFERENCES bitcoin_deposit_receipts(id),
 source_id varchar(64) NOT NULL REFERENCES codevasp_directory_sources(id),directory_attempt_id bigint NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(receipt_id,source_id),
 FOREIGN KEY(directory_attempt_id,source_id) REFERENCES codevasp_directory_attempts(id,source_id)
);
CREATE OR REPLACE FUNCTION codevasp_deposit_search_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 현재 위치를 고정하고 동일 주체의 새 성공 목록을 요구한다. 나중 승인에 이 과거 목록을 그대로 쓰면 안 된다.
 PERFORM h.source_id FROM codevasp_directory_heads h
 JOIN codevasp_directory_attempts a ON a.id=h.attempt_id AND a.source_id=h.source_id
 JOIN codevasp_directory_results r ON r.attempt_id=a.id AND r.source_id=a.source_id
 WHERE h.source_id=NEW.source_id AND h.attempt_id=NEW.directory_attempt_id AND r.status='SUCCESS' AND r.recorded_at<=clock_timestamp()
  AND a.started_at BETWEEN clock_timestamp()-interval '5 minutes' AND clock_timestamp()
 FOR SHARE OF h;
 IF NOT FOUND THEN RAISE EXCEPTION '거래 검색 예약에 현재 성공한 사업자 목록이 필요합니다'; END IF;
 IF NOT EXISTS(SELECT 1 FROM bitcoin_deposit_receipts b
  JOIN bitcoin_deposit_addresses d ON d.id=b.address_id AND d.user_id=b.user_id AND d.network=b.network
  JOIN bitcoin_network_identity n ON n.singleton=1 AND n.network=b.network
  JOIN codevasp_directory_sources s ON s.id=NEW.source_id
  WHERE b.id=NEW.receipt_id AND b.asset='BTC'
   AND (b.network='bitcoin-mainnet' OR s.api_origin ~ '^https?://(127[.]0[.]0[.]1|\[::1\])(:[0-9]+)?/$')) THEN
  RAISE EXCEPTION '거래 검색의 입금 원본 또는 시험망 접속 경계가 다릅니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_search_source ON codevasp_deposit_searches;
CREATE TRIGGER codevasp_search_source BEFORE INSERT ON codevasp_deposit_searches FOR EACH ROW EXECUTE FUNCTION codevasp_deposit_search_source();
DROP TRIGGER IF EXISTS codevasp_search_immutable ON codevasp_deposit_searches;
CREATE TRIGGER codevasp_search_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_deposit_searches FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 접수의 시작을 잃지 않는다. 게이트웨이 미전송을 증명한 경우만 재접수하며 불명·중단은 같은 ID의 결과 조회다.
CREATE TABLE IF NOT EXISTS codevasp_search_attempts (
 id bigserial PRIMARY KEY,search_id uuid NOT NULL REFERENCES codevasp_deposit_searches(id),
 phase varchar(8) NOT NULL CHECK(phase IN ('SUBMIT','POLL')),started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(id,search_id)
);
CREATE TABLE IF NOT EXISTS codevasp_search_heads (
 search_id uuid PRIMARY KEY REFERENCES codevasp_deposit_searches(id),attempt_id bigint NOT NULL,
 FOREIGN KEY(attempt_id,search_id) REFERENCES codevasp_search_attempts(id,search_id)
);
CREATE TABLE IF NOT EXISTS codevasp_search_results (
 attempt_id bigint PRIMARY KEY REFERENCES codevasp_search_attempts(id),status varchar(32) NOT NULL,
 failure_code varchar(32),payload text,payload_hash varchar(64),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(status IN ('SUBMIT_ACKNOWLEDGED','SUBMIT_UNKNOWN','SUBMIT_NOT_SENT','FOUND','NOT_FOUND','PROCESSING','UNKNOWN_REQUEST','AMBIGUOUS','POLL_FAILED')),
 CHECK((status IN ('SUBMIT_UNKNOWN','SUBMIT_NOT_SENT','POLL_FAILED') AND failure_code IS NOT NULL AND failure_code IN ('PROVIDER_UNAVAILABLE','INVALID_SOURCE','INTERNAL_FAILURE') AND payload IS NULL AND payload_hash IS NULL)
  OR(status='SUBMIT_ACKNOWLEDGED' AND failure_code IS NULL AND payload IS NULL AND payload_hash IS NULL)
  OR(status IN ('FOUND','NOT_FOUND','PROCESSING','UNKNOWN_REQUEST','AMBIGUOUS') AND failure_code IS NULL AND payload IS NOT NULL AND payload_hash IS NOT NULL
     AND octet_length(payload)<=4194304 AND payload_hash=encode(sha256(convert_to(payload,'UTF8')),'hex')
     AND jsonb_typeof(payload::jsonb)='object' AND payload::jsonb->>'status'=status))
);
CREATE OR REPLACE FUNCTION codevasp_search_attempt_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE latest record; prior boolean;
BEGIN
 IF NEW.started_at>clock_timestamp() THEN RAISE EXCEPTION '검색 시작 시각이 미래입니다'; END IF;
 -- 불변 검색 원본의 행을 잠깐 고정해 직접 SQL의 동시 최초 접수도 직렬화한다.
 PERFORM id FROM codevasp_deposit_searches WHERE id=NEW.search_id FOR UPDATE;
 SELECT EXISTS(SELECT 1 FROM codevasp_search_attempts WHERE search_id=NEW.search_id) INTO prior;
 SELECT a.started_at,r.recorded_at,r.status INTO latest FROM codevasp_search_heads h
  JOIN codevasp_search_attempts a ON a.id=h.attempt_id LEFT JOIN codevasp_search_results r ON r.attempt_id=a.id WHERE h.search_id=NEW.search_id;
 IF (NOT prior AND NEW.phase<>'SUBMIT') OR(prior AND NEW.phase<>CASE WHEN latest.status='SUBMIT_NOT_SENT' THEN 'SUBMIT' ELSE 'POLL' END) THEN
  RAISE EXCEPTION '미전송이 확인된 경우만 재접수하고 이후에는 결과를 조회합니다';
 END IF;
 IF latest.started_at IS NOT NULL AND ((latest.recorded_at IS NULL AND latest.started_at>clock_timestamp()-interval '2 minutes')
   OR(latest.recorded_at IS NOT NULL AND latest.recorded_at>clock_timestamp()-interval '30 seconds')) THEN
  RAISE EXCEPTION '진행 중이거나 재조회 간격이 지나지 않은 검색입니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_search_attempt_guard ON codevasp_search_attempts;
CREATE TRIGGER codevasp_search_attempt_guard BEFORE INSERT ON codevasp_search_attempts FOR EACH ROW EXECUTE FUNCTION codevasp_search_attempt_guard();
CREATE OR REPLACE FUNCTION codevasp_search_head_forward() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND (NEW.search_id<>OLD.search_id OR NEW.attempt_id<=OLD.attempt_id) THEN RAISE EXCEPTION '검색의 최신 시도를 되돌릴 수 없습니다'; END IF;
 IF NOT EXISTS(SELECT 1 FROM codevasp_search_attempts a WHERE a.id=NEW.attempt_id AND a.search_id=NEW.search_id AND a.created_txid=txid_current()) THEN
  RAISE EXCEPTION '검색 현재 위치는 시작 원본과 같은 트랜잭션에서 작성해야 합니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_search_head_forward ON codevasp_search_heads;
CREATE TRIGGER codevasp_search_head_forward BEFORE INSERT OR UPDATE ON codevasp_search_heads FOR EACH ROW EXECUTE FUNCTION codevasp_search_head_forward();
CREATE OR REPLACE FUNCTION codevasp_search_result_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original record;
BEGIN
 SELECT phase,search_id,started_at INTO original FROM codevasp_search_attempts WHERE id=NEW.attempt_id;
 IF NOT FOUND OR NEW.recorded_at>clock_timestamp() OR NEW.recorded_at<original.started_at
  OR(original.phase='SUBMIT' AND NEW.status NOT IN ('SUBMIT_ACKNOWLEDGED','SUBMIT_UNKNOWN','SUBMIT_NOT_SENT'))
  OR(original.phase='POLL' AND NEW.status IN ('SUBMIT_ACKNOWLEDGED','SUBMIT_UNKNOWN','SUBMIT_NOT_SENT')) THEN
  RAISE EXCEPTION '검색 결과의 실행 단계 또는 기록 시각이 다릅니다';
 END IF;
 IF NEW.payload IS NOT NULL AND (NEW.payload::jsonb->>'requestId' IS NULL OR NEW.payload::jsonb->>'requestId'<>original.search_id::text OR NEW.payload::jsonb->>'status' IS NULL) THEN
  RAISE EXCEPTION '검색 결과의 요청 식별자 또는 상태가 다릅니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_search_result_guard ON codevasp_search_results;
CREATE TRIGGER codevasp_search_result_guard BEFORE INSERT ON codevasp_search_results FOR EACH ROW EXECUTE FUNCTION codevasp_search_result_guard();
DROP TRIGGER IF EXISTS codevasp_search_attempt_immutable ON codevasp_search_attempts;
CREATE TRIGGER codevasp_search_attempt_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_search_attempts FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS codevasp_search_result_immutable ON codevasp_search_results;
CREATE TRIGGER codevasp_search_result_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_search_results FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS codevasp_search_head_no_delete ON codevasp_search_heads;
CREATE TRIGGER codevasp_search_head_no_delete BEFORE DELETE OR TRUNCATE ON codevasp_search_heads FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 실제 자동 후보 조회의 접속 주체 필터와 검색별 시작 이력 조회를 위한 인덱스다.
CREATE INDEX IF NOT EXISTS codevasp_searches_source_created ON codevasp_deposit_searches(source_id,created_at,id);
CREATE INDEX IF NOT EXISTS codevasp_search_attempts_search ON codevasp_search_attempts(search_id,id);

-- 개인정보 제공 의도는 고객 원문을 평문으로 보관하지 않는다. 과거 의도는 현재 전송 허가가 아니며 수정/삭제하지 않는다.
CREATE TABLE IF NOT EXISTS codevasp_transfer_intents (
 id uuid PRIMARY KEY,search_id uuid NOT NULL UNIQUE REFERENCES codevasp_deposit_searches(id),
 user_id bigint NOT NULL REFERENCES users(id),receipt_id uuid NOT NULL REFERENCES bitcoin_deposit_receipts(id),
 search_attempt_id bigint NOT NULL REFERENCES codevasp_search_attempts(id),directory_attempt_id bigint NOT NULL REFERENCES codevasp_directory_attempts(id),
 decision_id bigint NOT NULL REFERENCES bitcoin_deposit_chain_decisions(id),observation_id bigint NOT NULL REFERENCES bitcoin_transaction_observations(id),
 declaration_id uuid NOT NULL REFERENCES cdd_declarations(id),assessment_id uuid NOT NULL REFERENCES cdd_assessments(id),
 policy_hash varchar(64) NOT NULL CHECK(policy_hash ~ '^[a-f0-9]{64}$'),peer_id varchar(128) NOT NULL,
 peer_public_key varchar(44) NOT NULL CHECK(length(peer_public_key)=44 AND octet_length(decode(peer_public_key,'base64'))=32),
 key_id varchar(64) NOT NULL CHECK(key_id ~ '^[a-f0-9]{64}$'),ciphertext bytea NOT NULL CHECK(octet_length(ciphertext) BETWEEN 28 AND 65536),
 content_mac varchar(64) NOT NULL CHECK(content_mac ~ '^[a-f0-9]{64}$'),created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE OR REPLACE FUNCTION codevasp_transfer_intent_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_at>clock_timestamp() OR NOT EXISTS(
  SELECT 1 FROM codevasp_deposit_searches s JOIN bitcoin_deposit_receipts b ON b.id=s.receipt_id
   JOIN bitcoin_pending_deposits p ON p.receipt_id=b.id
   JOIN codevasp_directory_sources src ON src.id=s.source_id
   JOIN codevasp_search_heads sh ON sh.search_id=s.id AND sh.attempt_id=NEW.search_attempt_id
   JOIN codevasp_search_results sr ON sr.attempt_id=sh.attempt_id AND sr.status='FOUND'
   JOIN codevasp_directory_heads dh ON dh.source_id=s.source_id AND dh.attempt_id=NEW.directory_attempt_id
   JOIN codevasp_directory_attempts da ON da.id=dh.attempt_id
   JOIN codevasp_directory_results dr ON dr.attempt_id=da.id AND dr.status='SUCCESS'
   JOIN bitcoin_deposit_chain_heads ch ON ch.receipt_id=b.id AND ch.decision_id=NEW.decision_id
   JOIN bitcoin_deposit_chain_decisions cd ON cd.id=ch.decision_id AND cd.observation_id=NEW.observation_id
   JOIN bitcoin_observation_heads oh ON oh.observation_id=cd.observation_id
   JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id AND o.coinbase=false
   JOIN bitcoin_observation_wallet_debits wd ON wd.observation_id=o.id AND wd.wallet_debit=false
   JOIN cdd_profiles cp ON cp.user_id=b.user_id AND cp.declaration_id=NEW.declaration_id AND cp.assessment_id=NEW.assessment_id
   JOIN cdd_declarations decl ON decl.id=cp.declaration_id AND decl.user_id=cp.user_id AND decl.key_id=NEW.key_id
   JOIN cdd_assessments ca ON ca.id=cp.assessment_id AND ca.declaration_id=decl.id AND ca.user_id=cp.user_id AND ca.key_id=NEW.key_id
   WHERE s.id=NEW.search_id AND b.id=NEW.receipt_id AND b.user_id=NEW.user_id
    AND (b.network='bitcoin-mainnet' OR src.api_origin ~ '^https?://(127[.]0[.]0[.]1|\[::1\])(:[0-9]+)?/$')
    AND sr.payload::jsonb->>'entityId'=NEW.peer_id
    AND sr.recorded_at<=clock_timestamp() AND sr.recorded_at>=clock_timestamp()-interval '15 minutes'
    AND da.started_at<=clock_timestamp() AND da.started_at>=clock_timestamp()-interval '5 minutes'
    AND cd.reason='CHAIN_READY' AND cd.policy_id='BTC_CHAIN_V1'
    AND o.read_started_at<=clock_timestamp() AND o.read_started_at>=clock_timestamp()-interval '2 minutes'
 ) THEN RAISE EXCEPTION '정보 제공 의도가 현재 입금·검색·수취인 원본과 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_transfer_intent_source_check ON codevasp_transfer_intents;
CREATE TRIGGER codevasp_transfer_intent_source_check BEFORE INSERT ON codevasp_transfer_intents FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_intent_source();
DROP TRIGGER IF EXISTS codevasp_transfer_intent_immutable ON codevasp_transfer_intents;
CREATE TRIGGER codevasp_transfer_intent_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_transfer_intents FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 제공 의도의 의미는 보존하고 실제 전송마다 현재 근거를 암호화해 새 시도로 남긴다.
CREATE TABLE IF NOT EXISTS codevasp_transfer_attempts (
 id uuid PRIMARY KEY,intent_id uuid NOT NULL REFERENCES codevasp_transfer_intents(id),sequence bigserial UNIQUE,
 search_id uuid NOT NULL REFERENCES codevasp_deposit_searches(id),user_id bigint NOT NULL REFERENCES users(id),receipt_id uuid NOT NULL REFERENCES bitcoin_deposit_receipts(id),
 search_attempt_id bigint NOT NULL REFERENCES codevasp_search_attempts(id),directory_attempt_id bigint NOT NULL REFERENCES codevasp_directory_attempts(id),
 decision_id bigint NOT NULL REFERENCES bitcoin_deposit_chain_decisions(id),observation_id bigint NOT NULL REFERENCES bitcoin_transaction_observations(id),
 declaration_id uuid NOT NULL REFERENCES cdd_declarations(id),assessment_id uuid NOT NULL REFERENCES cdd_assessments(id),
 policy_hash varchar(64) NOT NULL CHECK(policy_hash ~ '^[a-f0-9]{64}$'),peer_id varchar(128) NOT NULL,
 peer_public_key varchar(44) NOT NULL CHECK(length(peer_public_key)=44 AND octet_length(decode(peer_public_key,'base64'))=32),
 key_id varchar(64) NOT NULL CHECK(key_id ~ '^[a-f0-9]{64}$'),ciphertext bytea NOT NULL CHECK(octet_length(ciphertext) BETWEEN 28 AND 65536),
 content_mac varchar(64) NOT NULL CHECK(content_mac ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(id,intent_id)
);
CREATE INDEX IF NOT EXISTS codevasp_transfer_attempt_receipt ON codevasp_transfer_attempts(receipt_id,sequence DESC);
CREATE TABLE IF NOT EXISTS codevasp_transfer_heads (
 intent_id uuid PRIMARY KEY REFERENCES codevasp_transfer_intents(id),attempt_id uuid NOT NULL UNIQUE,
 FOREIGN KEY(attempt_id,intent_id) REFERENCES codevasp_transfer_attempts(id,intent_id)
);
CREATE TABLE IF NOT EXISTS codevasp_transfer_results (
 attempt_id uuid PRIMARY KEY REFERENCES codevasp_transfer_attempts(id),status varchar(24) NOT NULL,
 reason varchar(32),key_id varchar(64),ciphertext bytea,content_mac varchar(64),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((status='DATA_RECEIVED' AND reason IS NULL AND ciphertext IS NOT NULL)
  OR(status='REMOTE_ERROR' AND reason IS NOT NULL AND reason IN ('NOT_FOUND_TXID','LACK_OF_INFORMATION','UNKNOWN','UNKNOWN_TRANSFER_ID') AND ciphertext IS NOT NULL)
  OR(status IN ('NOT_SENT','OUTCOME_UNKNOWN') AND reason IS NOT NULL AND reason IN ('PROVIDER_UNAVAILABLE','INVALID_SOURCE') AND ciphertext IS NULL)),
 CHECK((ciphertext IS NULL AND key_id IS NULL AND content_mac IS NULL)
  OR(ciphertext IS NOT NULL AND octet_length(ciphertext) BETWEEN 28 AND 65536 AND key_id IS NOT NULL AND key_id ~ '^[a-f0-9]{64}$' AND content_mac IS NOT NULL AND content_mac ~ '^[a-f0-9]{64}$'))
);
CREATE OR REPLACE FUNCTION codevasp_transfer_attempt_admission() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prior record;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('code-private-transfer-receipt-v1:'||NEW.receipt_id::text,0));
 SELECT a.id,r.status,r.recorded_at INTO prior FROM codevasp_transfer_attempts a LEFT JOIN codevasp_transfer_results r ON r.attempt_id=a.id
  WHERE a.receipt_id=NEW.receipt_id ORDER BY a.sequence DESC LIMIT 1;
 IF FOUND AND (prior.status IS DISTINCT FROM 'NOT_SENT' OR prior.recorded_at>clock_timestamp()-interval '30 seconds') THEN
  RAISE EXCEPTION '미전송이 확인되지 않은 정보 교환을 반복할 수 없습니다';
 END IF;
 IF NEW.created_txid<>txid_current() OR NOT EXISTS(SELECT 1 FROM codevasp_transfer_intents i
  WHERE i.id=NEW.intent_id AND i.search_id=NEW.search_id AND i.user_id=NEW.user_id AND i.receipt_id=NEW.receipt_id AND i.peer_id=NEW.peer_id)
 THEN RAISE EXCEPTION '정보 교환 시작이 원래 의도와 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_transfer_attempt_admission_check ON codevasp_transfer_attempts;
CREATE TRIGGER codevasp_transfer_attempt_admission_check BEFORE INSERT ON codevasp_transfer_attempts FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_attempt_admission();
DROP TRIGGER IF EXISTS codevasp_transfer_attempt_source_check ON codevasp_transfer_attempts;
CREATE TRIGGER codevasp_transfer_attempt_source_check BEFORE INSERT ON codevasp_transfer_attempts FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_intent_source();
CREATE OR REPLACE FUNCTION codevasp_transfer_head_forward() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE fresh record;previous bigint;
BEGIN
 SELECT sequence,created_txid INTO fresh FROM codevasp_transfer_attempts WHERE id=NEW.attempt_id AND intent_id=NEW.intent_id;
 IF NOT FOUND OR fresh.created_txid<>txid_current() THEN RAISE EXCEPTION '같은 트랜잭션의 정보 교환 시작만 현재 위치가 될 수 있습니다';END IF;
 IF TG_OP='UPDATE' THEN
  SELECT sequence INTO previous FROM codevasp_transfer_attempts WHERE id=OLD.attempt_id;
  IF NEW.intent_id<>OLD.intent_id OR fresh.sequence<=previous THEN RAISE EXCEPTION '정보 교환 위치를 되돌릴 수 없습니다';END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_transfer_head_forward_check ON codevasp_transfer_heads;
CREATE TRIGGER codevasp_transfer_head_forward_check BEFORE INSERT OR UPDATE ON codevasp_transfer_heads FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_head_forward();
CREATE OR REPLACE FUNCTION codevasp_transfer_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original record;
BEGIN
 SELECT * INTO original FROM codevasp_transfer_attempts WHERE id=NEW.attempt_id;
 IF NOT FOUND OR NEW.recorded_at<original.created_at OR NEW.recorded_at>clock_timestamp() OR(NEW.key_id IS NOT NULL AND NEW.key_id<>original.key_id)
 THEN RAISE EXCEPTION '정보 교환 결과의 시각/암호키가 시작 원본과 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_transfer_result_binding_check ON codevasp_transfer_results;
CREATE TRIGGER codevasp_transfer_result_binding_check BEFORE INSERT ON codevasp_transfer_results FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_result_binding();
DROP TRIGGER IF EXISTS codevasp_transfer_attempt_immutable ON codevasp_transfer_attempts;
CREATE TRIGGER codevasp_transfer_attempt_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_transfer_attempts FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS codevasp_transfer_result_immutable ON codevasp_transfer_results;
CREATE TRIGGER codevasp_transfer_result_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_transfer_results FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS codevasp_transfer_head_no_delete ON codevasp_transfer_heads;
CREATE TRIGGER codevasp_transfer_head_no_delete BEFORE DELETE OR TRUNCATE ON codevasp_transfer_heads FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 작업 일정은 갱신 가능한 운영 상태다. 개인정보/금융 증거는 이 표가 아니라 불변 의도·실행·감사에 보관한다.
CREATE TABLE IF NOT EXISTS codevasp_transfer_jobs (
 search_id uuid PRIMARY KEY REFERENCES codevasp_deposit_searches(id),intent_id uuid REFERENCES codevasp_transfer_intents(id),
 state varchar(24) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','RETRY','HELD','DONE','REVIEW_REQUIRED')),
 last_reason varchar(32) NOT NULL DEFAULT 'NEW' CHECK(last_reason IN ('NEW','NOT_SENT','CURRENT_SOURCE_UNAVAILABLE','SOURCE_MISSING','CURRENT_CONDITIONS_NOT_MET','LOCAL_FAILURE','DATA_RECEIVED','RUNNING','OUTCOME_UNKNOWN','REMOTE_ERROR','PRIOR_TRANSFER')),
 next_attempt_at timestamptz DEFAULT clock_timestamp(),lease_id uuid,lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((lease_id IS NULL)=(lease_until IS NULL)),CHECK((state IN ('DONE','REVIEW_REQUIRED'))=(next_attempt_at IS NULL)),
 CHECK(state NOT IN ('DONE','REVIEW_REQUIRED') OR lease_id IS NULL)
);
CREATE INDEX IF NOT EXISTS codevasp_transfer_job_due ON codevasp_transfer_jobs(next_attempt_at,search_id) WHERE state IN ('PENDING','RETRY','HELD');
CREATE OR REPLACE FUNCTION codevasp_transfer_job_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND(NEW.search_id<>OLD.search_id OR NEW.created_at<>OLD.created_at) THEN
  RAISE EXCEPTION '정보 교환 작업의 원본을 바꿀 수 없습니다';END IF;
 IF NEW.intent_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM codevasp_transfer_intents i WHERE i.id=NEW.intent_id AND i.search_id=NEW.search_id) THEN
  RAISE EXCEPTION '정보 교환 작업과 제공 의도의 검색 원본이 다릅니다';END IF;
 IF NEW.state='DONE' AND NOT EXISTS(SELECT 1 FROM codevasp_transfer_heads h JOIN codevasp_transfer_results r ON r.attempt_id=h.attempt_id
  WHERE h.intent_id=NEW.intent_id AND r.status='DATA_RECEIVED') THEN
  RAISE EXCEPTION '수신 원본 없이 정보 교환 작업을 완료할 수 없습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_transfer_job_binding_check ON codevasp_transfer_jobs;
CREATE TRIGGER codevasp_transfer_job_binding_check BEFORE INSERT OR UPDATE ON codevasp_transfer_jobs FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_job_binding();
DROP TRIGGER IF EXISTS codevasp_transfer_job_no_delete ON codevasp_transfer_jobs;
CREATE TRIGGER codevasp_transfer_job_no_delete BEFORE DELETE OR TRUNCATE ON codevasp_transfer_jobs FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
