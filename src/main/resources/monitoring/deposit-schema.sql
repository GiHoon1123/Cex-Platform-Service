-- 수탁 원장 반입의 실제 고객 항목 순번을 공통 금융 입력으로 쓴다. 현재 등록된 원본은 BTC 보류 인식뿐이다.
-- 벽시계가 뒤로 움직여도 새 원장 항목을 놓치지 않으며, 금융 원본과 당시 평가 사실은 복사해 바꾸지 않는다.
CREATE INDEX IF NOT EXISTS ledger_pending_monitoring_cursor ON ledger_entries(owner_id,id DESC) WHERE bucket='PENDING' AND delta>0;
CREATE OR REPLACE VIEW deposit_monitoring_inputs AS
 SELECT e.id input_cursor,p.operation_key,b.id receipt_id,b.user_id,b.asset,b.network,b.txid,p.recorded_at recognized_at,
  'KRW'::varchar currency,v.policy_id valuation_policy,v.status valuation_status,v.price_attempt_id,v.krw_value
 FROM bitcoin_pending_deposits p JOIN bitcoin_deposit_receipts b ON b.id=p.receipt_id
 JOIN ledger_transactions l ON l.operation_key=p.operation_key AND l.kind='BTC_DEPOSIT_PENDING'
 JOIN ledger_entries e ON e.operation_key=p.operation_key AND e.owner_id=b.user_id AND e.bucket='PENDING' AND e.asset=b.asset AND e.delta=b.amount AND e.delta>0
 LEFT JOIN bitcoin_deposit_valuations v ON v.receipt_id=b.id;
CREATE TABLE IF NOT EXISTS deposit_monitoring_assessments (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),input_cursor bigint NOT NULL REFERENCES ledger_entries(id),
 policy_id varchar(50) NOT NULL CHECK(policy_id='NATIVE_SMALL_REFERENCE_INFLOW_V1'),reference_at timestamptz NOT NULL,
 outcome varchar(24) NOT NULL CHECK(outcome IN ('NO_SIGNAL','REVIEW_REQUIRED','INCOMPLETE')),
 reason varchar(40) NOT NULL CHECK(reason IN ('NO_SIGNAL_IN_THIS_RULE','REPEATED_SMALL_REFERENCE_INFLOW','VALUATION_UNAVAILABLE',
  'NO_RECOGNIZED_INPUT','INPUT_LIMIT_EXCEEDED','INVALID_REFERENCE_SOURCE','CLOCK_DISCONTINUITY','OUTSIDE_REFERENCE_WINDOW')),
 small_transfers integer NOT NULL CHECK(small_transfers>=0),small_total_krw numeric NOT NULL CHECK(small_total_krw>=0),
 unknown_transfers integer NOT NULL CHECK(unknown_transfers>=0),source_count integer NOT NULL CHECK(source_count BETWEEN 1 AND 1001),
 source_document text NOT NULL CHECK(octet_length(source_document)<=1048576),source_hash varchar(64) NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 UNIQUE(user_id,policy_id,input_cursor),CHECK(source_hash=encode(sha256(convert_to(source_document,'UTF8')),'hex')),
 CHECK(source_count<=1000 OR(outcome='INCOMPLETE' AND reason='INPUT_LIMIT_EXCEEDED')),
 CHECK((outcome='NO_SIGNAL' AND reason='NO_SIGNAL_IN_THIS_RULE' AND unknown_transfers=0)
  OR(outcome='REVIEW_REQUIRED' AND reason='REPEATED_SMALL_REFERENCE_INFLOW' AND small_transfers>=5 AND small_total_krw>=1000000)
  OR(outcome='INCOMPLETE' AND reason NOT IN ('NO_SIGNAL_IN_THIS_RULE','REPEATED_SMALL_REFERENCE_INFLOW')))
);
CREATE TABLE IF NOT EXISTS deposit_monitoring_heads (
 user_id bigint PRIMARY KEY REFERENCES users(id),assessment_id uuid NOT NULL UNIQUE REFERENCES deposit_monitoring_assessments(id)
);
CREATE TABLE IF NOT EXISTS deposit_monitoring_jobs (
 user_id bigint PRIMARY KEY REFERENCES users(id),target_cursor bigint NOT NULL REFERENCES ledger_entries(id),
 observed_cursor bigint NOT NULL DEFAULT 0 CHECK(observed_cursor>=0),lease_id uuid,lease_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((lease_id IS NULL)=(lease_until IS NULL)),CHECK(target_cursor>=observed_cursor)
);
CREATE OR REPLACE FUNCTION deposit_monitoring_assessment_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE reference timestamptz;expected integer;supplied integer;
BEGIN
 SELECT recognized_at INTO reference FROM deposit_monitoring_inputs WHERE input_cursor=NEW.input_cursor AND user_id=NEW.user_id;
 IF NOT FOUND OR NEW.created_txid<>txid_current() OR NEW.reference_at<>reference OR NEW.recorded_at>clock_timestamp()
  OR jsonb_typeof(NEW.source_document::jsonb)<>'array' OR jsonb_array_length(NEW.source_document::jsonb)<>NEW.source_count THEN
  RAISE EXCEPTION '모니터링 기준과 수탁 원장 입력이 다릅니다';END IF;
 SELECT count(*) INTO expected FROM(SELECT 1 FROM deposit_monitoring_inputs WHERE user_id=NEW.user_id AND input_cursor<=NEW.input_cursor
  AND recognized_at>=reference-interval '24 hours' LIMIT 1001) bounded;
 IF expected<>NEW.source_count THEN RAISE EXCEPTION '모니터링 입력을 누락하거나 초과했습니다';END IF;
 SELECT count(DISTINCT (r->>'cursor')::bigint) INTO supplied FROM jsonb_array_elements(NEW.source_document::jsonb) r;
 IF supplied<>NEW.source_count OR EXISTS(
  SELECT 1 FROM jsonb_array_elements(NEW.source_document::jsonb) r WHERE NOT EXISTS(
   SELECT 1 FROM deposit_monitoring_inputs i WHERE i.user_id=NEW.user_id AND i.input_cursor=(r->>'cursor')::bigint
    AND i.input_cursor<=NEW.input_cursor AND i.recognized_at>=reference-interval '24 hours'
    AND i.operation_key=r->>'operationKey' AND i.receipt_id=(r->>'receiptId')::uuid
    AND i.asset=r->>'asset' AND i.network=r->>'network' AND i.txid=r->>'txid' AND i.recognized_at=(r->>'recognizedAt')::timestamptz
    AND i.currency=r->>'currency' AND i.valuation_policy IS NOT DISTINCT FROM r->>'valuationPolicy'
    AND i.valuation_status IS NOT DISTINCT FROM r->>'valuationStatus' AND i.price_attempt_id IS NOT DISTINCT FROM (r->>'priceAttemptId')::bigint
    AND i.krw_value IS NOT DISTINCT FROM (r->>'krwValue')::numeric
  )) THEN RAISE EXCEPTION '모니터링 근거와 실제 불변 원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS deposit_monitoring_assessment_binding_check ON deposit_monitoring_assessments;
CREATE TRIGGER deposit_monitoring_assessment_binding_check BEFORE INSERT ON deposit_monitoring_assessments FOR EACH ROW EXECUTE FUNCTION deposit_monitoring_assessment_binding();
CREATE OR REPLACE FUNCTION deposit_monitoring_head_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE next_cursor bigint;previous_cursor bigint;
BEGIN
 SELECT input_cursor INTO next_cursor FROM deposit_monitoring_assessments WHERE id=NEW.assessment_id AND user_id=NEW.user_id AND created_txid=txid_current();
 IF NOT FOUND THEN RAISE EXCEPTION '현재 모니터링 위치에는 같은 트랜잭션의 평가가 필요합니다';END IF;
 IF TG_OP='UPDATE' THEN
  SELECT input_cursor INTO previous_cursor FROM deposit_monitoring_assessments WHERE id=OLD.assessment_id;
  IF NEW.user_id<>OLD.user_id OR next_cursor<previous_cursor THEN RAISE EXCEPTION '현재 모니터링 위치를 뒤로 돌릴 수 없습니다';END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS deposit_monitoring_head_binding_check ON deposit_monitoring_heads;
CREATE TRIGGER deposit_monitoring_head_binding_check BEFORE INSERT OR UPDATE ON deposit_monitoring_heads FOR EACH ROW EXECUTE FUNCTION deposit_monitoring_head_binding();
CREATE OR REPLACE FUNCTION deposit_monitoring_job_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM deposit_monitoring_inputs WHERE user_id=NEW.user_id AND input_cursor=NEW.target_cursor)
  OR(TG_OP='UPDATE' AND(NEW.user_id<>OLD.user_id OR NEW.target_cursor<OLD.target_cursor OR NEW.observed_cursor<OLD.observed_cursor OR NEW.created_at<>OLD.created_at)) THEN
  RAISE EXCEPTION '모니터링 작업과 수탁 입력이 다릅니다';END IF;
 -- 처리 위치는 바로 다음 실제 입력과 같은 트랜잭션의 확정 평가로만 전진한다. 임대·목표 변경은 이 검사를 우회하지 않는다.
 IF TG_OP='INSERT' THEN
  IF NEW.observed_cursor<>0 THEN RAISE EXCEPTION '새 모니터링 작업은 미처리 위치에서 시작해야 합니다';END IF;
 ELSIF NEW.observed_cursor<>OLD.observed_cursor THEN
  IF NEW.observed_cursor IS DISTINCT FROM (SELECT min(input_cursor) FROM deposit_monitoring_inputs WHERE user_id=NEW.user_id AND input_cursor>OLD.observed_cursor)
   OR NOT EXISTS(SELECT 1 FROM deposit_monitoring_assessments a JOIN deposit_monitoring_heads h ON h.assessment_id=a.id
    WHERE a.user_id=NEW.user_id AND h.user_id=NEW.user_id AND a.input_cursor=NEW.observed_cursor AND a.created_txid=txid_current()) THEN
   RAISE EXCEPTION '모니터링 처리 위치는 다음 입력의 확정 평가가 필요합니다';END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS deposit_monitoring_job_binding_check ON deposit_monitoring_jobs;
CREATE TRIGGER deposit_monitoring_job_binding_check BEFORE INSERT OR UPDATE ON deposit_monitoring_jobs FOR EACH ROW EXECUTE FUNCTION deposit_monitoring_job_binding();
DROP TRIGGER IF EXISTS deposit_monitoring_assessment_immutable ON deposit_monitoring_assessments;
CREATE TRIGGER deposit_monitoring_assessment_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_assessments FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS deposit_monitoring_head_preserve ON deposit_monitoring_heads;
CREATE TRIGGER deposit_monitoring_head_preserve BEFORE DELETE OR TRUNCATE ON deposit_monitoring_heads FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS deposit_monitoring_job_preserve ON deposit_monitoring_jobs;
CREATE TRIGGER deposit_monitoring_job_preserve BEFORE DELETE OR TRUNCATE ON deposit_monitoring_jobs FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
