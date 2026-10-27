CREATE TABLE IF NOT EXISTS ethereum_network_identity (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), chain_id numeric(78,0) NOT NULL,
 genesis_hash varchar(66) NOT NULL, bound_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS ethereum_deposit_addresses (
 id uuid PRIMARY KEY, user_id bigint UNIQUE REFERENCES users(id), address varchar(42) NOT NULL UNIQUE,
 network varchar(80) NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(), claimed_at timestamptz
);
CREATE TABLE IF NOT EXISTS ethereum_scan_checkpoint (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), block_number numeric(78,0) NOT NULL,
 block_hash varchar(66) NOT NULL, updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS ethereum_deposits (
 id uuid PRIMARY KEY, user_id bigint NOT NULL REFERENCES users(id), address_id uuid NOT NULL REFERENCES ethereum_deposit_addresses(id),
 tx_hash varchar(66) NOT NULL UNIQUE, block_number numeric(78,0) NOT NULL, block_hash varchar(66) NOT NULL,
 sender varchar(42) NOT NULL, amount numeric(30,18) NOT NULL CHECK(amount>0), state varchar(20) NOT NULL CHECK(state IN('CONFIRMED','CREDITING','CREDITED','HELD')),
 detected_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(block_hash,tx_hash)
);
CREATE TABLE IF NOT EXISTS ethereum_credit_authorizations (
 deposit_id uuid PRIMARY KEY REFERENCES ethereum_deposits(id), user_id bigint NOT NULL REFERENCES users(id),
 asset varchar(20) NOT NULL CHECK(asset='ETH'), amount numeric(30,18) NOT NULL CHECK(amount>0),
 authorized_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS ethereum_deposit_holds (
 deposit_id uuid PRIMARY KEY REFERENCES ethereum_deposits(id), reason varchar(80) NOT NULL,
 held_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS ethereum_credit_attempts (
 id uuid PRIMARY KEY, deposit_id uuid NOT NULL REFERENCES ethereum_deposits(id), position bigserial UNIQUE,
 started_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS ethereum_credit_results (
 attempt_id uuid PRIMARY KEY REFERENCES ethereum_credit_attempts(id), outcome varchar(30) NOT NULL,
 completed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS ethereum_credited_deposits (
 deposit_id uuid PRIMARY KEY REFERENCES ethereum_deposits(id), event_id varchar(120) NOT NULL UNIQUE,
 engine_id uuid NOT NULL, event_time varchar(64) NOT NULL, payload_hash varchar(64) NOT NULL,
 operation_key varchar(160) NOT NULL UNIQUE, credited_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE OR REPLACE FUNCTION ethereum_financial_history_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'ethereum financial history is append-only'; END $$;
DO $$ DECLARE relation text; BEGIN
 FOREACH relation IN ARRAY ARRAY['ethereum_network_identity','ethereum_deposits','ethereum_deposit_holds','ethereum_credit_authorizations','ethereum_credit_attempts','ethereum_credit_results','ethereum_credited_deposits'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS %I_immutable ON %I',relation,relation);
  EXECUTE format('CREATE TRIGGER %I_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ethereum_financial_history_immutable()',relation,relation);
 END LOOP;
END $$;
