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
