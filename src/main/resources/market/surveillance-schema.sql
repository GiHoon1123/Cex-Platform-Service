-- 엔진 결과를 반영한 트랜잭션 안에서 감시 입력을 함께 고정한다. 감시기는 엔진 코어나 Kafka 원문을 다시 해석하지 않는다.
CREATE TABLE IF NOT EXISTS market_activity_events (
 id bigserial PRIMARY KEY, source_event_id varchar(120) NOT NULL UNIQUE REFERENCES engine_event_receipts(event_id),
 event_kind varchar(16) NOT NULL CHECK(event_kind IN ('TRADE','CANCEL')),
 event_at timestamptz NOT NULL, user_id bigint REFERENCES users(id), buyer_id bigint REFERENCES users(id), seller_id bigint REFERENCES users(id),
 order_id bigint REFERENCES orders(id), buy_order_id bigint REFERENCES orders(id), sell_order_id bigint REFERENCES orders(id),
 base_asset varchar(20) NOT NULL, quote_asset varchar(20) NOT NULL, side varchar(8),
 price numeric(30,9), original_amount numeric(30,9), filled_amount numeric(30,9),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK((event_kind='TRADE' AND user_id IS NULL AND order_id IS NULL AND buyer_id IS NOT NULL AND seller_id IS NOT NULL
   AND buy_order_id IS NOT NULL AND sell_order_id IS NOT NULL AND side IS NULL AND price>0 AND original_amount IS NULL AND filled_amount>0)
  OR(event_kind='CANCEL' AND user_id IS NOT NULL AND order_id IS NOT NULL AND buyer_id IS NULL AND seller_id IS NULL
   AND buy_order_id IS NULL AND sell_order_id IS NULL AND side IN ('buy','sell') AND price>0 AND original_amount>0 AND filled_amount>=0 AND filled_amount<original_amount))
);
CREATE INDEX IF NOT EXISTS market_activity_cancel_pattern ON market_activity_events(user_id,base_asset,quote_asset,side,event_at,id) WHERE event_kind='CANCEL';
CREATE INDEX IF NOT EXISTS market_activity_trade_market ON market_activity_events(base_asset,quote_asset,event_at,id) WHERE event_kind='TRADE';

CREATE OR REPLACE FUNCTION market_activity_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NOT EXISTS(SELECT 1 FROM engine_event_receipts WHERE event_id=NEW.source_event_id) THEN
  RAISE EXCEPTION '시장 감시 입력은 같은 금융 결과 트랜잭션에 묶여야 합니다';END IF;
 IF NEW.event_kind='TRADE' AND NOT EXISTS(SELECT 1 FROM trades t WHERE t.engine_event_id=NEW.source_event_id
  AND t.buy_order_id=NEW.buy_order_id AND t.sell_order_id=NEW.sell_order_id AND t.buyer_id=NEW.buyer_id AND t.seller_id=NEW.seller_id
  AND t.base_mint=NEW.base_asset AND t.quote_mint=NEW.quote_asset AND t.price=NEW.price AND t.amount=NEW.filled_amount
  AND t.created_at=NEW.event_at AT TIME ZONE 'Asia/Seoul') THEN RAISE EXCEPTION '감시 체결 입력이 확정 체결과 다릅니다';END IF;
 IF NEW.event_kind='CANCEL' AND NOT EXISTS(SELECT 1 FROM orders o WHERE o.id=NEW.order_id AND o.user_id=NEW.user_id
  AND o.status='cancelled' AND o.order_type=NEW.side AND o.order_side='limit' AND o.base_mint=NEW.base_asset
  AND o.quote_mint=NEW.quote_asset AND o.price=NEW.price AND o.amount=NEW.original_amount AND o.filled_amount=NEW.filled_amount)
  THEN RAISE EXCEPTION '감시 취소 입력이 확정 주문과 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_activity_binding_check ON market_activity_events;
CREATE TRIGGER market_activity_binding_check BEFORE INSERT ON market_activity_events FOR EACH ROW EXECUTE FUNCTION market_activity_binding();

CREATE TABLE IF NOT EXISTS market_surveillance_state (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), observed_event bigint NOT NULL DEFAULT 0 CHECK(observed_event>=0),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO market_surveillance_state(singleton) VALUES(true) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS market_surveillance_assessments (
 id uuid PRIMARY KEY, input_event bigint NOT NULL UNIQUE REFERENCES market_activity_events(id), policy_id varchar(40) NOT NULL,
 outcome varchar(24) NOT NULL CHECK(outcome IN ('NO_SIGNAL','REVIEW_REQUIRED')),
 reason varchar(40) NOT NULL CHECK(reason IN ('NO_SIGNAL_IN_THIS_POLICY','RAPID_UNFILLED_CANCELLATION','ABRUPT_EXECUTION_PRICE','SELF_MATCH_INVARIANT_BREACH')),
 source_document text NOT NULL CHECK(octet_length(source_document)<=262144), source_hash varchar(64) NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK(source_hash=encode(sha256(convert_to(source_document,'UTF8')),'hex')),
 CHECK((outcome='NO_SIGNAL' AND reason='NO_SIGNAL_IN_THIS_POLICY') OR(outcome='REVIEW_REQUIRED' AND reason<>'NO_SIGNAL_IN_THIS_POLICY'))
);
CREATE TABLE IF NOT EXISTS market_surveillance_signals (
 id uuid PRIMARY KEY, assessment_id uuid NOT NULL UNIQUE REFERENCES market_surveillance_assessments(id),
 status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','UNDER_REVIEW','CLOSED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS market_surveillance_signal_queue ON market_surveillance_signals(status,created_at,id);

CREATE OR REPLACE FUNCTION market_surveillance_assessment_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NOT EXISTS(SELECT 1 FROM market_activity_events WHERE id=NEW.input_event)
  OR jsonb_typeof(NEW.source_document::jsonb)<>'object' OR (NEW.source_document::jsonb->>'inputEvent')::bigint<>NEW.input_event THEN
  RAISE EXCEPTION '시장 감시 평가 근거가 입력과 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_surveillance_assessment_binding_check ON market_surveillance_assessments;
CREATE TRIGGER market_surveillance_assessment_binding_check BEFORE INSERT ON market_surveillance_assessments FOR EACH ROW EXECUTE FUNCTION market_surveillance_assessment_binding();
CREATE OR REPLACE FUNCTION market_surveillance_state_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.singleton<>OLD.singleton OR NEW.observed_event<OLD.observed_event THEN RAISE EXCEPTION '시장 감시 위치를 되돌릴 수 없습니다';END IF;
 IF NEW.observed_event<>OLD.observed_event AND (NEW.observed_event IS DISTINCT FROM
   (SELECT min(id) FROM market_activity_events WHERE id>OLD.observed_event)
   OR NOT EXISTS(SELECT 1 FROM market_surveillance_assessments WHERE input_event=NEW.observed_event AND created_txid=txid_current()))
  THEN RAISE EXCEPTION '다음 입력의 확정 평가 없이 감시 위치를 옮길 수 없습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_surveillance_state_binding_check ON market_surveillance_state;
CREATE TRIGGER market_surveillance_state_binding_check BEFORE UPDATE ON market_surveillance_state FOR EACH ROW EXECUTE FUNCTION market_surveillance_state_binding();

CREATE OR REPLACE FUNCTION market_surveillance_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION '시장 감시 근거는 변경하거나 삭제할 수 없습니다';END $$;
DROP TRIGGER IF EXISTS market_activity_immutable ON market_activity_events;
CREATE TRIGGER market_activity_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON market_activity_events FOR EACH STATEMENT EXECUTE FUNCTION market_surveillance_immutable();
DROP TRIGGER IF EXISTS market_surveillance_assessment_immutable ON market_surveillance_assessments;
CREATE TRIGGER market_surveillance_assessment_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON market_surveillance_assessments FOR EACH STATEMENT EXECUTE FUNCTION market_surveillance_immutable();
DROP TRIGGER IF EXISTS market_surveillance_signal_preserve ON market_surveillance_signals;
CREATE TRIGGER market_surveillance_signal_preserve BEFORE DELETE OR TRUNCATE ON market_surveillance_signals FOR EACH STATEMENT EXECUTE FUNCTION market_surveillance_immutable();
