-- 견적은 고객의 지급 승인이 아니다. 정규 목적지와 서버가 계산한 요율/금액을 변경 없이 보존한다.
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_quotes (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),user_version bigint NOT NULL,
 session_id uuid NOT NULL,request_key uuid NOT NULL,input_hash varchar(64) NOT NULL CHECK(input_hash ~ '^[0-9a-f]{64}$'),
 network varchar(32) NOT NULL CHECK(network IN('bitcoin-regtest','bitcoin-mainnet')),
 amount numeric(16,8) NOT NULL CHECK(amount>0),fee numeric(16,8) NOT NULL CHECK(fee>0),total numeric(16,8) NOT NULL CHECK(total<=21000000 AND total=amount+fee),
 policy varchar(64) NOT NULL CHECK(policy='BTC_WITHDRAWAL_FEE_1BPS_V1'),
 payload text NOT NULL CHECK(length(payload)<=4096),payload_hash varchar(64) NOT NULL CHECK(payload_hash=encode(sha256(convert_to(payload,'UTF8')),'hex')),
 created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL CHECK(expires_at=created_at+interval '2 minutes'),
 created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(user_id,request_key),
 CHECK(fee=ceil(amount*0.0001*100000000)/100000000)
);
CREATE INDEX IF NOT EXISTS bitcoin_withdrawal_quote_owner ON bitcoin_withdrawal_quotes(user_id,created_at,id);
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_quote_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p jsonb;
BEGIN
 PERFORM id FROM users WHERE id=NEW.user_id FOR UPDATE;
 p:=NEW.payload::jsonb;
 IF jsonb_typeof(p) IS DISTINCT FROM 'object' OR (SELECT count(*) FROM jsonb_object_keys(p))<>15
  OR EXISTS(SELECT 1 FROM unnest(ARRAY['id','customerId','network','asset','address','scriptPubKey','scriptType','amount','feeRate','fee','total','policy','createdAt','expiresAt']) f WHERE jsonb_typeof(p->f) IS DISTINCT FROM 'string')
  OR jsonb_typeof(p->'customerVersion') IS DISTINCT FROM 'number' THEN
  RAISE EXCEPTION '출금 견적 본문의 필드와 실제 타입이 다릅니다';END IF;
 IF COALESCE(jsonb_typeof(p)<>'object' OR NEW.created_txid<>txid_current() OR NEW.created_at<transaction_timestamp() OR NEW.created_at>clock_timestamp()
  OR NOT EXISTS(SELECT 1 FROM users WHERE id=NEW.user_id AND version=NEW.user_version AND role='CUSTOMER' AND enabled)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_network_identity WHERE singleton=1 AND network=NEW.network)
  OR (p->>'id')::uuid<>NEW.id OR (p->>'customerId')::bigint<>NEW.user_id OR (p->>'customerVersion')::bigint<>NEW.user_version
  OR p->>'network'<>NEW.network OR p->>'asset'<>'BTC' OR (p->>'amount')::numeric<>NEW.amount OR (p->>'fee')::numeric<>NEW.fee
  OR (p->>'total')::numeric<>NEW.total OR p->>'policy'<>NEW.policy OR (p->>'feeRate')::numeric<>0.0001
  OR (p->>'createdAt')::timestamptz<>NEW.created_at OR (p->>'expiresAt')::timestamptz<>NEW.expires_at
  OR length(p->>'address') NOT BETWEEN 1 AND 100 OR p->>'scriptPubKey' !~ '^[0-9a-f]{2,128}$'
  OR length(p->>'scriptPubKey')%2<>0 OR p->>'scriptType' NOT IN('pubkeyhash','scripthash','witness_v0_keyhash','witness_v0_scripthash','witness_v1_taproot'),true) THEN
  RAISE EXCEPTION '출금 견적과 현재 고객·네트워크·정확한 요율/금액 원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_quote_binding_check ON bitcoin_withdrawal_quotes;
CREATE TRIGGER bitcoin_withdrawal_quote_binding_check BEFORE INSERT ON bitcoin_withdrawal_quotes FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_quote_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_quote_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM security_audit WHERE actor_id=NEW.user_id AND action='BTC_WITHDRAWAL_QUOTED' AND target='bitcoin-withdrawal-quote:'||NEW.id::text
  AND outcome='SUCCESS' AND detail=NEW.payload_hash AND occurred_at>=NEW.created_at AND length(source_address)>0) THEN
  RAISE EXCEPTION '출금 견적 확정에는 같은 내용의 필수 감사가 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_quote_audit_check ON bitcoin_withdrawal_quotes;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_quote_audit_check AFTER INSERT ON bitcoin_withdrawal_quotes DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_quote_audit();
DROP TRIGGER IF EXISTS bitcoin_withdrawal_quote_preserve ON bitcoin_withdrawal_quotes;
CREATE TRIGGER bitcoin_withdrawal_quote_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_withdrawal_quotes FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
