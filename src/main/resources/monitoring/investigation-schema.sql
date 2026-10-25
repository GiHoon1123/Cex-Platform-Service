-- 탐지 원본 열람과 담당자 의견을 분리한다. 의견은 결론·보고 결정·금융 허가가 아니다.
CREATE TABLE IF NOT EXISTS deposit_monitoring_source_views (
 id uuid PRIMARY KEY,assessment_id uuid NOT NULL REFERENCES deposit_monitoring_assessments(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,source_hash varchar(64) NOT NULL CHECK(source_hash ~ '^[a-f0-9]{64}$'),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS deposit_monitoring_observations (
 id uuid PRIMARY KEY,assessment_id uuid NOT NULL REFERENCES deposit_monitoring_assessments(id),view_id uuid NOT NULL REFERENCES deposit_monitoring_source_views(id),
 actor_id bigint NOT NULL REFERENCES users(id),actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,request_key uuid NOT NULL,
 finding varchar(32) NOT NULL CHECK(finding IN ('EXPLAINED_ACTIVITY','REQUIRES_FURTHER_REVIEW','INSUFFICIENT_INFORMATION')),
 source_hash varchar(64) NOT NULL CHECK(source_hash ~ '^[a-f0-9]{64}$'),notes_mac varchar(64) NOT NULL CHECK(notes_mac ~ '^[a-f0-9]{64}$'),
 fingerprint_key_id varchar(64) NOT NULL CHECK(fingerprint_key_id ~ '^[a-f0-9]{64}$'),evidence_key_id varchar(64) NOT NULL CHECK(evidence_key_id ~ '^[a-f0-9]{64}$'),
 notes_cipher bytea NOT NULL CHECK(octet_length(notes_cipher) BETWEEN 28 AND 16384),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),UNIQUE(actor_id,request_key)
);
CREATE INDEX IF NOT EXISTS monitoring_observations_assessment ON deposit_monitoring_observations(assessment_id,recorded_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS deposit_monitoring_observation_views (
 id uuid PRIMARY KEY,observation_id uuid NOT NULL REFERENCES deposit_monitoring_observations(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
-- 현재 역할·만료·추가 인증의 최종 판정은 기존 운영 세션 정책이 담당한다. DB는 위조된 관계·운영 버전 삽입도 거부한다.
CREATE OR REPLACE FUNCTION monitoring_investigation_actor(actor bigint,person uuid,version bigint,sid uuid,roles varchar[]) RETURNS boolean LANGUAGE sql AS $$
 SELECT EXISTS(SELECT 1 FROM users u JOIN refresh_tokens s ON s.user_id=u.id AND s.session_id=sid
  WHERE u.id=actor AND u.enabled=true AND u.role=ANY(roles) AND u.operator_person_id=person AND u.version=version
   AND s.revoked=false AND s.mfa_verified_at IS NOT NULL AND s.mfa_credential_fingerprint ~ '^[a-f0-9]{64}$');
$$;
CREATE OR REPLACE FUNCTION monitoring_source_view_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at>clock_timestamp()
  OR NOT monitoring_investigation_actor(NEW.actor_id,NEW.actor_person,NEW.actor_version,NEW.session_id,ARRAY['OPERATOR','APPROVER']::varchar[])
  OR NOT EXISTS(SELECT 1 FROM deposit_monitoring_assessments a WHERE a.id=NEW.assessment_id AND a.source_hash=NEW.source_hash) THEN
  RAISE EXCEPTION '탐지 열람과 현재 운영자·세션·원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_source_view_binding_check ON deposit_monitoring_source_views;
CREATE TRIGGER monitoring_source_view_binding_check BEFORE INSERT ON deposit_monitoring_source_views FOR EACH ROW EXECUTE FUNCTION monitoring_source_view_binding();
CREATE OR REPLACE FUNCTION monitoring_observation_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at>clock_timestamp()
  OR NOT monitoring_investigation_actor(NEW.actor_id,NEW.actor_person,NEW.actor_version,NEW.session_id,ARRAY['OPERATOR']::varchar[])
  OR NOT EXISTS(SELECT 1 FROM deposit_monitoring_source_views v JOIN deposit_monitoring_assessments a ON a.id=v.assessment_id
   WHERE v.id=NEW.view_id AND v.assessment_id=NEW.assessment_id AND a.outcome IN ('REVIEW_REQUIRED','INCOMPLETE')
    AND v.actor_id=NEW.actor_id AND v.actor_person=NEW.actor_person AND v.actor_version=NEW.actor_version AND v.session_id=NEW.session_id
    AND v.source_hash=NEW.source_hash AND a.source_hash=NEW.source_hash) THEN
  RAISE EXCEPTION '거래 조사 의견과 실제 열람·담당자·탐지 근거가 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_observation_binding_check ON deposit_monitoring_observations;
CREATE TRIGGER monitoring_observation_binding_check BEFORE INSERT ON deposit_monitoring_observations FOR EACH ROW EXECUTE FUNCTION monitoring_observation_binding();
CREATE OR REPLACE FUNCTION monitoring_observation_view_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at>clock_timestamp()
  OR NOT monitoring_investigation_actor(NEW.actor_id,NEW.actor_person,NEW.actor_version,NEW.session_id,ARRAY['OPERATOR','APPROVER']::varchar[]) THEN
  RAISE EXCEPTION '조사 의견 열람과 현재 운영자·세션이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_observation_view_binding_check ON deposit_monitoring_observation_views;
CREATE TRIGGER monitoring_observation_view_binding_check BEFORE INSERT ON deposit_monitoring_observation_views FOR EACH ROW EXECUTE FUNCTION monitoring_observation_view_binding();
-- 호출자가 감사를 조용히 누락해도 원본·의견 열람과 의견 기록을 성공으로 확정하지 못한다.
CREATE OR REPLACE FUNCTION monitoring_investigation_audit_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.action=TG_ARGV[0] AND a.target=TG_ARGV[1]||NEW.id::text
  AND a.actor_id=NEW.actor_id AND a.actor_person=NEW.actor_person AND a.outcome='SUCCESS' AND a.occurred_at>=NEW.recorded_at AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '거래 조사 업무에는 해당 운영자의 필수 감사가 필요합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS monitoring_source_view_audit_check ON deposit_monitoring_source_views;
CREATE CONSTRAINT TRIGGER monitoring_source_view_audit_check AFTER INSERT ON deposit_monitoring_source_views DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_investigation_audit_complete('MONITORING_EVIDENCE_INSPECTED','monitoring-source-view:');
DROP TRIGGER IF EXISTS monitoring_observation_audit_check ON deposit_monitoring_observations;
CREATE CONSTRAINT TRIGGER monitoring_observation_audit_check AFTER INSERT ON deposit_monitoring_observations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_investigation_audit_complete('MONITORING_OBSERVATION_RECORDED','monitoring-observation:');
DROP TRIGGER IF EXISTS monitoring_observation_view_audit_check ON deposit_monitoring_observation_views;
CREATE CONSTRAINT TRIGGER monitoring_observation_view_audit_check AFTER INSERT ON deposit_monitoring_observation_views DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_investigation_audit_complete('MONITORING_OBSERVATION_INSPECTED','monitoring-observation-view:');
DROP TRIGGER IF EXISTS monitoring_source_views_immutable ON deposit_monitoring_source_views;
CREATE TRIGGER monitoring_source_views_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_source_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS monitoring_observations_immutable ON deposit_monitoring_observations;
CREATE TRIGGER monitoring_observations_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_observations FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS monitoring_observation_views_immutable ON deposit_monitoring_observation_views;
CREATE TRIGGER monitoring_observation_views_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_observation_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
