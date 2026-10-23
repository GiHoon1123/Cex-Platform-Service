-- 기존 원문·금액을 복사하지 않고 결정 당시 자료 목록의 전체 표현과 해시를 보존한다.
-- 텍스트를 그대로 보존해 이후 DB 버전의 JSON 출력 방식이 달라도 과거 해시를 검증할 수 있게 한다.
CREATE TABLE IF NOT EXISTS edd_case_dossiers (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES edd_cases(id),user_id bigint NOT NULL REFERENCES users(id),
 items text NOT NULL CHECK(jsonb_typeof(items::jsonb)='array'),digest varchar(64) NOT NULL,item_count integer NOT NULL CHECK(item_count>=0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(item_count=jsonb_array_length(items::jsonb)),CHECK(digest=encode(sha256(convert_to(items,'UTF8')),'hex'))
);
CREATE TABLE IF NOT EXISTS edd_case_dossier_views (
 id uuid PRIMARY KEY,report_id uuid NOT NULL REFERENCES edd_reports(id),dossier_id uuid NOT NULL REFERENCES edd_case_dossiers(id),
 actor_id bigint NOT NULL REFERENCES users(id),actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,
 page integer NOT NULL CHECK(page>=0),digest varchar(64) NOT NULL,viewed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS edd_dossier_view_report_actor ON edd_case_dossier_views(report_id,actor_id,viewed_at);
DROP TRIGGER IF EXISTS edd_dossier_no_change ON edd_case_dossiers;
CREATE TRIGGER edd_dossier_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_case_dossiers FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS edd_dossier_view_no_change ON edd_case_dossier_views;
CREATE TRIGGER edd_dossier_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON edd_case_dossier_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
