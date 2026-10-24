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
