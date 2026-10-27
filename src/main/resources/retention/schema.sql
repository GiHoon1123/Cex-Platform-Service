-- 보존 정지는 파기 대상을 다시 만들지 않고 범위와 사유를 추가 전용 기록으로 남긴다.
CREATE TABLE IF NOT EXISTS data_retention_requests (
 id uuid PRIMARY KEY,action varchar(8) NOT NULL CHECK(action IN ('HOLD','RELEASE')),
 scope_type varchar(16) NOT NULL CHECK(scope_type IN ('USER','MAIL_JOB')),scope_id varchar(64) NOT NULL,
 hold_id uuid,requester_id bigint NOT NULL,requester_person uuid NOT NULL,requester_version bigint NOT NULL,
 checker_id bigint NOT NULL,checker_person uuid NOT NULL,checker_version bigint NOT NULL,request_key uuid NOT NULL,
 reason varchar(300) NOT NULL,authority_reference varchar(200) NOT NULL,payload text NOT NULL,payload_hash varchar(64) NOT NULL,
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(requester_id,request_key),
 CHECK((action='HOLD' AND hold_id IS NULL) OR (action='RELEASE' AND hold_id IS NOT NULL))
);
CREATE TABLE IF NOT EXISTS data_retention_request_views (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES data_retention_requests(id),actor_id bigint NOT NULL,
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,payload_hash varchar(64) NOT NULL,
 viewed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS data_retention_request_decisions (
 request_id uuid PRIMARY KEY REFERENCES data_retention_requests(id),approver_id bigint NOT NULL,session_id uuid NOT NULL,
 approved boolean NOT NULL,payload_hash varchar(64) NOT NULL,authorization_id uuid NOT NULL,decided_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS data_retention_holds (
 id uuid PRIMARY KEY,request_id uuid NOT NULL UNIQUE REFERENCES data_retention_requests(id),scope_type varchar(16) NOT NULL,
 scope_id varchar(64) NOT NULL,reason varchar(300) NOT NULL,authority_reference varchar(200) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS data_retention_hold_scope ON data_retention_holds(scope_type,scope_id);
CREATE TABLE IF NOT EXISTS data_retention_hold_releases (
 hold_id uuid PRIMARY KEY REFERENCES data_retention_holds(id),request_id uuid NOT NULL UNIQUE REFERENCES data_retention_requests(id),
 released_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS data_disposal_runs (
 id uuid PRIMARY KEY,dataset varchar(40) NOT NULL CHECK(dataset='CUSTOMER_MAIL_PAYLOAD'),disposed_count integer NOT NULL CHECK(disposed_count>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS data_disposal_items (
 run_id uuid NOT NULL REFERENCES data_disposal_runs(id),dataset varchar(40) NOT NULL,item_id uuid NOT NULL,
 disposed_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(run_id,item_id)
);

-- 요청·열람·결정·보존 정지·파기 증적은 과거 사실이므로 수정이나 삭제를 허용하지 않는다.
DO $$ DECLARE name text; BEGIN
 FOREACH name IN ARRAY ARRAY['data_retention_requests','data_retention_request_views','data_retention_request_decisions','data_retention_holds','data_retention_hold_releases','data_disposal_runs','data_disposal_items'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS %I_no_change ON %I',name,name);
  EXECUTE format('CREATE TRIGGER %I_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION security_audit_immutable()',name,name);
 END LOOP;
END $$;

-- 법령·조사 근거가 살아 있는 고객이나 메일 작업은 직접 SQL 삭제도 차단한다.
CREATE OR REPLACE FUNCTION customer_mail_payload_retention_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM customer_mail_jobs j JOIN customer_mail_delivery d ON d.job_id=j.id WHERE j.id=OLD.job_id AND j.expires_at<clock_timestamp()-interval '1 day' AND d.status IN ('SMTP_ACCEPTED','SUPPRESSED','EXHAUSTED')) THEN RAISE EXCEPTION '메일 암호문 보존 조건이 충족되지 않았습니다'; END IF;
 IF EXISTS(SELECT 1 FROM customer_mail_jobs j JOIN data_retention_holds h ON (h.scope_type='MAIL_JOB' AND h.scope_id=j.id::text) OR (h.scope_type='USER' AND h.scope_id=j.user_id::text) LEFT JOIN data_retention_hold_releases r ON r.hold_id=h.id WHERE j.id=OLD.job_id AND r.hold_id IS NULL) THEN RAISE EXCEPTION '활성 보존 정지 대상은 파기할 수 없습니다'; END IF;
 RETURN OLD;
END $$;
