-- 가격은 공개 참고자료이며 고객/자산의 금융 승인 원장이 아니다. 새 조회 시작과 원본·결과를 보존한다.
CREATE TABLE IF NOT EXISTS btc_krw_price_attempts (
 id bigserial PRIMARY KEY,started_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS btc_krw_price_heads (
 singleton boolean PRIMARY KEY CHECK(singleton=true),attempt_id bigint NOT NULL UNIQUE REFERENCES btc_krw_price_attempts(id)
);
CREATE TABLE IF NOT EXISTS btc_krw_price_results (
 attempt_id bigint PRIMARY KEY REFERENCES btc_krw_price_attempts(id),status varchar(8) NOT NULL CHECK(status IN ('SUCCESS','FAILED')),
 failure_code varchar(24),price numeric,trade_at timestamptz,provider_at timestamptz,read_started_at timestamptz,read_finished_at timestamptz,
 payload text,payload_hash varchar(64),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((status='SUCCESS' AND failure_code IS NULL AND price IS NOT NULL AND price>0 AND price<1e30 AND scale(price)<=8
  AND trade_at IS NOT NULL AND provider_at IS NOT NULL AND read_started_at IS NOT NULL AND read_finished_at IS NOT NULL
  AND payload IS NOT NULL AND octet_length(payload)<=65536 AND payload_hash IS NOT NULL AND payload_hash=encode(sha256(convert_to(payload,'UTF8')),'hex'))
 OR(status='FAILED' AND failure_code IS NOT NULL AND failure_code IN ('INVALID_SOURCE','PROVIDER_UNAVAILABLE','INTERNAL_FAILURE') AND price IS NULL
  AND trade_at IS NULL AND provider_at IS NULL AND read_started_at IS NULL AND read_finished_at IS NULL AND payload IS NULL AND payload_hash IS NULL)),
 CHECK(status='FAILED' OR COALESCE(jsonb_typeof(payload::jsonb)='array' AND jsonb_array_length(payload::jsonb)=1
  AND payload::jsonb->0->>'market'='KRW-BTC' AND jsonb_typeof(payload::jsonb->0->'trade_price')='number'
  AND(payload::jsonb->0->>'trade_price')::numeric=price
  AND(payload::jsonb->0->>'trade_timestamp')::numeric=extract(epoch FROM trade_at)*1000
  AND(payload::jsonb->0->>'timestamp')::numeric=extract(epoch FROM provider_at)*1000,false))
);
CREATE OR REPLACE FUNCTION btc_krw_price_head_forward() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM btc_krw_price_attempts a WHERE a.id=NEW.attempt_id AND a.created_txid=txid_current() AND a.started_at<=clock_timestamp())
  OR(TG_OP='UPDATE' AND(NEW.singleton<>OLD.singleton OR NEW.attempt_id<=OLD.attempt_id)) THEN
  RAISE EXCEPTION '참고가격 현재 위치는 같은 트랜잭션의 새 조회로만 전진합니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS btc_krw_price_head_forward_check ON btc_krw_price_heads;
CREATE TRIGGER btc_krw_price_head_forward_check BEFORE INSERT OR UPDATE ON btc_krw_price_heads FOR EACH ROW EXECUTE FUNCTION btc_krw_price_head_forward();
CREATE OR REPLACE FUNCTION btc_krw_price_result_time() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original timestamptz;
BEGIN
 SELECT started_at INTO original FROM btc_krw_price_attempts WHERE id=NEW.attempt_id;
 IF NOT FOUND OR NEW.recorded_at<original OR NEW.recorded_at>clock_timestamp()
  OR(NEW.status='SUCCESS' AND(NEW.read_started_at<original-interval '10 seconds' OR NEW.read_finished_at<NEW.read_started_at
   OR NEW.read_finished_at>clock_timestamp()+interval '10 seconds' OR NEW.trade_at>NEW.provider_at
   OR NEW.provider_at>NEW.read_finished_at+interval '10 seconds' OR NEW.trade_at<NEW.read_started_at-interval '2 minutes'))
 THEN RAISE EXCEPTION '참고가격의 조회/체결 시각이 원본과 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS btc_krw_price_result_time_check ON btc_krw_price_results;
CREATE TRIGGER btc_krw_price_result_time_check BEFORE INSERT ON btc_krw_price_results FOR EACH ROW EXECUTE FUNCTION btc_krw_price_result_time();
DROP TRIGGER IF EXISTS btc_krw_price_attempt_immutable ON btc_krw_price_attempts;
CREATE TRIGGER btc_krw_price_attempt_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON btc_krw_price_attempts FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS btc_krw_price_result_immutable ON btc_krw_price_results;
CREATE TRIGGER btc_krw_price_result_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON btc_krw_price_results FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS btc_krw_price_head_no_delete ON btc_krw_price_heads;
CREATE TRIGGER btc_krw_price_head_no_delete BEFORE DELETE OR TRUNCATE ON btc_krw_price_heads FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
