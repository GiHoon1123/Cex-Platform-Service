-- 미서명 후보와 입력 배정은 서명·지급 권한과 구분한다. 현재 단계에서는 자동 해제 경로를 열지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_payout_preparations (
 id uuid PRIMARY KEY,position bigserial UNIQUE,review_id uuid NOT NULL REFERENCES bitcoin_withdrawal_payout_decisions(review_id),
 withdrawal_id uuid NOT NULL REFERENCES bitcoin_withdrawals(id),user_id bigint NOT NULL REFERENCES users(id),
 review_hash varchar(64) NOT NULL,scope_hash varchar(64) NOT NULL,started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE INDEX IF NOT EXISTS bitcoin_payout_preparation_latest ON bitcoin_payout_preparations(review_id,position DESC);
CREATE TABLE IF NOT EXISTS bitcoin_payout_candidates (
 id uuid PRIMARY KEY,review_id uuid NOT NULL UNIQUE REFERENCES bitcoin_withdrawal_payout_decisions(review_id),withdrawal_id uuid NOT NULL REFERENCES bitcoin_withdrawals(id),
 preparation_id uuid NOT NULL UNIQUE REFERENCES bitcoin_payout_preparations(id),user_id bigint NOT NULL REFERENCES users(id),
 network varchar(32) NOT NULL,wallet varchar(64) NOT NULL,policy varchar(40) NOT NULL CHECK(policy='BTC_WATCH_ONLY_PSBT_V1'),
 amount numeric(16,8) NOT NULL CHECK(amount>0),native_fee numeric(16,8) NOT NULL CHECK(native_fee>0 AND native_fee<=0.0001),
 input_count integer NOT NULL CHECK(input_count BETWEEN 1 AND 100),psbt_hash varchar(64) NOT NULL,unsigned_txid varchar(64) NOT NULL,
 key_id varchar(64) NOT NULL,ciphertext bytea NOT NULL,payload_hash varchar(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 UNIQUE(network,unsigned_txid),CHECK(psbt_hash ~ '^[0-9a-f]{64}$' AND unsigned_txid ~ '^[0-9a-f]{64}$' AND key_id ~ '^[0-9a-f]{64}$' AND payload_hash ~ '^[0-9a-f]{64}$'),
 CHECK(octet_length(ciphertext) BETWEEN 28 AND 1048604)
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_preparation_results (
 preparation_id uuid PRIMARY KEY REFERENCES bitcoin_payout_preparations(id),
 status varchar(24) NOT NULL CHECK(status IN('SAVED','NODE_UNAVAILABLE','BASIS_CHANGED','INPUT_RESERVED')),
 candidate_id uuid REFERENCES bitcoin_payout_candidates(id),observed_psbt_hash varchar(64),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK((status='SAVED')=(candidate_id IS NOT NULL)),CHECK(observed_psbt_hash IS NULL OR observed_psbt_hash ~ '^[0-9a-f]{64}$')
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_candidate_inputs (
 candidate_id uuid NOT NULL REFERENCES bitcoin_payout_candidates(id),txid varchar(64) NOT NULL,vout integer NOT NULL CHECK(vout>=0),
 amount numeric(16,8) NOT NULL CHECK(amount>0),script_pub_key varchar(44) NOT NULL CHECK(script_pub_key ~ '^0014[0-9a-f]{40}$'),
 created_txid bigint NOT NULL DEFAULT txid_current(),PRIMARY KEY(candidate_id,txid,vout),CHECK(txid ~ '^[0-9a-f]{64}$')
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_active_candidates (
 withdrawal_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawals(id),candidate_id uuid NOT NULL UNIQUE REFERENCES bitcoin_payout_candidates(id)
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_input_reservations (
 network varchar(32) NOT NULL,txid varchar(64) NOT NULL,vout integer NOT NULL,candidate_id uuid NOT NULL,
 -- 같은 UTXO를 다른 지갑 이름으로 이중 배정하지 못한다. 지갑은 후보의 귀속 정보이고 UTXO의 식별자가 아니다.
 PRIMARY KEY(network,txid,vout),FOREIGN KEY(candidate_id,txid,vout) REFERENCES bitcoin_payout_candidate_inputs(candidate_id,txid,vout)
);
CREATE OR REPLACE FUNCTION bitcoin_payout_preparation_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;prior record;
BEGIN
 SELECT * INTO r FROM bitcoin_withdrawal_payout_reviews WHERE id=NEW.review_id;
 PERFORM id FROM users WHERE id IN(r.user_id,r.requester_id,r.checker_id) ORDER BY id FOR UPDATE;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.started_at>clock_timestamp() OR NEW.withdrawal_id<>r.withdrawal_id OR NEW.user_id<>r.user_id
  OR NEW.review_hash<>r.payload_hash OR NEW.scope_hash<>r.scope_hash OR NOT bitcoin_payout_source(r.id),true)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_decisions WHERE review_id=r.id AND approved)
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=r.withdrawal_id) THEN RAISE EXCEPTION '현재 승인된 같은 출금 범위만 준비할 수 있습니다';END IF;
 SELECT a.started_at,result.recorded_at INTO prior FROM bitcoin_payout_preparations a LEFT JOIN bitcoin_payout_preparation_results result ON result.preparation_id=a.id WHERE a.review_id=NEW.review_id ORDER BY a.position DESC LIMIT 1;
 IF FOUND AND coalesce(prior.recorded_at,prior.started_at)>clock_timestamp()-interval '30 seconds' THEN RAISE EXCEPTION '미서명 거래 재준비의 영속 대기 시간이 지나지 않았습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_preparation_binding_check ON bitcoin_payout_preparations;
CREATE TRIGGER bitcoin_payout_preparation_binding_check BEFORE INSERT ON bitcoin_payout_preparations FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_preparation_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_candidate_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;a record;w record;
BEGIN
 SELECT * INTO r FROM bitcoin_withdrawal_payout_reviews WHERE id=NEW.review_id;SELECT * INTO a FROM bitcoin_payout_preparations WHERE id=NEW.preparation_id;
 SELECT * INTO w FROM bitcoin_withdrawals WHERE id=NEW.withdrawal_id;
 PERFORM id FROM users WHERE id IN(r.user_id,r.requester_id,r.checker_id) ORDER BY id FOR UPDATE;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.created_at>clock_timestamp() OR a.review_id<>r.id OR a.withdrawal_id<>r.withdrawal_id OR a.user_id<>r.user_id
  OR NEW.withdrawal_id<>r.withdrawal_id OR NEW.user_id<>r.user_id OR a.review_hash<>r.payload_hash OR a.scope_hash<>r.scope_hash
  OR NEW.amount<>w.amount OR NEW.network<>w.network OR NOT bitcoin_payout_source(r.id),true)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_decisions WHERE review_id=r.id AND approved)
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=r.withdrawal_id) THEN RAISE EXCEPTION '미서명 후보와 현재 승인·고정 지급·준비 시도가 다릅니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_candidate_binding_check ON bitcoin_payout_candidates;
CREATE TRIGGER bitcoin_payout_candidate_binding_check BEFORE INSERT ON bitcoin_payout_candidates FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_candidate_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_candidate_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bitcoin_payout_active_candidates WHERE candidate_id=NEW.id AND withdrawal_id=NEW.withdrawal_id)
  OR(SELECT count(*) FROM bitcoin_payout_candidate_inputs WHERE candidate_id=NEW.id)<>NEW.input_count
  OR(SELECT count(*) FROM bitcoin_payout_input_reservations WHERE candidate_id=NEW.id)<>NEW.input_count
  OR NOT EXISTS(SELECT 1 FROM bitcoin_payout_preparation_results WHERE preparation_id=NEW.preparation_id AND candidate_id=NEW.id AND status='SAVED' AND created_txid=txid_current())
  OR NOT EXISTS(SELECT 1 FROM security_audit WHERE actor_id IS NULL AND action='BTC_PAYOUT_CANDIDATE_SAVED' AND target='bitcoin-payout-candidate:'||NEW.id AND outcome='SUCCESS' AND detail=NEW.payload_hash) THEN
  RAISE EXCEPTION '후보·입력 전체 배정·준비 결과·감사를 같은 커밋에 보존해야 합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_candidate_complete_check ON bitcoin_payout_candidates;
CREATE CONSTRAINT TRIGGER bitcoin_payout_candidate_complete_check AFTER INSERT ON bitcoin_payout_candidates DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_candidate_complete();

-- 과거 기록을 지우거나 현재 배정만 제거해 같은 입력을 재사용하지 못한다. 명시적 미실행 해제는 후속 업무다.
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_preparations','bitcoin_payout_preparation_results','bitcoin_payout_candidates','bitcoin_payout_candidate_inputs','bitcoin_payout_active_candidates','bitcoin_payout_input_reservations'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);
  EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
END $$;
-- 원본 후보와 다른 행에 입력을 늦게 추가하거나 다른 망으로 예약해서 고유 제약을 우회하지 못한다.
CREATE OR REPLACE FUNCTION bitcoin_payout_allocation_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c record;
BEGIN
 SELECT * INTO c FROM bitcoin_payout_candidates WHERE id=NEW.candidate_id;
 IF coalesce(c.created_txid<>txid_current(),true) THEN RAISE EXCEPTION '후보와 입력 배정은 같은 커밋에서 완성해야 합니다';END IF;
 IF TG_TABLE_NAME='bitcoin_payout_active_candidates' THEN
  IF NEW.withdrawal_id<>c.withdrawal_id THEN RAISE EXCEPTION '활성 후보의 출금이 다릅니다';END IF;
 ELSIF TG_TABLE_NAME='bitcoin_payout_input_reservations' THEN
  IF NEW.network<>c.network THEN RAISE EXCEPTION '입력 배정의 망이 다릅니다';END IF;
 END IF;RETURN NEW;
END $$;
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_candidate_inputs','bitcoin_payout_active_candidates','bitcoin_payout_input_reservations'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS binding_check ON %I',relation);
  EXECUTE format('CREATE TRIGGER binding_check BEFORE INSERT ON %I FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_allocation_binding()',relation);
 END LOOP;
END $$;
CREATE OR REPLACE FUNCTION bitcoin_payout_preparation_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record;label text;audit_target text;audit_detail text;stamp timestamptz;
BEGIN
 IF TG_TABLE_NAME='bitcoin_payout_preparations' THEN a:=NEW;label:='BTC_PAYOUT_PREPARATION_STARTED';audit_target:='bitcoin-payout-preparation:'||a.id;audit_detail:=a.review_hash;stamp:=a.started_at;
 ELSE SELECT * INTO a FROM bitcoin_payout_preparations WHERE id=NEW.preparation_id;
  IF NEW.created_txid<>txid_current() OR NEW.recorded_at<a.started_at OR NEW.recorded_at>clock_timestamp()
   OR NEW.status='SAVED' AND NOT EXISTS(SELECT 1 FROM bitcoin_payout_candidates WHERE id=NEW.candidate_id AND preparation_id=a.id AND created_txid=txid_current()) THEN
   RAISE EXCEPTION '준비 결과와 원래 시도/후보가 다릅니다';END IF;
  label:='BTC_PAYOUT_PREPARATION_FINISHED';audit_target:='bitcoin-payout-preparation:'||a.id;audit_detail:=NEW.status;stamp:=NEW.recorded_at;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit WHERE actor_id IS NULL AND action=label AND security_audit.target=audit_target AND outcome='SUCCESS' AND security_audit.detail=audit_detail AND occurred_at>=stamp) THEN
  RAISE EXCEPTION '준비 시도와 결과의 감사를 같은 커밋에 보존해야 합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_preparation_audit_check ON bitcoin_payout_preparations;
CREATE CONSTRAINT TRIGGER bitcoin_payout_preparation_audit_check AFTER INSERT ON bitcoin_payout_preparations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_preparation_audit();
DROP TRIGGER IF EXISTS bitcoin_payout_result_audit_check ON bitcoin_payout_preparation_results;
CREATE CONSTRAINT TRIGGER bitcoin_payout_result_audit_check AFTER INSERT ON bitcoin_payout_preparation_results DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_preparation_audit();
