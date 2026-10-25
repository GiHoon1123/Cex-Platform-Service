-- 수량의 보류 인식과 당시 평가/미평가 사실을 분리한다. 사후 가격으로 과거 부재를 채우지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_deposit_valuations (
 receipt_id uuid PRIMARY KEY REFERENCES bitcoin_pending_deposits(receipt_id),reference_at timestamptz NOT NULL,
 policy_id varchar(40) NOT NULL CHECK(policy_id='BTC_PENDING_KRW_REFERENCE_V1'),
 status varchar(12) NOT NULL CHECK(status IN ('VALUED','UNVALUED')),
 reason varchar(40) NOT NULL CHECK(reason IN ('PRICE_AVAILABLE','PRICE_UNAVAILABLE','PRICE_NOT_USABLE_AT_REFERENCE')),
 price_attempt_id bigint REFERENCES btc_krw_price_results(attempt_id),krw_value numeric,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK((status='VALUED' AND reason='PRICE_AVAILABLE' AND price_attempt_id IS NOT NULL AND krw_value IS NOT NULL AND krw_value>0)
  OR(status='UNVALUED' AND krw_value IS NULL AND((reason='PRICE_UNAVAILABLE' AND price_attempt_id IS NULL)
   OR(reason='PRICE_NOT_USABLE_AT_REFERENCE' AND price_attempt_id IS NOT NULL))))
);
CREATE OR REPLACE FUNCTION bitcoin_deposit_valuation_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE deposit record;price record;usable boolean;
BEGIN
 SELECT p.recorded_at,r.amount,l.created_txid INTO deposit FROM bitcoin_pending_deposits p
  JOIN bitcoin_deposit_receipts r ON r.id=p.receipt_id JOIN ledger_transactions l ON l.operation_key=p.operation_key AND l.kind='BTC_DEPOSIT_PENDING'
  WHERE p.receipt_id=NEW.receipt_id;
 IF NOT FOUND OR NEW.created_txid<>txid_current() OR deposit.created_txid<>txid_current() OR NEW.reference_at<>deposit.recorded_at
  OR NEW.recorded_at>clock_timestamp() OR NEW.recorded_at<NEW.reference_at-interval '10 seconds' THEN
  RAISE EXCEPTION '입금 평가는 같은 트랜잭션의 보류 인식 시점에만 기록할 수 있습니다';END IF;
 IF NEW.price_attempt_id IS NOT NULL THEN
  SELECT a.started_at,r.* INTO price FROM btc_krw_price_attempts a JOIN btc_krw_price_results r ON r.attempt_id=a.id
   JOIN btc_krw_price_heads h ON h.attempt_id=a.id WHERE a.id=NEW.price_attempt_id AND r.status='SUCCESS' FOR SHARE OF h;
  IF NOT FOUND THEN RAISE EXCEPTION '확인한 현재 가격 원본이 평가와 다릅니다';END IF;
  usable:=price.started_at<=NEW.reference_at AND price.recorded_at<=NEW.reference_at AND price.started_at>=NEW.reference_at-interval '2 minutes'
   AND price.trade_at>=NEW.reference_at-interval '2 minutes' AND price.provider_at<=NEW.reference_at+interval '10 seconds';
  IF(NEW.status='VALUED' AND(NOT usable OR NEW.krw_value IS DISTINCT FROM deposit.amount*price.price))
   OR(NEW.status='UNVALUED' AND usable) THEN RAISE EXCEPTION '입금 환산액과 당시 가격 원본이 다릅니다';END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_deposit_valuation_binding_check ON bitcoin_deposit_valuations;
CREATE TRIGGER bitcoin_deposit_valuation_binding_check BEFORE INSERT ON bitcoin_deposit_valuations FOR EACH ROW EXECUTE FUNCTION bitcoin_deposit_valuation_binding();
CREATE OR REPLACE FUNCTION bitcoin_pending_valuation_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM bitcoin_deposit_valuations v WHERE v.receipt_id=NEW.receipt_id) THEN
  RAISE EXCEPTION '새 보류 입금에는 같은 트랜잭션의 평가 또는 미평가 기록이 필요합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_pending_valuation_complete_check ON bitcoin_pending_deposits;
CREATE CONSTRAINT TRIGGER bitcoin_pending_valuation_complete_check AFTER INSERT ON bitcoin_pending_deposits
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_pending_valuation_complete();
DROP TRIGGER IF EXISTS bitcoin_deposit_valuation_immutable ON bitcoin_deposit_valuations;
CREATE TRIGGER bitcoin_deposit_valuation_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_deposit_valuations FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
