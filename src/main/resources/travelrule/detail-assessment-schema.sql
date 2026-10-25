-- 수신 성공과 요청 당시 고객 정보의 일관성 분류를 구분한다. 분류는 금융 해제나 동일인 확정이 아니다.
CREATE TABLE IF NOT EXISTS codevasp_transfer_detail_assessments (
 attempt_id uuid PRIMARY KEY REFERENCES codevasp_transfer_results(attempt_id),
 policy_id varchar(40) NOT NULL CHECK(policy_id='BTC_REQUEST_DETAILS_V1'),
 status varchar(24) NOT NULL CHECK(status IN ('CONSISTENT','REVIEW_REQUIRED')),
 reason varchar(40) NOT NULL CHECK(reason IN ('DETAILS_CONSISTENT','PARTIES_INCOMPLETE',
  'BENEFICIARY_STRUCTURED_NAME','BENEFICIARY_NAME_MISMATCH','BENEFICIARY_BIRTH_DATE_MISSING','BENEFICIARY_BIRTH_DATE_MISMATCH','BENEFICIARY_ACCOUNT_MISMATCH',
  'ORIGINATOR_STRUCTURED_NAME','ORIGINATOR_NAME_MISMATCH','ORIGINATOR_BIRTH_DATE_MISSING','ORIGINATOR_BIRTH_DATE_MISMATCH')),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK((status='CONSISTENT')=(reason='DETAILS_CONSISTENT'))
);
CREATE OR REPLACE FUNCTION codevasp_detail_assessment_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at>clock_timestamp() OR NOT EXISTS(
  SELECT 1 FROM codevasp_transfer_results r JOIN codevasp_transfer_attempts a ON a.id=r.attempt_id
   JOIN codevasp_transfer_heads h ON h.intent_id=a.intent_id AND h.attempt_id=a.id
   WHERE r.attempt_id=NEW.attempt_id AND r.status='DATA_RECEIVED' AND NEW.recorded_at>=r.recorded_at
 ) THEN RAISE EXCEPTION '정보 일관성 분류와 현재 수신 원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_detail_assessment_binding_check ON codevasp_transfer_detail_assessments;
CREATE TRIGGER codevasp_detail_assessment_binding_check BEFORE INSERT ON codevasp_transfer_detail_assessments FOR EACH ROW EXECUTE FUNCTION codevasp_detail_assessment_binding();
CREATE OR REPLACE FUNCTION codevasp_detail_assessment_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM codevasp_transfer_detail_assessments a WHERE a.attempt_id=NEW.attempt_id AND a.created_txid=txid_current()) THEN
  RAISE EXCEPTION '새 정보 수신 결과에는 같은 트랜잭션의 일관성 분류가 필요합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS codevasp_detail_assessment_complete_check ON codevasp_transfer_results;
CREATE CONSTRAINT TRIGGER codevasp_detail_assessment_complete_check AFTER INSERT ON codevasp_transfer_results
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.status='DATA_RECEIVED') EXECUTE FUNCTION codevasp_detail_assessment_complete();
DROP TRIGGER IF EXISTS codevasp_detail_assessment_immutable ON codevasp_transfer_detail_assessments;
CREATE TRIGGER codevasp_detail_assessment_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_transfer_detail_assessments FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
