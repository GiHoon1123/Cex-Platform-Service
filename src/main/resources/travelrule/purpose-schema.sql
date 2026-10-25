-- 자동 출처 요청의 내부 목적과 당시 평가 근거를 보존한다. 법정 면제·동일인·금융 해제 판정과 구분한다.
CREATE TABLE IF NOT EXISTS codevasp_transfer_purposes (
 intent_id uuid PRIMARY KEY REFERENCES codevasp_transfer_intents(id),
 receipt_id uuid NOT NULL REFERENCES bitcoin_deposit_valuations(receipt_id),
 policy_id varchar(40) NOT NULL CHECK(policy_id='BTC_ORIGIN_REFERENCE_1M_V1'),
 purpose varchar(40) NOT NULL CHECK(purpose='DEPOSIT_ORIGIN_VERIFICATION'),
 price_attempt_id bigint NOT NULL REFERENCES btc_krw_price_results(attempt_id),
 reference_at timestamptz NOT NULL,krw_value numeric NOT NULL CHECK(krw_value>=1000000),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE OR REPLACE FUNCTION codevasp_transfer_purpose_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at>clock_timestamp() OR NOT EXISTS(
  SELECT 1 FROM codevasp_transfer_intents i JOIN bitcoin_deposit_valuations v ON v.receipt_id=i.receipt_id
   WHERE i.id=NEW.intent_id AND i.receipt_id=NEW.receipt_id AND v.policy_id='BTC_PENDING_KRW_REFERENCE_V1'
    AND v.status='VALUED' AND v.krw_value>=1000000 AND v.krw_value=NEW.krw_value
    AND v.price_attempt_id=NEW.price_attempt_id AND v.reference_at=NEW.reference_at
 ) THEN RAISE EXCEPTION '자동 정보 요청의 목적과 입금 당시 평가 원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_transfer_purpose_binding_check ON codevasp_transfer_purposes;
CREATE TRIGGER codevasp_transfer_purpose_binding_check BEFORE INSERT ON codevasp_transfer_purposes FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_purpose_binding();
CREATE OR REPLACE FUNCTION codevasp_transfer_purpose_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM codevasp_transfer_purposes p WHERE p.intent_id=NEW.id AND p.created_txid=txid_current()) THEN
  RAISE EXCEPTION '새 정보 제공 의도에는 같은 트랜잭션의 목적 근거가 필요합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS codevasp_transfer_purpose_complete_check ON codevasp_transfer_intents;
CREATE CONSTRAINT TRIGGER codevasp_transfer_purpose_complete_check AFTER INSERT ON codevasp_transfer_intents
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_purpose_complete();
DROP TRIGGER IF EXISTS codevasp_transfer_purpose_immutable ON codevasp_transfer_purposes;
CREATE TRIGGER codevasp_transfer_purpose_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_transfer_purposes FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
-- 직접 실행 원본을 삽입해도 목적 없는 과거 의도로 새 개인정보 요청을 시작하지 못한다.
CREATE OR REPLACE FUNCTION codevasp_transfer_attempt_purpose() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM codevasp_transfer_purposes p WHERE p.intent_id=NEW.intent_id AND p.receipt_id=NEW.receipt_id) THEN
  RAISE EXCEPTION '정보 교환 실행에는 기록된 입금 목적 근거가 필요합니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_transfer_attempt_purpose_check ON codevasp_transfer_attempts;
CREATE TRIGGER codevasp_transfer_attempt_purpose_check BEFORE INSERT ON codevasp_transfer_attempts FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_attempt_purpose();
