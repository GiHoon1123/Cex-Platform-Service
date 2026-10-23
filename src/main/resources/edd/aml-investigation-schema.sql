-- 서명 검증·현재 적용·위험 평가와 같은 트랜잭션에서 얻은 공급사 자료만 보존한다.
-- 원문 전체 대신 조사에 필요한 필드를 암호화하며 기존 과거 평가의 자료를 채워 넣지 않는다.
CREATE TABLE IF NOT EXISTS edd_aml_sources (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES edd_cases(id),user_id bigint NOT NULL REFERENCES users(id),
 assessment_id uuid NOT NULL UNIQUE REFERENCES cdd_assessments(id),event_id uuid NOT NULL REFERENCES kyc_events(event_id),
 status_digest varchar(64) NOT NULL,provider_time bigint NOT NULL,content_mac varchar(64) NOT NULL,key_id varchar(64) NOT NULL,
 report_cipher bytea NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS edd_aml_source_case ON edd_aml_sources(case_id,created_at);
CREATE TABLE IF NOT EXISTS edd_aml_source_views (
 id uuid PRIMARY KEY,source_id uuid NOT NULL REFERENCES edd_aml_sources(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,case_revision bigint NOT NULL,
 content_mac varchar(64) NOT NULL,viewed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS edd_aml_investigations (
 id uuid PRIMARY KEY,source_id uuid NOT NULL REFERENCES edd_aml_sources(id),case_id uuid NOT NULL REFERENCES edd_cases(id),
 user_id bigint NOT NULL REFERENCES users(id),requester_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,notes_cipher bytea NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(requester_id,request_key)
);
CREATE INDEX IF NOT EXISTS edd_aml_investigation_case ON edd_aml_investigations(case_id,created_at);
DROP TRIGGER IF EXISTS edd_aml_source_no_change ON edd_aml_sources;
CREATE TRIGGER edd_aml_source_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_aml_sources FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS edd_aml_source_view_no_change ON edd_aml_source_views;
CREATE TRIGGER edd_aml_source_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_aml_source_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS edd_aml_investigation_no_change ON edd_aml_investigations;
CREATE TRIGGER edd_aml_investigation_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_aml_investigations FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
