-- 검토 이력은 고객 신고 변경으로 지우지 않는다. 한 고객의 검토를 같은 사건에서 계속 추적한다.
CREATE TABLE IF NOT EXISTS edd_cases (
 id uuid PRIMARY KEY, user_id bigint NOT NULL UNIQUE REFERENCES users(id),
 first_assessment uuid NOT NULL REFERENCES cdd_assessments(id), latest_assessment uuid NOT NULL REFERENCES cdd_assessments(id),
 revision bigint NOT NULL DEFAULT 1, state varchar(24) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','REFERRED','CLOSED')),
 valid_until timestamptz, created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS cdd_assessment_user_outcome ON cdd_assessments(user_id,outcome);
-- 실제 평가·서명 이벤트를 근거로 고정한다. 증명되지 않은 외부 문서 번호나 민감 원문을 받지 않는다.
CREATE TABLE IF NOT EXISTS edd_reports (
 id uuid PRIMARY KEY, case_id uuid NOT NULL REFERENCES edd_cases(id), user_id bigint NOT NULL REFERENCES users(id),
 requester_id bigint NOT NULL REFERENCES users(id), request_key uuid NOT NULL,
 payload text NOT NULL, payload_hash varchar(64) NOT NULL, expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), UNIQUE(requester_id,request_key)
);
CREATE INDEX IF NOT EXISTS edd_report_case_time ON edd_reports(case_id,created_at);
CREATE TABLE IF NOT EXISTS edd_decisions (
 report_id uuid PRIMARY KEY REFERENCES edd_reports(id), approver_id bigint NOT NULL REFERENCES users(id),
 approver_person uuid NOT NULL, approved boolean NOT NULL, decided_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
DROP TRIGGER IF EXISTS edd_report_no_change ON edd_reports;
CREATE TRIGGER edd_report_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_reports FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS edd_decision_no_change ON edd_decisions;
CREATE TRIGGER edd_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
