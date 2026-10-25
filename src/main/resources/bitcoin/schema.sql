-- 원장은 BTC 자산 코드를 사용하므로 실행 DB의 체인은 하나로 고정한다. 설정 변경으로 시험 자산을 실망 자산으로 바꾸지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_network_identity (
 singleton integer PRIMARY KEY CHECK(singleton=1),network varchar(32) NOT NULL CHECK(network IN ('bitcoin-regtest','bitcoin-mainnet')),
 bound_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
DROP TRIGGER IF EXISTS bitcoin_network_identity_immutable ON bitcoin_network_identity;
CREATE TRIGGER bitcoin_network_identity_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_network_identity
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 고객 주소의 소유자는 발급 이후 바꾸거나 삭제하지 않는다. 네트워크를 포함해 주소 혼동을 막는다.
CREATE TABLE IF NOT EXISTS bitcoin_deposit_addresses (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),
 network varchar(32) NOT NULL CHECK(network IN ('bitcoin-regtest','bitcoin-mainnet')),
 wallet varchar(64) NOT NULL,address varchar(100) NOT NULL,
 script_pub_key varchar(44) NOT NULL CHECK(script_pub_key ~ '^0014[0-9a-f]{40}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(user_id,network),UNIQUE(network,address),UNIQUE(network,script_pub_key)
);
DROP TRIGGER IF EXISTS bitcoin_address_no_change ON bitcoin_deposit_addresses;
CREATE TRIGGER bitcoin_address_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_deposit_addresses
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 노드 관찰은 금융 적립과 구분한다. 미확정/충돌도 원본으로 보존하고 새 관찰로 현재 상태를 갱신한다.
CREATE TABLE IF NOT EXISTS bitcoin_transaction_observations (
 id bigserial PRIMARY KEY,network varchar(32) NOT NULL CHECK(network IN ('bitcoin-regtest','bitcoin-mainnet')),
 wallet varchar(64) NOT NULL,txid varchar(64) NOT NULL CHECK(txid ~ '^[0-9a-f]{64}$'),
 status varchar(16) NOT NULL CHECK(status IN ('UNCONFIRMED','CONFIRMED','CONFLICTED')),
 confirmations bigint NOT NULL,coinbase boolean NOT NULL,
 block_hash varchar(64),block_height bigint,
 tip_hash varchar(64) NOT NULL CHECK(tip_hash ~ '^[0-9a-f]{64}$'),tip_height bigint NOT NULL CHECK(tip_height>=0),
 outputs jsonb NOT NULL CHECK(jsonb_typeof(outputs)='array'),
 read_started_at timestamptz NOT NULL,recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(id,network,txid),
 CHECK((status='CONFIRMED' AND confirmations>0 AND block_hash ~ '^[0-9a-f]{64}$' AND block_hash IS NOT NULL
    AND block_height IS NOT NULL AND block_height>=0 AND block_height<=tip_height AND confirmations=tip_height-block_height+1)
  OR(status='UNCONFIRMED' AND confirmations=0 AND block_hash IS NULL AND block_height IS NULL)
  OR(status='CONFLICTED' AND confirmations<0 AND block_hash IS NULL AND block_height IS NULL))
);
CREATE INDEX IF NOT EXISTS bitcoin_observation_history ON bitcoin_transaction_observations(network,txid,id);
DROP TRIGGER IF EXISTS bitcoin_observation_no_change ON bitcoin_transaction_observations;
CREATE TRIGGER bitcoin_observation_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_transaction_observations
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

CREATE TABLE IF NOT EXISTS bitcoin_observation_heads (
 network varchar(32) NOT NULL,txid varchar(64) NOT NULL,observation_id bigint NOT NULL,
 PRIMARY KEY(network,txid),FOREIGN KEY(observation_id,network,txid) REFERENCES bitcoin_transaction_observations(id,network,txid)
);
-- 기존 사실을 현재 관찰로 재사용하거나 현재 포인터를 과거/타 거래로 되돌리지 않는다.
CREATE OR REPLACE FUNCTION bitcoin_head_require_fresh_observation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' THEN
  IF NEW.network<>OLD.network OR NEW.txid<>OLD.txid OR NEW.observation_id<=OLD.observation_id THEN
   RAISE EXCEPTION '현재 입금 관찰은 새로운 같은 거래 원본으로만 전진해야 합니다';
  END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM bitcoin_transaction_observations o WHERE o.id=NEW.observation_id
   AND o.network=NEW.network AND o.txid=NEW.txid AND o.created_txid=txid_current()) THEN
  RAISE EXCEPTION '현재 관찰과 원본은 같은 트랜잭션에서 확정해야 합니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_head_fresh_source ON bitcoin_observation_heads;
CREATE TRIGGER bitcoin_head_fresh_source BEFORE INSERT OR UPDATE ON bitcoin_observation_heads
 FOR EACH ROW EXECUTE FUNCTION bitcoin_head_require_fresh_observation();
DROP TRIGGER IF EXISTS bitcoin_head_no_delete ON bitcoin_observation_heads;
CREATE TRIGGER bitcoin_head_no_delete BEFORE DELETE OR TRUNCATE ON bitcoin_observation_heads
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 반복하는 주소 목록은 불변 원본 하나를 공유한다. 주소가 추가된 다음 조회는 새로운 목록 원본을 만든다.
CREATE TABLE IF NOT EXISTS bitcoin_scan_registries (
 id bigserial PRIMARY KEY,network varchar(32) NOT NULL CHECK(network IN ('bitcoin-regtest','bitcoin-mainnet')),
 wallet varchar(64) NOT NULL,digest varchar(64) NOT NULL CHECK(digest ~ '^[0-9a-f]{64}$'),
 addresses jsonb NOT NULL CHECK(jsonb_typeof(addresses)='array' AND jsonb_array_length(addresses)>0),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(network,wallet,digest),UNIQUE(id,network,wallet)
);
DROP TRIGGER IF EXISTS bitcoin_registry_no_change ON bitcoin_scan_registries;
CREATE TRIGGER bitcoin_registry_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_scan_registries
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

CREATE TABLE IF NOT EXISTS bitcoin_block_scans (
 id bigserial PRIMARY KEY,network varchar(32) NOT NULL,wallet varchar(64) NOT NULL,
 previous_scan_id bigint,height bigint NOT NULL CHECK(height>=0),
 block_hash varchar(64) NOT NULL CHECK(block_hash ~ '^[0-9a-f]{64}$'),parent_hash varchar(64),
 tip_hash varchar(64) NOT NULL CHECK(tip_hash ~ '^[0-9a-f]{64}$'),tip_height bigint NOT NULL CHECK(tip_height>=height),
 registry_id bigint NOT NULL,received jsonb NOT NULL CHECK(jsonb_typeof(received)='array'),
 created_txid bigint NOT NULL DEFAULT txid_current(),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(id,network,wallet),
 FOREIGN KEY(registry_id,network,wallet) REFERENCES bitcoin_scan_registries(id,network,wallet),
 FOREIGN KEY(previous_scan_id,network,wallet) REFERENCES bitcoin_block_scans(id,network,wallet),
 CHECK((height=0 AND parent_hash IS NULL AND previous_scan_id IS NULL)
   OR(height>0 AND parent_hash IS NOT NULL AND parent_hash ~ '^[0-9a-f]{64}$' AND previous_scan_id IS NOT NULL))
);
DROP TRIGGER IF EXISTS bitcoin_scan_no_change ON bitcoin_block_scans;
CREATE TRIGGER bitcoin_scan_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_block_scans
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

CREATE TABLE IF NOT EXISTS bitcoin_scan_heads (
 network varchar(32) NOT NULL,wallet varchar(64) NOT NULL,scan_id bigint NOT NULL,
 PRIMARY KEY(network,wallet),FOREIGN KEY(scan_id,network,wallet) REFERENCES bitcoin_block_scans(id,network,wallet)
);
-- 완료 원본과 같은 트랜잭션에서만 한 블록 전진한다. 오래된 원본 재활성/건너뛰기/다른 지갑 연결은 금지한다.
CREATE OR REPLACE FUNCTION bitcoin_scan_head_require_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_scan bitcoin_block_scans%ROWTYPE; previous_scan bitcoin_block_scans%ROWTYPE;
BEGIN
 SELECT * INTO current_scan FROM bitcoin_block_scans WHERE id=NEW.scan_id AND network=NEW.network AND wallet=NEW.wallet;
 IF NOT FOUND OR current_scan.created_txid<>txid_current() THEN RAISE EXCEPTION '진행 위치는 새 완료 원본과 함께 확정해야 합니다'; END IF;
 IF TG_OP='INSERT' THEN
  IF current_scan.height<>0 OR current_scan.previous_scan_id IS NOT NULL THEN RAISE EXCEPTION '최초 조회는 첫 블록에서 시작해야 합니다'; END IF;
 ELSE
  SELECT * INTO previous_scan FROM bitcoin_block_scans WHERE id=OLD.scan_id;
  IF NEW.network<>OLD.network OR NEW.wallet<>OLD.wallet OR NEW.scan_id<=OLD.scan_id
     OR current_scan.previous_scan_id IS DISTINCT FROM OLD.scan_id OR current_scan.height<>previous_scan.height+1
     OR current_scan.parent_hash IS DISTINCT FROM previous_scan.block_hash THEN
   RAISE EXCEPTION '진행 위치는 같은 체인의 바로 다음 완료 블록이어야 합니다';
  END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_scan_head_complete ON bitcoin_scan_heads;
CREATE TRIGGER bitcoin_scan_head_complete BEFORE INSERT OR UPDATE ON bitcoin_scan_heads
 FOR EACH ROW EXECUTE FUNCTION bitcoin_scan_head_require_complete();
DROP TRIGGER IF EXISTS bitcoin_scan_head_no_delete ON bitcoin_scan_heads;
CREATE TRIGGER bitcoin_scan_head_no_delete BEFORE DELETE OR TRUNCATE ON bitcoin_scan_heads
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 출력 하나는 네트워크/거래 ID/출력 번호로 식별한다. 블록 이동/재조회로 입금 ID를 새로 만들지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_deposit_receipts (
 id uuid PRIMARY KEY,network varchar(32) NOT NULL CHECK(network IN ('bitcoin-regtest','bitcoin-mainnet')),
 txid varchar(64) NOT NULL CHECK(txid ~ '^[0-9a-f]{64}$'),vout integer NOT NULL CHECK(vout>=0),
 address_id uuid NOT NULL REFERENCES bitcoin_deposit_addresses(id),user_id bigint NOT NULL REFERENCES users(id),
 asset varchar(8) NOT NULL CHECK(asset='BTC'),amount numeric NOT NULL CHECK(amount>0 AND amount<=21000000 AND scale(amount)<=8),
 first_scan_id bigint NOT NULL REFERENCES bitcoin_block_scans(id),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(network,txid,vout)
);
CREATE INDEX IF NOT EXISTS bitcoin_receipt_customer ON bitcoin_deposit_receipts(user_id,recorded_at,id);
CREATE INDEX IF NOT EXISTS bitcoin_receipt_first_scan ON bitcoin_deposit_receipts(first_scan_id);
CREATE OR REPLACE FUNCTION bitcoin_receipt_require_output() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bitcoin_block_scans s CROSS JOIN LATERAL jsonb_array_elements(s.received) o
   JOIN bitcoin_deposit_addresses a ON a.id=(o->>'addressId')::uuid AND a.network=s.network AND a.wallet=s.wallet
   WHERE s.id=NEW.first_scan_id AND s.network=NEW.network AND a.id=NEW.address_id AND a.user_id=NEW.user_id
     AND o->>'txid'=NEW.txid AND (o->>'vout')::integer=NEW.vout AND (o->>'amount')::numeric=NEW.amount) THEN
  RAISE EXCEPTION '입금은 완료 블록의 같은 고객 출력과 정확한 금액을 참조해야 합니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_receipt_source ON bitcoin_deposit_receipts;
CREATE TRIGGER bitcoin_receipt_source BEFORE INSERT ON bitcoin_deposit_receipts FOR EACH ROW EXECUTE FUNCTION bitcoin_receipt_require_output();
DROP TRIGGER IF EXISTS bitcoin_receipt_no_change ON bitcoin_deposit_receipts;
CREATE TRIGGER bitcoin_receipt_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_deposit_receipts
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

CREATE TABLE IF NOT EXISTS bitcoin_scan_receipt_projections (
 scan_id bigint PRIMARY KEY REFERENCES bitcoin_block_scans(id),output_count integer NOT NULL CHECK(output_count>=0),
 new_count integer NOT NULL CHECK(new_count>=0 AND new_count<=output_count),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE OR REPLACE FUNCTION bitcoin_projection_require_receipts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE outputs integer;
BEGIN
 SELECT count(*) INTO outputs FROM bitcoin_block_scans s CROSS JOIN LATERAL jsonb_array_elements(s.received) o
  WHERE s.id=NEW.scan_id AND (o->>'amount')::numeric>0;
 IF outputs<>NEW.output_count OR NEW.new_count<>(SELECT count(*) FROM bitcoin_deposit_receipts WHERE first_scan_id=NEW.scan_id) OR EXISTS(
  SELECT 1 FROM bitcoin_block_scans s CROSS JOIN LATERAL jsonb_array_elements(s.received) o
   WHERE s.id=NEW.scan_id AND (o->>'amount')::numeric>0 AND NOT EXISTS(
    SELECT 1 FROM bitcoin_deposit_receipts r WHERE r.network=s.network AND r.txid=o->>'txid'
      AND r.vout=(o->>'vout')::integer AND r.address_id=(o->>'addressId')::uuid AND r.amount=(o->>'amount')::numeric)) THEN
  RAISE EXCEPTION '양수 출력의 입금 원본을 모두 식별한 뒤에만 완료할 수 있습니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_projection_complete ON bitcoin_scan_receipt_projections;
CREATE TRIGGER bitcoin_projection_complete BEFORE INSERT ON bitcoin_scan_receipt_projections
 FOR EACH ROW EXECUTE FUNCTION bitcoin_projection_require_receipts();
DROP TRIGGER IF EXISTS bitcoin_projection_no_change ON bitcoin_scan_receipt_projections;
CREATE TRIGGER bitcoin_projection_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_scan_receipt_projections
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 작업 행은 실행 상태만 갱신한다. 관찰/입금 원본을 수정하거나 실패를 금액으로 보정하지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_deposit_observation_jobs (
 network varchar(32) NOT NULL,wallet varchar(64) NOT NULL,txid varchar(64) NOT NULL CHECK(txid ~ '^[0-9a-f]{64}$'),
 next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),lease_id uuid,lease_until timestamptz,
 latest_observation_id bigint,last_attempt_at timestamptz,last_outcome varchar(32),failures bigint NOT NULL DEFAULT 0 CHECK(failures>=0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(network,wallet,txid),
 FOREIGN KEY(latest_observation_id,network,txid) REFERENCES bitcoin_transaction_observations(id,network,txid),
 CHECK((lease_id IS NULL)=(lease_until IS NULL)),
 CHECK(last_outcome IS NULL OR last_outcome IN ('OBSERVED','OBSERVATION_CONFLICT','NODE_UNAVAILABLE','INTERNAL_FAILURE'))
);
CREATE INDEX IF NOT EXISTS bitcoin_observation_job_due ON bitcoin_deposit_observation_jobs(network,wallet,next_attempt_at,txid);
CREATE OR REPLACE FUNCTION bitcoin_observation_job_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NOT EXISTS(SELECT 1 FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id
    WHERE r.network=NEW.network AND a.wallet=NEW.wallet AND r.txid=NEW.txid) THEN
   RAISE EXCEPTION '관찰 작업은 같은 네트워크/지갑의 실제 입금 원본을 참조해야 합니다';
  END IF;
 ELSE
  IF NEW.network<>OLD.network OR NEW.wallet<>OLD.wallet OR NEW.txid<>OLD.txid OR NEW.created_at<>OLD.created_at
    OR(OLD.latest_observation_id IS NOT NULL AND (NEW.latest_observation_id IS NULL OR NEW.latest_observation_id<OLD.latest_observation_id)) THEN
   RAISE EXCEPTION '관찰 작업의 원본을 바꾸거나 과거 결과로 되돌릴 수 없습니다';
  END IF;
 END IF;
 IF NEW.latest_observation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM bitcoin_transaction_observations o
    WHERE o.id=NEW.latest_observation_id AND o.network=NEW.network AND o.wallet=NEW.wallet AND o.txid=NEW.txid) THEN
  RAISE EXCEPTION '관찰 결과는 같은 지갑/거래 원본이어야 합니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_observation_job_original ON bitcoin_deposit_observation_jobs;
CREATE TRIGGER bitcoin_observation_job_original BEFORE INSERT OR UPDATE ON bitcoin_deposit_observation_jobs
 FOR EACH ROW EXECUTE FUNCTION bitcoin_observation_job_source();
DROP TRIGGER IF EXISTS bitcoin_observation_job_no_delete ON bitcoin_deposit_observation_jobs;
CREATE TRIGGER bitcoin_observation_job_no_delete BEFORE DELETE OR TRUNCATE ON bitcoin_deposit_observation_jobs
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 온체인 준비 판단은 고객 자격/금융 반입 허용과 구분한다. 적용 정책과 당시 관찰을 불변으로 연결한다.
CREATE TABLE IF NOT EXISTS bitcoin_deposit_chain_decisions (
 id bigserial PRIMARY KEY,receipt_id uuid NOT NULL REFERENCES bitcoin_deposit_receipts(id),
 observation_id bigint NOT NULL REFERENCES bitcoin_transaction_observations(id),
 policy_id varchar(32) NOT NULL CHECK(policy_id='BTC_CHAIN_V1'),
 reason varchar(32) NOT NULL CHECK(reason IN ('OUTPUT_MISMATCH','CONFLICTED','WAITING_CHAIN','STALE_OBSERVATION','IMMATURE_COINBASE','WAITING_CONFIRMATIONS','CHAIN_READY')),
 created_txid bigint NOT NULL DEFAULT txid_current(),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(id,receipt_id)
);
CREATE OR REPLACE FUNCTION bitcoin_chain_decision_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source record; output_count integer; matching_count integer;
BEGIN
 SELECT o.*,a.address,a.script_pub_key,r.vout,r.amount AS receipt_amount INTO source
  FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id
  JOIN bitcoin_transaction_observations o ON o.id=NEW.observation_id AND o.network=r.network AND o.txid=r.txid AND o.wallet=a.wallet
  JOIN bitcoin_observation_heads h ON h.observation_id=o.id WHERE r.id=NEW.receipt_id FOR SHARE OF h;
 IF NOT FOUND THEN RAISE EXCEPTION '온체인 판단은 같은 고객 입금의 현재 거래 관찰을 참조해야 합니다'; END IF;
 IF NEW.reason='CHAIN_READY' THEN
  SELECT count(*),count(*) FILTER(WHERE e->>'address'=source.address AND e->>'scriptPubKey'=source.script_pub_key AND (e->>'amount')::numeric=source.receipt_amount)
    INTO output_count,matching_count FROM jsonb_array_elements(source.outputs) e WHERE (e->>'vout')::integer=source.vout;
  IF source.status<>'CONFIRMED' OR source.confirmations<6 OR(source.coinbase AND source.confirmations<101)
     OR source.read_started_at>clock_timestamp() OR source.read_started_at<clock_timestamp()-interval '2 minutes'
     OR output_count<>1 OR matching_count<>1 THEN RAISE EXCEPTION '온체인 준비 조건을 충족하지 않은 입금입니다'; END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_decision_original ON bitcoin_deposit_chain_decisions;
CREATE TRIGGER bitcoin_chain_decision_original BEFORE INSERT ON bitcoin_deposit_chain_decisions
 FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_decision_source();
DROP TRIGGER IF EXISTS bitcoin_chain_decision_no_change ON bitcoin_deposit_chain_decisions;
CREATE TRIGGER bitcoin_chain_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_deposit_chain_decisions
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

CREATE TABLE IF NOT EXISTS bitcoin_deposit_chain_heads (
 receipt_id uuid PRIMARY KEY REFERENCES bitcoin_deposit_receipts(id),decision_id bigint NOT NULL,
 FOREIGN KEY(decision_id,receipt_id) REFERENCES bitcoin_deposit_chain_decisions(id,receipt_id)
);
CREATE OR REPLACE FUNCTION bitcoin_chain_head_fresh() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND(NEW.receipt_id<>OLD.receipt_id OR NEW.decision_id<=OLD.decision_id) THEN
  RAISE EXCEPTION '온체인 판단은 같은 입금의 새 원본으로만 전진해야 합니다';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM bitcoin_deposit_chain_decisions d WHERE d.id=NEW.decision_id AND d.receipt_id=NEW.receipt_id AND d.created_txid=txid_current()) THEN
  RAISE EXCEPTION '현재 온체인 판단과 원본은 같은 트랜잭션에서 확정해야 합니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_head_original ON bitcoin_deposit_chain_heads;
CREATE TRIGGER bitcoin_chain_head_original BEFORE INSERT OR UPDATE ON bitcoin_deposit_chain_heads
 FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_head_fresh();
DROP TRIGGER IF EXISTS bitcoin_chain_head_no_delete ON bitcoin_deposit_chain_heads;
CREATE TRIGGER bitcoin_chain_head_no_delete BEFORE DELETE OR TRUNCATE ON bitcoin_deposit_chain_heads
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 현재 관찰의 지갑 사용 표지를 별도 불변 근거로 남긴다. 이 근거가 없는 과거 관찰은 외부 입금으로 추측하지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_observation_wallet_debits (
 observation_id bigint PRIMARY KEY REFERENCES bitcoin_transaction_observations(id),wallet_debit boolean NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE OR REPLACE FUNCTION bitcoin_wallet_debit_new_observation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bitcoin_transaction_observations o WHERE o.id=NEW.observation_id AND o.created_txid=txid_current()
   AND NOT(o.coinbase AND NEW.wallet_debit)) THEN
  RAISE EXCEPTION '지갑 사용 근거는 모순 없는 새 관찰과 같은 트랜잭션에서 확정해야 합니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_wallet_debit_fresh ON bitcoin_observation_wallet_debits;
CREATE TRIGGER bitcoin_wallet_debit_fresh BEFORE INSERT ON bitcoin_observation_wallet_debits
 FOR EACH ROW EXECUTE FUNCTION bitcoin_wallet_debit_new_observation();
DROP TRIGGER IF EXISTS bitcoin_wallet_debit_no_change ON bitcoin_observation_wallet_debits;
CREATE TRIGGER bitcoin_wallet_debit_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_observation_wallet_debits
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 고객 보류 자산은 실제 입금/현재 판단/원장 항목을 함께 확정한다. 거래 가능 잔액과 엔진 입력은 별도다.
CREATE TABLE IF NOT EXISTS bitcoin_pending_deposits (
 receipt_id uuid PRIMARY KEY REFERENCES bitcoin_deposit_receipts(id),decision_id bigint NOT NULL,
 operation_key varchar(160) NOT NULL UNIQUE REFERENCES ledger_transactions(operation_key),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(decision_id,receipt_id) REFERENCES bitcoin_deposit_chain_decisions(id,receipt_id)
);
DROP TRIGGER IF EXISTS bitcoin_pending_deposit_no_change ON bitcoin_pending_deposits;
CREATE TRIGGER bitcoin_pending_deposit_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_pending_deposits
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

CREATE OR REPLACE FUNCTION bitcoin_pending_ledger_verify(op varchar) RETURNS void LANGUAGE plpgsql AS $$
DECLARE source record; entries integer;
BEGIN
 SELECT r.user_id,r.asset,r.amount INTO source
  FROM bitcoin_pending_deposits f JOIN bitcoin_deposit_receipts r ON r.id=f.receipt_id
  JOIN ledger_transactions t ON t.operation_key=f.operation_key AND t.kind='BTC_DEPOSIT_PENDING' AND t.created_txid=txid_current()
  JOIN bitcoin_deposit_chain_decisions d ON d.id=f.decision_id AND d.receipt_id=r.id AND d.reason='CHAIN_READY' AND d.policy_id='BTC_CHAIN_V1'
  JOIN bitcoin_deposit_chain_heads qh ON qh.receipt_id=r.id AND qh.decision_id=d.id
  JOIN bitcoin_observation_heads oh ON oh.observation_id=d.observation_id JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id
  JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id AND w.wallet_debit=false
  WHERE f.operation_key=op AND f.operation_key='btc:pending:'||r.id::text
   AND o.read_started_at<=clock_timestamp() AND o.read_started_at>=clock_timestamp()-interval '2 minutes'
  FOR SHARE OF qh,oh;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key='opening') THEN
  RAISE EXCEPTION '보류 원장은 최초 반입과 현재 검증된 입금 근거가 필요합니다';
 END IF;
 SELECT count(*) INTO entries FROM ledger_entries WHERE operation_key=op;
 IF entries<>2 OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=op AND owner_id=source.user_id AND bucket='PENDING' AND asset=source.asset AND delta=source.amount)
   OR NOT EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=op AND owner_id=0 AND bucket='CUSTODY' AND asset=source.asset AND delta=-source.amount) THEN
  RAISE EXCEPTION '보류 원장의 양쪽 항목은 같은 입금의 정확한 소유자/자산/금액이어야 합니다';
 END IF;
END $$;
CREATE OR REPLACE FUNCTION bitcoin_pending_source_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM bitcoin_pending_ledger_verify(NEW.operation_key); RETURN NEW; END $$;
DROP TRIGGER IF EXISTS bitcoin_pending_source_balanced ON bitcoin_pending_deposits;
CREATE CONSTRAINT TRIGGER bitcoin_pending_source_balanced AFTER INSERT ON bitcoin_pending_deposits
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_pending_source_commit();
DROP TRIGGER IF EXISTS bitcoin_pending_ledger_has_source ON ledger_transactions;
CREATE CONSTRAINT TRIGGER bitcoin_pending_ledger_has_source AFTER INSERT ON ledger_transactions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.kind='BTC_DEPOSIT_PENDING') EXECUTE FUNCTION bitcoin_pending_source_commit();
-- 일반 업무 키로 보류/수탁 계정을 만들어 입금 근거 검사를 우회하지 못한다.
CREATE OR REPLACE FUNCTION bitcoin_pending_entry_kind() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.operation_key AND kind='BTC_DEPOSIT_PENDING') THEN
  -- 실제 엔진 입금 확정에 연결된 고객 보류 감소만 추가 허용한다. 수탁 계정이나 임의 업무의 보류 이동은 열지 않는다.
  IF NEW.bucket='PENDING' AND NEW.delta<0 AND EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.operation_key AND kind='BTC_DEPOSIT_CREDIT') THEN
   IF EXISTS(SELECT 1 FROM bitcoin_credited_deposits c JOIN bitcoin_credit_authorizations a ON a.receipt_id=c.receipt_id
    WHERE c.operation_key=NEW.operation_key AND c.created_txid=txid_current() AND a.user_id=NEW.owner_id AND a.asset=NEW.asset AND NEW.delta=-a.amount) THEN RETURN NEW;END IF;
  END IF;
  RAISE EXCEPTION '보류/수탁 항목은 검증된 입금 반입 업무에서만 생성할 수 있습니다';
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_pending_entry_bound ON ledger_entries;
CREATE CONSTRAINT TRIGGER bitcoin_pending_entry_bound AFTER INSERT ON ledger_entries
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.bucket IN ('PENDING','CUSTODY')) EXECUTE FUNCTION bitcoin_pending_entry_kind();

-- 입금 한 건의 대사는 거래소 전체 수탁 자산 증명과 구분한다. 완료 뒤 증거/차이/범위를 바꾸지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_deposit_reconciliation_runs (
 id uuid PRIMARY KEY,receipt_id uuid NOT NULL REFERENCES bitcoin_deposit_receipts(id),network varchar(32) NOT NULL,wallet varchar(64) NOT NULL,
 scope varchar(32) NOT NULL CHECK(scope='PENDING_RECEIPT'),snapshot_at timestamptz NOT NULL,
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),completed_at timestamptz,
 status varchar(16) NOT NULL CHECK(status IN ('RUNNING','MATCHED','MISMATCH','INCOMPLETE','HELD','FAILED')),
 CHECK((status='RUNNING' AND completed_at IS NULL) OR(status<>'RUNNING' AND completed_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS bitcoin_deposit_reconciliation_history ON bitcoin_deposit_reconciliation_runs(receipt_id,started_at,id);
CREATE TABLE IF NOT EXISTS bitcoin_deposit_reconciliation_evidence (
 run_id uuid NOT NULL REFERENCES bitcoin_deposit_reconciliation_runs(id),kind varchar(16) NOT NULL CHECK(kind IN ('PLATFORM','NODE')),
 payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),payload text NOT NULL CHECK(jsonb_typeof(payload::jsonb)='object'),
 CHECK(payload_hash=encode(sha256(convert_to(payload,'UTF8')),'hex')),PRIMARY KEY(run_id,kind)
);
CREATE TABLE IF NOT EXISTS bitcoin_deposit_reconciliation_issues (
 id bigserial PRIMARY KEY,run_id uuid NOT NULL REFERENCES bitcoin_deposit_reconciliation_runs(id),code varchar(64) NOT NULL,expected text NOT NULL,actual text NOT NULL
);
CREATE OR REPLACE FUNCTION bitcoin_deposit_reconciliation_open() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_runs WHERE id=NEW.run_id AND status='RUNNING' FOR SHARE) THEN
  RAISE EXCEPTION '완료한 입금 대사에 증거/차이를 추가할 수 없습니다';
 END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION bitcoin_deposit_reconciliation_finish() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION '입금 대사 원본을 삭제할 수 없습니다'; END IF;
 IF TG_OP='INSERT' THEN
  -- 직접 SQL로 시작하더라도 고객 잠금 뒤에만 시작 순번을 할당한다. 외부 노드 호출은 이 트랜잭션 밖이다.
  PERFORM u.id FROM users u JOIN bitcoin_deposit_receipts r ON r.user_id=u.id WHERE r.id=NEW.receipt_id FOR UPDATE OF u;
  IF NEW.status<>'RUNNING' OR NEW.completed_at IS NOT NULL THEN RAISE EXCEPTION '입금 대사는 실행 상태에서 시작해야 합니다'; END IF;
  IF NOT EXISTS(SELECT 1 FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id
    WHERE r.id=NEW.receipt_id AND r.network=NEW.network AND a.wallet=NEW.wallet) OR NEW.snapshot_at>NEW.started_at THEN
   RAISE EXCEPTION '입금 대사 범위와 시점은 원본에 일치해야 합니다';
  END IF;
 ELSE
  IF NEW.completed_at<OLD.started_at OR OLD.status<>'RUNNING' OR NEW.status='RUNNING' OR NEW.id<>OLD.id OR NEW.receipt_id<>OLD.receipt_id OR NEW.network<>OLD.network OR NEW.wallet<>OLD.wallet
    OR NEW.scope<>OLD.scope OR NEW.snapshot_at<>OLD.snapshot_at OR NEW.started_at<>OLD.started_at THEN RAISE EXCEPTION '완료한 입금 대사의 범위/결과를 바꿀 수 없습니다'; END IF;
  IF NOT EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=NEW.id AND kind='PLATFORM')
    OR(NEW.status<>'FAILED' AND NOT EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=NEW.id AND kind='NODE'))
    OR(NEW.status='MATCHED' AND EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_issues WHERE run_id=NEW.id))
    OR(NEW.status<>'MATCHED' AND NOT EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_issues WHERE run_id=NEW.id)) THEN
   RAISE EXCEPTION '입금 대사 결과에는 범위에 맞는 증거와 차이 기록이 필요합니다';
  END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_deposit_reconciliation_state ON bitcoin_deposit_reconciliation_runs;
CREATE TRIGGER bitcoin_deposit_reconciliation_state BEFORE INSERT OR UPDATE OR DELETE ON bitcoin_deposit_reconciliation_runs
 FOR EACH ROW EXECUTE FUNCTION bitcoin_deposit_reconciliation_finish();
DROP TRIGGER IF EXISTS bitcoin_deposit_reconciliation_no_truncate ON bitcoin_deposit_reconciliation_runs;
CREATE TRIGGER bitcoin_deposit_reconciliation_no_truncate BEFORE TRUNCATE ON bitcoin_deposit_reconciliation_runs FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS bitcoin_deposit_reconciliation_evidence_open ON bitcoin_deposit_reconciliation_evidence;
CREATE TRIGGER bitcoin_deposit_reconciliation_evidence_open BEFORE INSERT ON bitcoin_deposit_reconciliation_evidence FOR EACH ROW EXECUTE FUNCTION bitcoin_deposit_reconciliation_open();
DROP TRIGGER IF EXISTS bitcoin_deposit_reconciliation_issues_open ON bitcoin_deposit_reconciliation_issues;
CREATE TRIGGER bitcoin_deposit_reconciliation_issues_open BEFORE INSERT ON bitcoin_deposit_reconciliation_issues FOR EACH ROW EXECUTE FUNCTION bitcoin_deposit_reconciliation_open();
DROP TRIGGER IF EXISTS bitcoin_deposit_reconciliation_evidence_immutable ON bitcoin_deposit_reconciliation_evidence;
CREATE TRIGGER bitcoin_deposit_reconciliation_evidence_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_deposit_reconciliation_evidence FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS bitcoin_deposit_reconciliation_issues_immutable ON bitcoin_deposit_reconciliation_issues;
CREATE TRIGGER bitcoin_deposit_reconciliation_issues_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_deposit_reconciliation_issues FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 설정 검증을 우회한 직접 삽입도 DB에 결합한 체인과 일치해야 한다. 결합 전에는 새 체인 사실을 받지 않는다.
CREATE OR REPLACE FUNCTION bitcoin_fact_require_network() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bound varchar;
BEGIN
 SELECT network INTO bound FROM bitcoin_network_identity WHERE singleton=1 FOR SHARE;
 IF NOT FOUND OR NEW.network<>bound THEN RAISE EXCEPTION '비트코인 원본의 네트워크가 플랫폼 DB와 다릅니다'; END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_address_network ON bitcoin_deposit_addresses;
CREATE TRIGGER bitcoin_address_network BEFORE INSERT ON bitcoin_deposit_addresses FOR EACH ROW EXECUTE FUNCTION bitcoin_fact_require_network();
DROP TRIGGER IF EXISTS bitcoin_observation_network ON bitcoin_transaction_observations;
CREATE TRIGGER bitcoin_observation_network BEFORE INSERT ON bitcoin_transaction_observations FOR EACH ROW EXECUTE FUNCTION bitcoin_fact_require_network();
DROP TRIGGER IF EXISTS bitcoin_registry_network ON bitcoin_scan_registries;
CREATE TRIGGER bitcoin_registry_network BEFORE INSERT ON bitcoin_scan_registries FOR EACH ROW EXECUTE FUNCTION bitcoin_fact_require_network();
DROP TRIGGER IF EXISTS bitcoin_scan_network ON bitcoin_block_scans;
CREATE TRIGGER bitcoin_scan_network BEFORE INSERT ON bitcoin_block_scans FOR EACH ROW EXECUTE FUNCTION bitcoin_fact_require_network();
DROP TRIGGER IF EXISTS bitcoin_receipt_network ON bitcoin_deposit_receipts;
CREATE TRIGGER bitcoin_receipt_network BEFORE INSERT ON bitcoin_deposit_receipts FOR EACH ROW EXECUTE FUNCTION bitcoin_fact_require_network();

-- 대사의 현재 근거는 벽시계나 완료 순서가 아니라 실제로 확정한 시작 순번으로 고른다.
-- 과거 실행을 추측해 채우지 않는다. 새 검증부터 등록하며 예전 실행 원본은 그대로 보존한다.
CREATE TABLE IF NOT EXISTS bitcoin_deposit_reconciliation_starts (
 position bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 run_id uuid NOT NULL UNIQUE REFERENCES bitcoin_deposit_reconciliation_runs(id),
 receipt_id uuid NOT NULL REFERENCES bitcoin_deposit_receipts(id),
 registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE INDEX IF NOT EXISTS bitcoin_reconciliation_start_receipt ON bitcoin_deposit_reconciliation_starts(receipt_id,position DESC);
CREATE OR REPLACE FUNCTION bitcoin_reconciliation_start_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 -- 실행 생성 트리거에서만 등록한다. 호출자가 옛 실행을 새 시작으로 직접 포장하지 못하게 한다.
 IF pg_trigger_depth()<2 OR NEW.created_txid<>txid_current() OR NEW.registered_at>clock_timestamp()
  OR NOT EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_runs r WHERE r.id=NEW.run_id AND r.receipt_id=NEW.receipt_id AND r.status='RUNNING') THEN
  RAISE EXCEPTION '대사 시작 순번은 새 실행 생성과 같은 트랜잭션에서만 기록합니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_reconciliation_start_binding_check ON bitcoin_deposit_reconciliation_starts;
CREATE TRIGGER bitcoin_reconciliation_start_binding_check BEFORE INSERT ON bitcoin_deposit_reconciliation_starts FOR EACH ROW EXECUTE FUNCTION bitcoin_reconciliation_start_binding();
CREATE OR REPLACE FUNCTION bitcoin_reconciliation_register_start() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO bitcoin_deposit_reconciliation_starts(run_id,receipt_id) VALUES(NEW.id,NEW.receipt_id);RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_reconciliation_register_start_check ON bitcoin_deposit_reconciliation_runs;
CREATE TRIGGER bitcoin_reconciliation_register_start_check AFTER INSERT ON bitcoin_deposit_reconciliation_runs FOR EACH ROW EXECUTE FUNCTION bitcoin_reconciliation_register_start();
CREATE OR REPLACE FUNCTION bitcoin_reconciliation_start_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NULL AND a.action='BTC_DEPOSIT_RECONCILIATION_STARTED'
  AND a.target='bitcoin-reconciliation:'||NEW.run_id::text AND a.outcome='SUCCESS' AND a.detail='RECEIPT:'||NEW.receipt_id::text
  AND a.occurred_at>=NEW.registered_at AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '대사 시작 순번에는 해당 실행의 필수 감사가 필요합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_reconciliation_start_audit_check ON bitcoin_deposit_reconciliation_starts;
CREATE CONSTRAINT TRIGGER bitcoin_reconciliation_start_audit_check AFTER INSERT ON bitcoin_deposit_reconciliation_starts
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_reconciliation_start_audit();
CREATE OR REPLACE FUNCTION bitcoin_reconciliation_finish_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NULL AND a.action='BTC_DEPOSIT_RECONCILIATION_FINISHED'
  AND a.target='bitcoin-reconciliation:'||NEW.id::text AND a.outcome=CASE WHEN NEW.status='MATCHED' THEN 'SUCCESS' ELSE 'DENIED' END
  AND a.detail='STATUS:'||NEW.status||':ISSUES:'||(SELECT count(*) FROM bitcoin_deposit_reconciliation_issues i WHERE i.run_id=NEW.id)::text
  AND a.occurred_at>=NEW.completed_at AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '대사 완료에는 실제 결과와 차이 수에 결합한 필수 감사가 필요합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_reconciliation_finish_audit_check ON bitcoin_deposit_reconciliation_runs;
CREATE CONSTRAINT TRIGGER bitcoin_reconciliation_finish_audit_check AFTER UPDATE ON bitcoin_deposit_reconciliation_runs
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.status<>'RUNNING') EXECUTE FUNCTION bitcoin_reconciliation_finish_audit();
DROP TRIGGER IF EXISTS bitcoin_reconciliation_starts_immutable ON bitcoin_deposit_reconciliation_starts;
CREATE TRIGGER bitcoin_reconciliation_starts_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_deposit_reconciliation_starts
 FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
-- 최신 시작이 실행 중이거나 실패해도 이전 성공을 선택하지 않는다. 재시작해도 같은 근거를 읽는다.
CREATE OR REPLACE VIEW bitcoin_current_deposit_reconciliation AS
 SELECT DISTINCT ON(s.receipt_id) r.*,s.position start_position,s.registered_at
 FROM bitcoin_deposit_reconciliation_starts s JOIN bitcoin_deposit_reconciliation_runs r ON r.id=s.run_id
 ORDER BY s.receipt_id,s.position DESC;
