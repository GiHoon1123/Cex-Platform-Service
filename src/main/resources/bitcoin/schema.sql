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
