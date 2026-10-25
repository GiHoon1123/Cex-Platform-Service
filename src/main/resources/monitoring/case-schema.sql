-- 반복 탐지 평가를 같은 고객·정책의 조사 사건에 연결한다. 사건 생성은 종결·신고·금융 허가가 아니다.
CREATE TABLE IF NOT EXISTS deposit_monitoring_cases (
 id uuid PRIMARY KEY,user_id bigint NOT NULL REFERENCES users(id),policy_id varchar(50) NOT NULL,
 first_assessment_id uuid NOT NULL REFERENCES deposit_monitoring_assessments(id),
 opened_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS deposit_monitoring_case_heads (
 user_id bigint PRIMARY KEY REFERENCES users(id),case_id uuid NOT NULL UNIQUE REFERENCES deposit_monitoring_cases(id)
);
CREATE TABLE IF NOT EXISTS deposit_monitoring_case_assessments (
 case_id uuid NOT NULL REFERENCES deposit_monitoring_cases(id),assessment_id uuid NOT NULL UNIQUE REFERENCES deposit_monitoring_assessments(id),
 input_cursor bigint NOT NULL REFERENCES ledger_entries(id),source_hash varchar(64) NOT NULL CHECK(source_hash ~ '^[a-f0-9]{64}$'),
 linked_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 PRIMARY KEY(case_id,input_cursor)
);
CREATE OR REPLACE FUNCTION monitoring_case_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NEW.opened_at>clock_timestamp() OR NOT EXISTS(
  SELECT 1 FROM deposit_monitoring_assessments a WHERE a.id=NEW.first_assessment_id AND a.user_id=NEW.user_id
   AND a.policy_id=NEW.policy_id AND a.outcome='REVIEW_REQUIRED') THEN
  RAISE EXCEPTION '거래 조사 사건과 실제 고객·정책·검토 신호가 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_binding_check ON deposit_monitoring_cases;
CREATE TRIGGER monitoring_case_binding_check BEFORE INSERT ON deposit_monitoring_cases FOR EACH ROW EXECUTE FUNCTION monitoring_case_binding();
CREATE OR REPLACE FUNCTION monitoring_case_head_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM deposit_monitoring_cases c WHERE c.id=NEW.case_id AND c.user_id=NEW.user_id AND c.created_txid=txid_current()) THEN
  RAISE EXCEPTION '조사 사건 현재 위치와 같은 트랜잭션의 고객 사건이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_head_binding_check ON deposit_monitoring_case_heads;
CREATE TRIGGER monitoring_case_head_binding_check BEFORE INSERT ON deposit_monitoring_case_heads FOR EACH ROW EXECUTE FUNCTION monitoring_case_head_binding();
CREATE OR REPLACE FUNCTION monitoring_case_assessment_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NEW.linked_at>clock_timestamp() OR NOT EXISTS(
  SELECT 1 FROM deposit_monitoring_cases c JOIN deposit_monitoring_case_heads h ON h.case_id=c.id AND h.user_id=c.user_id
   JOIN deposit_monitoring_assessments a ON a.id=NEW.assessment_id AND a.user_id=c.user_id AND a.policy_id=c.policy_id
   WHERE c.id=NEW.case_id AND a.input_cursor=NEW.input_cursor AND a.source_hash=NEW.source_hash AND a.outcome='REVIEW_REQUIRED') THEN
  RAISE EXCEPTION '조사 사건의 신호 연결과 실제 고객·정책·입력 근거가 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_assessment_binding_check ON deposit_monitoring_case_assessments;
CREATE TRIGGER monitoring_case_assessment_binding_check BEFORE INSERT ON deposit_monitoring_case_assessments FOR EACH ROW EXECUTE FUNCTION monitoring_case_assessment_binding();
CREATE OR REPLACE FUNCTION monitoring_case_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM deposit_monitoring_case_heads h JOIN deposit_monitoring_case_assessments m ON m.case_id=h.case_id
  WHERE h.case_id=NEW.id AND h.user_id=NEW.user_id AND m.assessment_id=NEW.first_assessment_id) THEN
  RAISE EXCEPTION '새 거래 조사 사건은 현재 위치와 첫 신호 연결을 함께 확정해야 합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_complete_check ON deposit_monitoring_cases;
CREATE CONSTRAINT TRIGGER monitoring_case_complete_check AFTER INSERT ON deposit_monitoring_cases DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_case_complete();
CREATE OR REPLACE FUNCTION monitoring_case_assessment_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.action='MONITORING_CASE_ASSESSMENT_LINKED'
  AND a.target='monitoring-case:'||NEW.case_id::text||':assessment:'||NEW.assessment_id::text
  AND a.actor_id IS NULL AND a.detail=NEW.source_hash AND a.outcome='SUCCESS' AND a.occurred_at>=NEW.linked_at AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '조사 사건의 신호 연결에는 해당 원본의 필수 감사가 필요합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_assessment_audit_check ON deposit_monitoring_case_assessments;
CREATE CONSTRAINT TRIGGER monitoring_case_assessment_audit_check AFTER INSERT ON deposit_monitoring_case_assessments DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_case_assessment_audit();
DROP TRIGGER IF EXISTS monitoring_cases_immutable ON deposit_monitoring_cases;
CREATE TRIGGER monitoring_cases_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_cases FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS monitoring_case_heads_immutable ON deposit_monitoring_case_heads;
CREATE TRIGGER monitoring_case_heads_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_case_heads FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS monitoring_case_assessments_immutable ON deposit_monitoring_case_assessments;
CREATE TRIGGER monitoring_case_assessments_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_case_assessments FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 새 검토 신호가 확정됐는데 사건 연결 호출이 조용히 누락되는 부분 완료도 허용하지 않는다.
CREATE OR REPLACE FUNCTION monitoring_assessment_case_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM deposit_monitoring_case_assessments m WHERE m.assessment_id=NEW.id) THEN
  RAISE EXCEPTION '새 거래 검토 신호는 조사 사건 연결과 함께 확정해야 합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS monitoring_assessment_case_complete_check ON deposit_monitoring_assessments;
CREATE CONSTRAINT TRIGGER monitoring_assessment_case_complete_check AFTER INSERT ON deposit_monitoring_assessments
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN(NEW.outcome='REVIEW_REQUIRED') EXECUTE FUNCTION monitoring_assessment_case_complete();
