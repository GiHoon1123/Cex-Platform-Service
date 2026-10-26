CREATE TABLE IF NOT EXISTS bitcoin_payout_wallet_attempts (
 id uuid PRIMARY KEY,position bigserial UNIQUE,candidate_id uuid NOT NULL REFERENCES bitcoin_payout_candidates(id),
 operation varchar(8) NOT NULL CHECK(operation IN('LOCK','UNLOCK')),started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE INDEX IF NOT EXISTS bitcoin_payout_wallet_latest ON bitcoin_payout_wallet_attempts(candidate_id,position DESC);
CREATE TABLE IF NOT EXISTS bitcoin_payout_wallet_results (
 attempt_id uuid PRIMARY KEY REFERENCES bitcoin_payout_wallet_attempts(id),status varchar(16) NOT NULL CHECK(status IN('OBSERVED','UNKNOWN')),
 locked boolean,key_id varchar(64),ciphertext bytea,content_mac varchar(64),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK(status='OBSERVED' AND locked IS NOT NULL AND key_id IS NOT NULL AND ciphertext IS NOT NULL AND content_mac IS NOT NULL
  AND key_id ~ '^[0-9a-f]{64}$' AND content_mac ~ '^[0-9a-f]{64}$' AND octet_length(ciphertext) BETWEEN 28 AND 32796
  OR status='UNKNOWN' AND locked IS NULL AND key_id IS NULL AND ciphertext IS NULL AND content_mac IS NULL)
);
CREATE TABLE IF NOT EXISTS bitcoin_payout_candidate_releases (
 candidate_id uuid PRIMARY KEY REFERENCES bitcoin_payout_candidates(id),reason varchar(16) NOT NULL CHECK(reason IN('CANCELLED','SUPERSEDED','EXPIRED')),
 wallet_attempt uuid REFERENCES bitcoin_payout_wallet_results(attempt_id),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE OR REPLACE FUNCTION bitcoin_payout_retirement(target uuid) RETURNS text LANGUAGE sql AS $$
 SELECT CASE WHEN EXISTS(SELECT 1 FROM bitcoin_withdrawal_cancellations x WHERE x.withdrawal_id=c.withdrawal_id) THEN 'CANCELLED'
  WHEN EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_reviews newer WHERE newer.withdrawal_id=c.withdrawal_id AND newer.position>r.position) THEN 'SUPERSEDED'
  WHEN r.expires_at<=clock_timestamp() THEN 'EXPIRED' ELSE NULL END
 FROM bitcoin_payout_candidates c JOIN bitcoin_withdrawal_payout_reviews r ON r.id=c.review_id
 WHERE c.id=target AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=c.withdrawal_id);
$$;
CREATE OR REPLACE FUNCTION bitcoin_payout_wallet_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c record;prior record;
BEGIN
 SELECT * INTO c FROM bitcoin_payout_candidates WHERE id=NEW.candidate_id;PERFORM id FROM users WHERE id=c.user_id FOR UPDATE;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.started_at>clock_timestamp(),true)
  OR EXISTS(SELECT 1 FROM bitcoin_payout_candidate_releases WHERE candidate_id=c.id)
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=c.withdrawal_id) THEN RAISE EXCEPTION '종료/실행 확정된 후보의 입력 잠금을 변경할 수 없습니다';END IF;
 IF NEW.operation='LOCK' THEN
  IF NOT bitcoin_payout_source(c.review_id) OR NOT EXISTS(SELECT 1 FROM bitcoin_payout_active_candidates WHERE candidate_id=c.id)
   OR EXISTS(SELECT 1 FROM bitcoin_payout_wallet_attempts WHERE candidate_id=c.id AND operation='UNLOCK') THEN RAISE EXCEPTION '현재 활성 후보만 입력을 잠글 수 있습니다';END IF;
 ELSIF bitcoin_payout_retirement(c.id) IS NULL THEN RAISE EXCEPTION '미실행 종료 근거 없이 입력을 해제할 수 없습니다';END IF;
 SELECT a.operation,a.started_at,r.recorded_at INTO prior FROM bitcoin_payout_wallet_attempts a LEFT JOIN bitcoin_payout_wallet_results r ON r.attempt_id=a.id WHERE a.candidate_id=c.id ORDER BY a.position DESC LIMIT 1;
 IF FOUND AND coalesce(prior.recorded_at,prior.started_at)>clock_timestamp()-interval '30 seconds' THEN RAISE EXCEPTION '같은 후보의 잠금 작업을 즉시 겹쳐 실행하지 않습니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_wallet_binding_check ON bitcoin_payout_wallet_attempts;
CREATE TRIGGER bitcoin_payout_wallet_binding_check BEFORE INSERT ON bitcoin_payout_wallet_attempts FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_wallet_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_wallet_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operation text;start_time timestamptz;
BEGIN
 SELECT a.operation,a.started_at INTO operation,start_time FROM bitcoin_payout_wallet_attempts a WHERE a.id=NEW.attempt_id;
 IF NOT FOUND OR NEW.created_txid<>txid_current() OR NEW.recorded_at<start_time OR NEW.recorded_at>clock_timestamp()
  OR NEW.status='OBSERVED' AND NEW.locked<>(operation='LOCK') THEN RAISE EXCEPTION '입력 잠금 시도와 관측 결과가 다릅니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_wallet_result_binding_check ON bitcoin_payout_wallet_results;
CREATE TRIGGER bitcoin_payout_wallet_result_binding_check BEFORE INSERT ON bitcoin_payout_wallet_results FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_wallet_result_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_release_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c record;
BEGIN
 SELECT * INTO c FROM bitcoin_payout_candidates WHERE id=NEW.candidate_id;PERFORM id FROM users WHERE id=c.user_id FOR UPDATE;
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at>clock_timestamp() OR NEW.reason IS DISTINCT FROM bitcoin_payout_retirement(c.id) THEN RAISE EXCEPTION '미실행 후보의 현재 종료 근거가 필요합니다';END IF;
 IF NEW.wallet_attempt IS NULL THEN
  IF EXISTS(SELECT 1 FROM bitcoin_payout_wallet_attempts WHERE candidate_id=c.id AND operation='LOCK') THEN RAISE EXCEPTION '노드 잠금 시도가 있는 입력은 해제 관측이 필요합니다';END IF;
 ELSIF NOT EXISTS(SELECT 1 FROM bitcoin_payout_wallet_attempts a JOIN bitcoin_payout_wallet_results r ON r.attempt_id=a.id
  WHERE a.id=NEW.wallet_attempt AND a.candidate_id=c.id AND a.operation='UNLOCK' AND r.status='OBSERVED' AND NOT r.locked AND r.recorded_at>=clock_timestamp()-interval '30 seconds'
   AND NOT EXISTS(SELECT 1 FROM bitcoin_payout_wallet_attempts newer WHERE newer.candidate_id=c.id AND newer.position>a.position)) THEN RAISE EXCEPTION '같은 입력의 실제 해제 관측이 필요합니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_release_binding_check ON bitcoin_payout_candidate_releases;
CREATE TRIGGER bitcoin_payout_release_binding_check BEFORE INSERT ON bitcoin_payout_candidate_releases FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_release_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_current_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE candidate uuid;c record;
BEGIN
 IF TG_OP='TRUNCATE' OR TG_OP='UPDATE' THEN RAISE EXCEPTION '현재 입력 배정은 갱신/전체 삭제할 수 없습니다';END IF;
 IF TG_OP='INSERT' THEN candidate:=NEW.candidate_id;ELSE candidate:=OLD.candidate_id;END IF;
 SELECT * INTO c FROM bitcoin_payout_candidates WHERE id=candidate;PERFORM id FROM users WHERE id=c.user_id FOR UPDATE;
 IF TG_OP='DELETE' THEN
  IF NOT EXISTS(SELECT 1 FROM bitcoin_payout_candidate_releases WHERE candidate_id=c.id) OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=c.withdrawal_id) THEN
   RAISE EXCEPTION '미실행 해제 증거 없이 현재 입력 배정을 삭제할 수 없습니다';END IF;RETURN OLD;
 END IF;
 IF EXISTS(SELECT 1 FROM bitcoin_payout_candidate_releases WHERE candidate_id=c.id) THEN RAISE EXCEPTION '종료한 입력 배정을 되살릴 수 없습니다';END IF;
 IF TG_TABLE_NAME='bitcoin_payout_active_candidates' THEN
  IF NEW.withdrawal_id<>c.withdrawal_id THEN RAISE EXCEPTION '활성 후보의 출금 원본이 다릅니다';END IF;
 ELSIF NEW.network<>c.network THEN RAISE EXCEPTION '입력 배정의 체인이 다릅니다';END IF;RETURN NEW;
END $$;
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_active_candidates','bitcoin_payout_input_reservations'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS bitcoin_payout_current_check ON %I',relation);
  EXECUTE format('CREATE TRIGGER bitcoin_payout_current_check BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_current_binding()',relation);
  EXECUTE format('DROP TRIGGER IF EXISTS bitcoin_payout_current_no_truncate ON %I',relation);
  EXECUTE format('CREATE TRIGGER bitcoin_payout_current_no_truncate BEFORE TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
END $$;
-- 영구 이력은 보존한다. 현재 배정의 삭제만 동일 커밋의 미실행 해제 증거와 결합한다.
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_wallet_attempts','bitcoin_payout_wallet_results','bitcoin_payout_candidate_releases'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);
  EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_active_candidates','bitcoin_payout_input_reservations'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);
  EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
 END LOOP;
END $$;
CREATE OR REPLACE FUNCTION bitcoin_payout_wallet_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record;label text;audit_target text;audit_detail text;stamp timestamptz;
BEGIN
 IF TG_TABLE_NAME='bitcoin_payout_wallet_attempts' THEN a:=NEW;label:='BTC_PAYOUT_WALLET_STARTED';audit_target:='bitcoin-payout-wallet:'||a.id;audit_detail:=a.operation;stamp:=a.started_at;
 ELSIF TG_TABLE_NAME='bitcoin_payout_wallet_results' THEN
  SELECT * INTO a FROM bitcoin_payout_wallet_attempts WHERE id=NEW.attempt_id;
  label:='BTC_PAYOUT_WALLET_OBSERVED';audit_target:='bitcoin-payout-wallet:'||a.id;audit_detail:=NEW.status;stamp:=NEW.recorded_at;
 ELSE
  label:='BTC_PAYOUT_CANDIDATE_RELEASED';audit_target:='bitcoin-payout-candidate:'||NEW.candidate_id;audit_detail:=NEW.reason;stamp:=NEW.recorded_at;
  IF EXISTS(SELECT 1 FROM bitcoin_payout_active_candidates WHERE candidate_id=NEW.candidate_id)
   OR EXISTS(SELECT 1 FROM bitcoin_payout_input_reservations WHERE candidate_id=NEW.candidate_id) THEN RAISE EXCEPTION '해제 증거와 현재 배정 전체 정리를 함께 확정해야 합니다';END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit WHERE actor_id IS NULL AND action=label AND target=audit_target AND outcome='SUCCESS' AND detail=audit_detail AND occurred_at>=stamp) THEN
  RAISE EXCEPTION '입력 잠금 시도·관측·해제에는 같은 커밋의 감사가 필요합니다';END IF;RETURN NULL;
END $$;
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_wallet_attempts','bitcoin_payout_wallet_results','bitcoin_payout_candidate_releases'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS bitcoin_payout_wallet_audit_check ON %I',relation);
  EXECUTE format('CREATE CONSTRAINT TRIGGER bitcoin_payout_wallet_audit_check AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_wallet_audit()',relation);
 END LOOP;
END $$;
-- 현재 배정만 먼저 삭제하고 나중에 해제 증거를 붙이는 경로도 금지한다.
CREATE OR REPLACE FUNCTION bitcoin_payout_deleted_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bitcoin_payout_candidate_releases WHERE candidate_id=OLD.candidate_id AND created_txid=txid_current()) THEN
  RAISE EXCEPTION '현재 배정 정리는 같은 커밋의 미실행 해제와 결합해야 합니다';END IF;RETURN NULL;
END $$;
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_payout_active_candidates','bitcoin_payout_input_reservations'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS bitcoin_payout_deleted_allocation_check ON %I',relation);
  EXECUTE format('CREATE CONSTRAINT TRIGGER bitcoin_payout_deleted_allocation_check AFTER DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_deleted_allocation()',relation);
 END LOOP;
END $$;
