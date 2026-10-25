-- 신호와 의견은 추가만 가능하다. 원장 순번의 접두 구간과 그 안 의견 수가 검토 범위를 고정한다.
CREATE OR REPLACE FUNCTION monitoring_case_scope(target uuid,through bigint)
 RETURNS TABLE(user_id bigint,policy_id varchar,signals bigint,through_cursor bigint,opinions bigint,scope_version text) LANGUAGE sql AS $$
 SELECT p.*,encode(sha256(convert_to('TM_CASE_SCOPE_V1:'||target::text||':'||p.user_id::text||':'||p.policy_id||':'||p.signals::text||':'||p.through_cursor::text||':'||p.opinions::text,'UTF8')),'hex')
 FROM(SELECT c.user_id,c.policy_id,
  (SELECT count(*) FROM deposit_monitoring_case_assessments m WHERE m.case_id=c.id AND m.input_cursor<=through) signals,
  coalesce((SELECT max(input_cursor) FROM deposit_monitoring_case_assessments m WHERE m.case_id=c.id AND m.input_cursor<=through),0) through_cursor,
  (SELECT count(*) FROM deposit_monitoring_observations o JOIN deposit_monitoring_case_assessments m ON m.assessment_id=o.assessment_id WHERE m.case_id=c.id AND m.input_cursor<=through) opinions
 FROM deposit_monitoring_cases c WHERE c.id=target) p;
$$;
CREATE INDEX IF NOT EXISTS monitoring_source_views_actor ON deposit_monitoring_source_views(assessment_id,actor_id,session_id,actor_person,actor_version,source_hash);
CREATE INDEX IF NOT EXISTS monitoring_note_views_actor ON deposit_monitoring_observation_views(observation_id,actor_id,session_id,actor_person,actor_version);
CREATE TABLE IF NOT EXISTS deposit_monitoring_case_reviews (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES deposit_monitoring_cases(id),user_id bigint NOT NULL REFERENCES users(id),
 requester_id bigint NOT NULL REFERENCES users(id),checker_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 through_cursor bigint NOT NULL REFERENCES ledger_entries(id),scope_version varchar(64) NOT NULL CHECK(scope_version ~ '^[a-f0-9]{64}$'),
 payload text NOT NULL CHECK(octet_length(payload)<=65536 AND jsonb_typeof(payload::jsonb)='object'),payload_hash varchar(64) NOT NULL CHECK(payload_hash=encode(sha256(convert_to(payload,'UTF8')),'hex')),
 facts_cipher bytea NOT NULL CHECK(octet_length(facts_cipher) BETWEEN 28 AND 16384),expires_at timestamptz NOT NULL,
 kind varchar(24) NOT NULL DEFAULT 'TM_CASE_REVIEW' CHECK(kind='TM_CASE_REVIEW'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 UNIQUE(requester_id,request_key),CHECK(requester_id<>checker_id),
 FOREIGN KEY(kind,id) REFERENCES operator_approval_bindings(kind,request_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX IF NOT EXISTS monitoring_case_review_scope ON deposit_monitoring_case_reviews(case_id,scope_version,created_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS deposit_monitoring_case_review_views (
 id uuid PRIMARY KEY,review_id uuid NOT NULL REFERENCES deposit_monitoring_case_reviews(id),actor_id bigint NOT NULL REFERENCES users(id),actor_person uuid NOT NULL,
 actor_version bigint NOT NULL,session_id uuid NOT NULL,payload_hash varchar(64) NOT NULL,
 viewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE INDEX IF NOT EXISTS monitoring_case_review_view_actor ON deposit_monitoring_case_review_views(review_id,actor_id,session_id,payload_hash,viewed_at);
-- 인증 옵션은 만료/다음 업무에서 지운다. 영구 결정에는 검증 당시 식별자만 보존하고 임시 표의 수명을 FK로 묶지 않는다.
CREATE TABLE IF NOT EXISTS deposit_monitoring_case_review_decisions (
 review_id uuid PRIMARY KEY REFERENCES deposit_monitoring_case_reviews(id),approver_id bigint NOT NULL REFERENCES users(id),approver_person uuid NOT NULL,
 approver_version bigint NOT NULL,session_id uuid NOT NULL,approved boolean NOT NULL,payload_hash varchar(64) NOT NULL,
 authorization_id uuid NOT NULL UNIQUE,decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE OR REPLACE FUNCTION monitoring_case_review_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p jsonb;scope record;
BEGIN
 p:=NEW.payload::jsonb;SELECT * INTO scope FROM monitoring_case_scope(NEW.case_id,NEW.through_cursor);
 IF COALESCE(NEW.created_txid<>txid_current() OR NOT FOUND OR NEW.created_at>clock_timestamp() OR NEW.expires_at<=NEW.created_at OR NEW.expires_at>NEW.created_at+interval '10 minutes 10 seconds'
  OR scope.user_id<>NEW.user_id OR scope.scope_version<>NEW.scope_version OR scope.through_cursor<>NEW.through_cursor OR scope.opinions<1
  OR(p->>'id')::uuid<>NEW.id OR(p->>'caseId')::uuid<>NEW.case_id OR(p->>'userId')::bigint<>NEW.user_id
  OR(p->>'requesterId')::bigint<>NEW.requester_id OR(p->>'checkerId')::bigint<>NEW.checker_id
  OR(p->>'expiresAt')::timestamptz<>NEW.expires_at OR p->>'policy'<>'TM_CASE_REVIEW_V1'
  OR p->>'assurance'<>'INTERNAL_CASE_REVIEW_NOT_CLOSURE_REPORT_OR_FINANCIAL_PERMISSION'
  OR(p->'scope'->>'caseId')::uuid<>NEW.case_id OR p->'scope'->>'throughCursor'<>NEW.through_cursor::text OR p->'scope'->>'version'<>NEW.scope_version
  OR(p->'scope'->>'customerId')::bigint<>scope.user_id OR p->'scope'->>'policy'<>scope.policy_id
  OR NOT(p->>'factsMac' ~ '^[a-f0-9]{64}$') OR NOT(p->>'evidenceKeyId' ~ '^[a-f0-9]{64}$') OR NOT(p->>'cddKeyId' ~ '^[a-f0-9]{64}$')
  OR(p->'scope'->>'signals')::bigint<>scope.signals OR(p->'scope'->>'opinions')::bigint<>scope.opinions
  OR NOT EXISTS(SELECT 1 FROM users a JOIN users b ON b.id=NEW.checker_id WHERE a.id=NEW.requester_id AND a.enabled=true AND b.enabled=true
    AND a.role='OPERATOR' AND b.role='APPROVER' AND a.operator_person_id IS DISTINCT FROM b.operator_person_id
    AND a.operator_person_id=(p->>'requesterPerson')::uuid AND b.operator_person_id=(p->>'checkerPerson')::uuid
    AND a.version=(p->>'requesterVersion')::bigint AND b.version=(p->>'checkerVersion')::bigint),true) THEN
  RAISE EXCEPTION '거래 사건 판단과 실제 범위·운영 인물·불변 요청이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_review_binding_check ON deposit_monitoring_case_reviews;
CREATE TRIGGER monitoring_case_review_binding_check BEFORE INSERT ON deposit_monitoring_case_reviews FOR EACH ROW EXECUTE FUNCTION monitoring_case_review_binding();
CREATE OR REPLACE FUNCTION monitoring_case_review_view_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF COALESCE(NEW.created_txid<>txid_current() OR NEW.viewed_at>clock_timestamp()
  OR NOT monitoring_investigation_actor(NEW.actor_id,NEW.actor_person,NEW.actor_version,NEW.session_id,ARRAY['OPERATOR','APPROVER']::varchar[])
  OR NOT EXISTS(SELECT 1 FROM deposit_monitoring_case_reviews r WHERE r.id=NEW.review_id AND r.payload_hash=NEW.payload_hash
   AND((r.requester_id=NEW.actor_id AND r.payload::jsonb->>'requesterPerson'=NEW.actor_person::text) OR(r.checker_id=NEW.actor_id AND r.payload::jsonb->>'checkerPerson'=NEW.actor_person::text))),true) THEN
  RAISE EXCEPTION '거래 사건 판단 열람과 지정된 인물·세션·요청이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_review_view_binding_check ON deposit_monitoring_case_review_views;
CREATE TRIGGER monitoring_case_review_view_binding_check BEFORE INSERT ON deposit_monitoring_case_review_views FOR EACH ROW EXECUTE FUNCTION monitoring_case_review_view_binding();
CREATE OR REPLACE FUNCTION monitoring_case_review_decision_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;scope record;
BEGIN
 SELECT * INTO r FROM deposit_monitoring_case_reviews WHERE id=NEW.review_id;
 SELECT * INTO scope FROM monitoring_case_scope(r.case_id,r.through_cursor);
 IF COALESCE(NEW.created_txid<>txid_current() OR NEW.decided_at>clock_timestamp() OR r.checker_id<>NEW.approver_id OR r.payload_hash<>NEW.payload_hash
  OR r.scope_version<>scope.scope_version OR r.expires_at<=clock_timestamp()
  OR r.payload::jsonb->>'checkerPerson'<>NEW.approver_person::text OR(r.payload::jsonb->>'checkerVersion')::bigint<>NEW.approver_version
  OR NOT monitoring_investigation_actor(NEW.approver_id,NEW.approver_person,NEW.approver_version,NEW.session_id,ARRAY['APPROVER']::varchar[])
  OR NOT EXISTS(SELECT 1 FROM operator_authorization_challenges c WHERE c.id=NEW.authorization_id AND c.user_id=NEW.approver_id AND c.session_id=NEW.session_id
   AND c.kind='TM_CASE_REVIEW' AND c.request_id=NEW.review_id AND c.payload_hash=NEW.payload_hash AND c.approve=NEW.approved AND c.consumed=true),true) THEN
  RAISE EXCEPTION '거래 사건 결정과 지정 승인자·현재 범위·소비된 인증이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_review_decision_binding_check ON deposit_monitoring_case_review_decisions;
CREATE TRIGGER monitoring_case_review_decision_binding_check BEFORE INSERT ON deposit_monitoring_case_review_decisions FOR EACH ROW EXECUTE FUNCTION monitoring_case_review_decision_binding();
-- 요청·열람·결정마다 해당 업무의 성공 감사가 필요하다. 사유 원문과 판단 방향은 감사 본문에 넣지 않는다.
CREATE OR REPLACE FUNCTION monitoring_case_review_audit_complete() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE review_actor bigint;review_person uuid;audit_target_id uuid;audit_stamp timestamptz;
BEGIN
 IF TG_TABLE_NAME='deposit_monitoring_case_reviews' THEN review_actor:=NEW.requester_id;review_person:=(NEW.payload::jsonb->>'requesterPerson')::uuid;audit_target_id:=NEW.id;audit_stamp:=NEW.created_at;
 ELSIF TG_TABLE_NAME='deposit_monitoring_case_review_views' THEN review_actor:=NEW.actor_id;review_person:=NEW.actor_person;audit_target_id:=NEW.id;audit_stamp:=NEW.viewed_at;
 ELSE review_actor:=NEW.approver_id;review_person:=NEW.approver_person;audit_target_id:=NEW.review_id;audit_stamp:=NEW.decided_at;END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.action=TG_ARGV[0] AND a.target=TG_ARGV[1]||audit_target_id::text AND a.actor_id=review_actor
  AND a.actor_person=review_person AND a.outcome='SUCCESS' AND a.occurred_at>=audit_stamp AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '거래 사건 판단 업무에는 해당 인물의 필수 감사가 필요합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_review_audit_check ON deposit_monitoring_case_reviews;
CREATE CONSTRAINT TRIGGER monitoring_case_review_audit_check AFTER INSERT ON deposit_monitoring_case_reviews DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_case_review_audit_complete('MONITORING_CASE_REVIEW_PROPOSED','monitoring-case-review:');
DROP TRIGGER IF EXISTS monitoring_case_review_view_audit_check ON deposit_monitoring_case_review_views;
CREATE CONSTRAINT TRIGGER monitoring_case_review_view_audit_check AFTER INSERT ON deposit_monitoring_case_review_views DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_case_review_audit_complete('MONITORING_CASE_REVIEW_VIEWED','monitoring-case-review-view:');
DROP TRIGGER IF EXISTS monitoring_case_review_decision_audit_check ON deposit_monitoring_case_review_decisions;
CREATE CONSTRAINT TRIGGER monitoring_case_review_decision_audit_check AFTER INSERT ON deposit_monitoring_case_review_decisions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_case_review_audit_complete('MONITORING_CASE_REVIEW_DECIDED','monitoring-case-review:');
DROP TRIGGER IF EXISTS monitoring_case_reviews_immutable ON deposit_monitoring_case_reviews;
CREATE TRIGGER monitoring_case_reviews_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_case_reviews FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS monitoring_case_review_views_immutable ON deposit_monitoring_case_review_views;
CREATE TRIGGER monitoring_case_review_views_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_case_review_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS monitoring_case_review_decisions_immutable ON deposit_monitoring_case_review_decisions;
CREATE TRIGGER monitoring_case_review_decisions_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_case_review_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
