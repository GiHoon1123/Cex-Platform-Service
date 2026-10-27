CREATE TABLE IF NOT EXISTS ethereum_withdrawal_quotes (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),user_version bigint NOT NULL,session_id uuid NOT NULL,
 request_key uuid NOT NULL,input_hash varchar(64) NOT NULL,address varchar(42) NOT NULL,
 network varchar(80) NOT NULL,amount numeric(30,9) NOT NULL CHECK(amount>0),fee numeric(30,9) NOT NULL CHECK(fee>0),
 total numeric(30,9) NOT NULL CHECK(total=amount+fee),policy varchar(64) NOT NULL CHECK(policy='ETH_WITHDRAWAL_FEE_1BPS_V1'),
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL,
 UNIQUE(user_id,request_key),CHECK(address ~ '^0x[0-9a-f]{40}$'),CHECK(input_hash ~ '^[0-9a-f]{64}$'),CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
 CHECK(fee=ceil(amount*0.0001*1000000000)/1000000000),CHECK(expires_at=created_at+interval '2 minutes')
);
CREATE TABLE IF NOT EXISTS ethereum_withdrawal_challenges (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,quote_id uuid NOT NULL REFERENCES ethereum_withdrawal_quotes(id),
 quote_hash varchar(64) NOT NULL,scope_mac varchar(64) NOT NULL,key_id varchar(64) NOT NULL,options text NOT NULL,
 expires_at timestamptz NOT NULL,consumed boolean NOT NULL DEFAULT false
);
CREATE TABLE IF NOT EXISTS ethereum_withdrawals (
 id uuid PRIMARY KEY REFERENCES ethereum_withdrawal_quotes(id),user_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 network varchar(80) NOT NULL,asset varchar(8) NOT NULL CHECK(asset='ETH'),address varchar(42) NOT NULL,
 amount numeric(30,9) NOT NULL,fee numeric(30,9) NOT NULL,total numeric(30,9) NOT NULL CHECK(total=amount+fee),
 quote_hash varchar(64) NOT NULL,operation_key varchar(160) NOT NULL UNIQUE REFERENCES ledger_transactions(operation_key),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS ethereum_withdrawal_authorizations (
 withdrawal_id uuid PRIMARY KEY REFERENCES ethereum_withdrawals(id) DEFERRABLE INITIALLY DEFERRED,user_id bigint NOT NULL REFERENCES users(id),
 session_id uuid NOT NULL,quote_hash varchar(64) NOT NULL,challenge_id uuid NOT NULL UNIQUE REFERENCES ethereum_withdrawal_challenges(id),
 credential_fingerprint varchar(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS ethereum_withdrawal_dispatch_attempts (
 id uuid PRIMARY KEY,withdrawal_id uuid NOT NULL REFERENCES ethereum_withdrawals(id),position bigserial UNIQUE,
 started_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS ethereum_withdrawal_dispatch_results (
 attempt_id uuid PRIMARY KEY REFERENCES ethereum_withdrawal_dispatch_attempts(id),outcome varchar(32) NOT NULL,
 completed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS ethereum_withdrawal_reservation_events (
 withdrawal_id uuid PRIMARY KEY REFERENCES ethereum_withdrawals(id),event_id varchar(120) NOT NULL UNIQUE REFERENCES engine_event_receipts(event_id),
 engine_id uuid NOT NULL,event_type varchar(32) NOT NULL CHECK(event_type IN('withdrawal_reserved','withdrawal_rejected')),
 event_time text NOT NULL,payload_hash varchar(64) NOT NULL,operation_key varchar(160) NOT NULL UNIQUE REFERENCES ledger_transactions(operation_key)
);

CREATE OR REPLACE FUNCTION ethereum_withdrawal_history_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'ethereum withdrawal history is append-only'; END $$;
DO $$ DECLARE relation text; BEGIN
 FOREACH relation IN ARRAY ARRAY['ethereum_withdrawal_quotes','ethereum_withdrawals','ethereum_withdrawal_authorizations','ethereum_withdrawal_dispatch_attempts','ethereum_withdrawal_dispatch_results','ethereum_withdrawal_reservation_events'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS %I_immutable ON %I',relation,relation);
  EXECUTE format('CREATE TRIGGER %I_immutable BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ethereum_withdrawal_history_immutable()',relation,relation);
 END LOOP;
END $$;
