-- 의견 승인과 실행 의도를 분리한다. 본문은 기존 암호화 판단의 해시를 참조하며 사유 원문을 복제하지 않는다.
CREATE TABLE IF NOT EXISTS deposit_monitoring_case_actions (
 id uuid PRIMARY KEY,review_id uuid NOT NULL REFERENCES deposit_monitoring_case_reviews(id),
 case_id uuid NOT NULL REFERENCES deposit_monitoring_cases(id),user_id bigint NOT NULL REFERENCES users(id),
 requester_id bigint NOT NULL REFERENCES users(id),checker_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 action varchar(40) NOT NULL CHECK(action IN ('CLOSE_EXPLAINED','REFER_FOR_REPORTING_REVIEW')),
 payload text NOT NULL CHECK(octet_length(payload)<=8192 AND jsonb_typeof(payload::jsonb)='object'),
 payload_hash varchar(64) NOT NULL CHECK(payload_hash=encode(sha256(convert_to(payload,'UTF8')),'hex')),
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 kind varchar(24) NOT NULL DEFAULT 'TM_CASE_ACTION' CHECK(kind='TM_CASE_ACTION'),UNIQUE(requester_id,request_key),
 FOREIGN KEY(kind,id) REFERENCES operator_approval_bindings(kind,request_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX IF NOT EXISTS monitoring_case_action_case ON deposit_monitoring_case_actions(case_id,action);
CREATE TABLE IF NOT EXISTS deposit_monitoring_case_action_decisions (
 request_id uuid PRIMARY KEY REFERENCES deposit_monitoring_case_actions(id),approver_id bigint NOT NULL REFERENCES users(id),
 session_id uuid NOT NULL,approved boolean NOT NULL,payload_hash varchar(64) NOT NULL,authorization_id uuid NOT NULL UNIQUE,
 decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
-- 종결 시점의 범위는 영구 보존한다. 나중에 원래 사건에 의견이 추가되면 현재 범위와 달라져 재검토가 필요하다.
CREATE OR REPLACE FUNCTION monitoring_case_closed(target uuid) RETURNS boolean LANGUAGE sql AS $$
 SELECT EXISTS(SELECT 1 FROM deposit_monitoring_case_actions a JOIN deposit_monitoring_case_action_decisions d ON d.request_id=a.id AND d.approved
 JOIN monitoring_case_scope(target,9223372036854775807) s ON a.payload::jsonb->>'scopeVersion'=s.scope_version
 WHERE a.case_id=target AND a.action='CLOSE_EXPLAINED');
$$;
CREATE OR REPLACE FUNCTION monitoring_case_action_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;p jsonb;s record;
BEGIN
 SELECT * INTO r FROM deposit_monitoring_case_reviews WHERE id=NEW.review_id;p:=NEW.payload::jsonb;
 SELECT * INTO s FROM monitoring_case_scope(NEW.case_id,9223372036854775807);
 IF COALESCE(NEW.created_txid<>txid_current() OR NEW.created_at>clock_timestamp() OR NEW.expires_at<=NEW.created_at OR NEW.expires_at>NEW.created_at+interval '10 minutes 10 seconds'
 OR r.case_id<>NEW.case_id OR r.user_id<>NEW.user_id OR r.requester_id<>NEW.requester_id OR r.checker_id<>NEW.checker_id
 OR r.scope_version<>s.scope_version OR NOT EXISTS(SELECT 1 FROM deposit_monitoring_case_review_decisions d WHERE d.review_id=r.id AND d.approved)
 OR(p->>'id')::uuid<>NEW.id OR(p->>'reviewId')::uuid<>NEW.review_id OR p->>'reviewHash'<>r.payload_hash
 OR(p->>'caseId')::uuid<>NEW.case_id OR(p->>'customerId')::bigint<>NEW.user_id OR p->>'scopeVersion'<>s.scope_version
 OR(p->>'requesterId')::bigint<>NEW.requester_id OR(p->>'checkerId')::bigint<>NEW.checker_id OR p->>'action'<>NEW.action
 OR p->>'policy'<>'TM_CASE_DISPOSITION_V1' OR p->>'assurance'<>'INTERNAL_CASE_ACTION_NOT_EXTERNAL_REPORT_OR_FINANCIAL_PERMISSION'
 OR(p->>'expiresAt')::timestamptz<>NEW.expires_at OR monitoring_case_closed(NEW.case_id),true) THEN
  RAISE EXCEPTION '사건 후속 업무와 승인된 전체 판단·실행 내용이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_action_binding_check ON deposit_monitoring_case_actions;
CREATE TRIGGER monitoring_case_action_binding_check BEFORE INSERT ON deposit_monitoring_case_actions FOR EACH ROW EXECUTE FUNCTION monitoring_case_action_binding();
CREATE OR REPLACE FUNCTION monitoring_case_action_decision_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a record;r record;s record;
BEGIN
 SELECT * INTO a FROM deposit_monitoring_case_actions WHERE id=NEW.request_id;
 SELECT * INTO r FROM deposit_monitoring_case_reviews WHERE id=a.review_id;
 SELECT * INTO s FROM monitoring_case_scope(a.case_id,9223372036854775807);
 IF COALESCE(NEW.created_txid<>txid_current() OR NEW.decided_at>clock_timestamp() OR a.expires_at<=clock_timestamp()
 OR a.checker_id<>NEW.approver_id OR a.payload_hash<>NEW.payload_hash OR r.scope_version<>s.scope_version
 OR NOT monitoring_investigation_actor(NEW.approver_id,(r.payload::jsonb->>'checkerPerson')::uuid,(r.payload::jsonb->>'checkerVersion')::bigint,NEW.session_id,ARRAY['APPROVER']::varchar[])
 OR NOT EXISTS(SELECT 1 FROM users u WHERE u.id=r.requester_id AND u.role='OPERATOR' AND u.enabled AND u.version=(r.payload::jsonb->>'requesterVersion')::bigint AND u.operator_person_id=(r.payload::jsonb->>'requesterPerson')::uuid)
 OR NOT EXISTS(SELECT 1 FROM operator_authorization_challenges c WHERE c.id=NEW.authorization_id AND c.user_id=NEW.approver_id AND c.session_id=NEW.session_id
 AND c.kind='TM_CASE_ACTION' AND c.request_id=NEW.request_id AND c.payload_hash=NEW.payload_hash AND c.approve=NEW.approved AND c.consumed)
 OR EXISTS(SELECT 1 FROM deposit_monitoring_case_actions other JOIN deposit_monitoring_case_action_decisions d ON d.request_id=other.id AND d.approved
 WHERE other.case_id=a.case_id AND(other.action='REFER_FOR_REPORTING_REVIEW' OR other.payload::jsonb->>'scopeVersion'=s.scope_version)),true) THEN
  RAISE EXCEPTION '사건 후속 결정과 현재 범위·지정 인물·일회 실행 인증이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_action_decision_binding_check ON deposit_monitoring_case_action_decisions;
CREATE TRIGGER monitoring_case_action_decision_binding_check BEFORE INSERT ON deposit_monitoring_case_action_decisions FOR EACH ROW EXECUTE FUNCTION monitoring_case_action_decision_binding();
CREATE OR REPLACE FUNCTION monitoring_case_action_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE action_actor bigint;action_id uuid;action_stamp timestamptz;action_person uuid;
BEGIN
 IF TG_TABLE_NAME='deposit_monitoring_case_actions' THEN action_actor:=NEW.requester_id;action_id:=NEW.id;action_stamp:=NEW.created_at;
 ELSE action_actor:=NEW.approver_id;action_id:=NEW.request_id;action_stamp:=NEW.decided_at;END IF;
 SELECT operator_person_id INTO action_person FROM users WHERE id=action_actor;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.action=TG_ARGV[0] AND a.actor_id=action_actor AND a.actor_person=action_person
 AND a.target='monitoring-case-action:'||action_id::text AND a.outcome='SUCCESS' AND a.detail=NEW.payload_hash AND a.occurred_at>=action_stamp AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '사건 후속 업무에는 실행 내용의 필수 감사가 필요합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_action_audit_check ON deposit_monitoring_case_actions;
CREATE CONSTRAINT TRIGGER monitoring_case_action_audit_check AFTER INSERT ON deposit_monitoring_case_actions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_case_action_audit('MONITORING_CASE_ACTION_PROPOSED');
DROP TRIGGER IF EXISTS monitoring_case_action_decision_audit_check ON deposit_monitoring_case_action_decisions;
CREATE CONSTRAINT TRIGGER monitoring_case_action_decision_audit_check AFTER INSERT ON deposit_monitoring_case_action_decisions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION monitoring_case_action_audit('MONITORING_CASE_ACTION_DECIDED');
CREATE OR REPLACE FUNCTION monitoring_closed_case_member_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF monitoring_case_closed(NEW.case_id) THEN RAISE EXCEPTION '종결된 사건의 새 신호는 새 사건에 연결해야 합니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_closed_case_member_check ON deposit_monitoring_case_assessments;
CREATE TRIGGER monitoring_closed_case_member_check BEFORE INSERT ON deposit_monitoring_case_assessments FOR EACH ROW EXECUTE FUNCTION monitoring_closed_case_member_guard();
-- 현재 위치만 갱신한다. 종결 원본·사건 구성원은 수정하지 않고 새 사건의 생성 트랜잭션에만 위치를 바꿀 수 있다.
CREATE OR REPLACE FUNCTION monitoring_case_head_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' THEN
  IF NEW.user_id<>OLD.user_id OR NEW.case_id=OLD.case_id OR NOT monitoring_case_closed(OLD.case_id) THEN
   RAISE EXCEPTION '사건 위치는 같은 고객의 종결된 사건에서 새 사건으로만 이동할 수 있습니다';END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM deposit_monitoring_cases c WHERE c.id=NEW.case_id AND c.user_id=NEW.user_id AND c.created_txid=txid_current()) THEN
  RAISE EXCEPTION '조사 사건 현재 위치와 같은 트랜잭션의 고객 사건이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS monitoring_case_heads_immutable ON deposit_monitoring_case_heads;
CREATE TRIGGER monitoring_case_heads_immutable BEFORE DELETE OR TRUNCATE ON deposit_monitoring_case_heads FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS monitoring_case_head_binding_check ON deposit_monitoring_case_heads;
CREATE TRIGGER monitoring_case_head_binding_check BEFORE INSERT OR UPDATE ON deposit_monitoring_case_heads FOR EACH ROW EXECUTE FUNCTION monitoring_case_head_binding();
DROP TRIGGER IF EXISTS monitoring_case_actions_immutable ON deposit_monitoring_case_actions;
CREATE TRIGGER monitoring_case_actions_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_case_actions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS monitoring_case_action_decisions_immutable ON deposit_monitoring_case_action_decisions;
CREATE TRIGGER monitoring_case_action_decisions_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON deposit_monitoring_case_action_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
