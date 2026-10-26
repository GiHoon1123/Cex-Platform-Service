-- 가격/환산은 고객 신청 시점의 역사적 사실이다. 나중의 현재가나 고객 총액으로 바꿔 쓰지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_valuations (
 withdrawal_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawals(id),user_id bigint NOT NULL REFERENCES users(id),
 policy varchar(64) NOT NULL CHECK(policy='BTC_WITHDRAWAL_REQUEST_KRW_REFERENCE_V1'),
 reference_at timestamptz NOT NULL,price_attempt_id bigint NOT NULL REFERENCES btc_krw_price_attempts(id),
 source_hash varchar(64) NOT NULL CHECK(source_hash ~ '^[0-9a-f]{64}$'),
 amount numeric(16,8) NOT NULL CHECK(amount>0),price numeric(38,8) NOT NULL CHECK(price>0),
 krw_value numeric(56,16) NOT NULL CHECK(krw_value=amount*price),
 created_txid bigint NOT NULL DEFAULT txid_current()
);
DROP TRIGGER IF EXISTS financial_preserve ON bitcoin_withdrawal_valuations;
CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_withdrawal_valuations FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_valuation_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NOT EXISTS(
  SELECT 1 FROM bitcoin_withdrawals w JOIN btc_krw_price_results p ON p.attempt_id=NEW.price_attempt_id
   JOIN btc_krw_price_attempts a ON a.id=p.attempt_id JOIN btc_krw_price_heads h ON h.attempt_id=a.id AND h.singleton=true
   WHERE w.id=NEW.withdrawal_id AND w.user_id=NEW.user_id AND w.created_txid=txid_current()
    AND w.amount=NEW.amount AND w.created_at=NEW.reference_at AND p.status='SUCCESS'
    AND p.price=NEW.price AND p.payload_hash=NEW.source_hash
    AND a.started_at<=NEW.reference_at AND a.started_at>=NEW.reference_at-interval '2 minutes'
    AND p.trade_at>=NEW.reference_at-interval '2 minutes' AND p.trade_at<=p.provider_at
    AND p.provider_at<=NEW.reference_at AND p.read_started_at<=p.read_finished_at
    AND p.read_finished_at<=NEW.reference_at AND p.recorded_at<=NEW.reference_at
 ) THEN RAISE EXCEPTION '출금 신청 시점의 순 지급액·현재 가격 원본·정확한 참고평가를 확인하세요';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_valuation_binding_check ON bitcoin_withdrawal_valuations;
CREATE TRIGGER bitcoin_withdrawal_valuation_binding_check BEFORE INSERT ON bitcoin_withdrawal_valuations FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_valuation_binding();
CREATE OR REPLACE FUNCTION bitcoin_withdrawal_valuation_commit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source record;
BEGIN
 SELECT * INTO source FROM bitcoin_withdrawal_valuations WHERE withdrawal_id=NEW.id;
 IF NOT FOUND OR source.created_txid<>txid_current() OR NOT EXISTS(SELECT 1 FROM security_audit
  WHERE actor_id=NEW.user_id AND action='BTC_WITHDRAWAL_VALUED' AND target='bitcoin-withdrawal:'||NEW.id::text
   AND outcome='SUCCESS' AND detail='PRICE:'||source.price_attempt_id::text||':HASH:'||source.source_hash) THEN
  RAISE EXCEPTION '출금 원본·신청 시점 참고평가·필수 감사를 같은 커밋에 보존해야 합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_withdrawal_valuation_commit_check ON bitcoin_withdrawals;
CREATE CONSTRAINT TRIGGER bitcoin_withdrawal_valuation_commit_check AFTER INSERT ON bitcoin_withdrawals DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_withdrawal_valuation_commit();
