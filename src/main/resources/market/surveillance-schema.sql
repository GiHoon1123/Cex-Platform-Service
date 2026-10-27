-- 엔진 결과를 반영한 트랜잭션 안에서 감시 입력을 함께 고정한다. 감시기는 엔진 코어나 Kafka 원문을 다시 해석하지 않는다.
CREATE TABLE IF NOT EXISTS market_activity_events (
 id bigserial PRIMARY KEY, source_event_id varchar(120) NOT NULL UNIQUE REFERENCES engine_event_receipts(event_id),
 event_kind varchar(16) NOT NULL CHECK(event_kind IN ('TRADE','CANCEL')),
 event_at timestamptz NOT NULL, user_id bigint REFERENCES users(id), buyer_id bigint REFERENCES users(id), seller_id bigint REFERENCES users(id),
 order_id bigint REFERENCES orders(id), buy_order_id bigint REFERENCES orders(id), sell_order_id bigint REFERENCES orders(id),
 base_asset varchar(20) NOT NULL, quote_asset varchar(20) NOT NULL, side varchar(8),
 price numeric(30,9), original_amount numeric(30,9), filled_amount numeric(30,9),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK((event_kind='TRADE' AND user_id IS NULL AND order_id IS NULL AND buyer_id IS NOT NULL AND seller_id IS NOT NULL
   AND buy_order_id IS NOT NULL AND sell_order_id IS NOT NULL AND side IS NULL AND price>0 AND original_amount IS NULL AND filled_amount>0)
  OR(event_kind='CANCEL' AND user_id IS NOT NULL AND order_id IS NOT NULL AND buyer_id IS NULL AND seller_id IS NULL
   AND buy_order_id IS NULL AND sell_order_id IS NULL AND side IN ('buy','sell') AND price>0 AND original_amount>0 AND filled_amount>=0 AND filled_amount<original_amount))
);
CREATE INDEX IF NOT EXISTS market_activity_cancel_pattern ON market_activity_events(user_id,base_asset,quote_asset,side,event_at,id) WHERE event_kind='CANCEL';
CREATE INDEX IF NOT EXISTS market_activity_trade_market ON market_activity_events(base_asset,quote_asset,event_at,id) WHERE event_kind='TRADE';

CREATE OR REPLACE FUNCTION market_activity_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NOT EXISTS(SELECT 1 FROM engine_event_receipts WHERE event_id=NEW.source_event_id) THEN
  RAISE EXCEPTION '시장 감시 입력은 같은 금융 결과 트랜잭션에 묶여야 합니다';END IF;
 IF NEW.event_kind='TRADE' AND NOT EXISTS(SELECT 1 FROM trades t WHERE t.engine_event_id=NEW.source_event_id
  AND t.buy_order_id=NEW.buy_order_id AND t.sell_order_id=NEW.sell_order_id AND t.buyer_id=NEW.buyer_id AND t.seller_id=NEW.seller_id
  AND t.base_mint=NEW.base_asset AND t.quote_mint=NEW.quote_asset AND t.price=NEW.price AND t.amount=NEW.filled_amount
  AND t.created_at=NEW.event_at AT TIME ZONE 'Asia/Seoul') THEN RAISE EXCEPTION '감시 체결 입력이 확정 체결과 다릅니다';END IF;
 IF NEW.event_kind='CANCEL' AND NOT EXISTS(SELECT 1 FROM orders o WHERE o.id=NEW.order_id AND o.user_id=NEW.user_id
  AND o.status='cancelled' AND o.order_type=NEW.side AND o.order_side='limit' AND o.base_mint=NEW.base_asset
  AND o.quote_mint=NEW.quote_asset AND o.price=NEW.price AND o.amount=NEW.original_amount AND o.filled_amount=NEW.filled_amount)
  THEN RAISE EXCEPTION '감시 취소 입력이 확정 주문과 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_activity_binding_check ON market_activity_events;
CREATE TRIGGER market_activity_binding_check BEFORE INSERT ON market_activity_events FOR EACH ROW EXECUTE FUNCTION market_activity_binding();

CREATE TABLE IF NOT EXISTS market_surveillance_state (
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton), observed_event bigint NOT NULL DEFAULT 0 CHECK(observed_event>=0),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO market_surveillance_state(singleton) VALUES(true) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS market_surveillance_assessments (
 id uuid PRIMARY KEY, input_event bigint NOT NULL UNIQUE REFERENCES market_activity_events(id), policy_id varchar(40) NOT NULL,
 outcome varchar(24) NOT NULL CHECK(outcome IN ('NO_SIGNAL','REVIEW_REQUIRED')),
 reason varchar(40) NOT NULL CHECK(reason IN ('NO_SIGNAL_IN_THIS_POLICY','RAPID_UNFILLED_CANCELLATION','ABRUPT_EXECUTION_PRICE','SELF_MATCH_INVARIANT_BREACH')),
 source_document text NOT NULL CHECK(octet_length(source_document)<=262144), source_hash varchar(64) NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(), created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK(source_hash=encode(sha256(convert_to(source_document,'UTF8')),'hex')),
 CHECK((outcome='NO_SIGNAL' AND reason='NO_SIGNAL_IN_THIS_POLICY') OR(outcome='REVIEW_REQUIRED' AND reason<>'NO_SIGNAL_IN_THIS_POLICY'))
);
CREATE TABLE IF NOT EXISTS market_surveillance_signals (
 id uuid PRIMARY KEY, assessment_id uuid NOT NULL UNIQUE REFERENCES market_surveillance_assessments(id),
 status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','UNDER_REVIEW','CLOSED')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS market_surveillance_signal_queue ON market_surveillance_signals(status,created_at,id);

CREATE OR REPLACE FUNCTION market_surveillance_assessment_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NOT EXISTS(SELECT 1 FROM market_activity_events WHERE id=NEW.input_event)
  OR jsonb_typeof(NEW.source_document::jsonb)<>'object' OR (NEW.source_document::jsonb->>'inputEvent')::bigint<>NEW.input_event THEN
  RAISE EXCEPTION '시장 감시 평가 근거가 입력과 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_surveillance_assessment_binding_check ON market_surveillance_assessments;
CREATE TRIGGER market_surveillance_assessment_binding_check BEFORE INSERT ON market_surveillance_assessments FOR EACH ROW EXECUTE FUNCTION market_surveillance_assessment_binding();
CREATE OR REPLACE FUNCTION market_surveillance_state_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.singleton<>OLD.singleton OR NEW.observed_event<OLD.observed_event THEN RAISE EXCEPTION '시장 감시 위치를 되돌릴 수 없습니다';END IF;
 IF NEW.observed_event<>OLD.observed_event AND (NEW.observed_event IS DISTINCT FROM
   (SELECT min(id) FROM market_activity_events WHERE id>OLD.observed_event)
   OR NOT EXISTS(SELECT 1 FROM market_surveillance_assessments WHERE input_event=NEW.observed_event AND created_txid=txid_current()))
  THEN RAISE EXCEPTION '다음 입력의 확정 평가 없이 감시 위치를 옮길 수 없습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_surveillance_state_binding_check ON market_surveillance_state;
CREATE TRIGGER market_surveillance_state_binding_check BEFORE UPDATE ON market_surveillance_state FOR EACH ROW EXECUTE FUNCTION market_surveillance_state_binding();

CREATE OR REPLACE FUNCTION market_surveillance_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION '시장 감시 근거는 변경하거나 삭제할 수 없습니다';END $$;
DROP TRIGGER IF EXISTS market_activity_immutable ON market_activity_events;
CREATE TRIGGER market_activity_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON market_activity_events FOR EACH STATEMENT EXECUTE FUNCTION market_surveillance_immutable();
DROP TRIGGER IF EXISTS market_surveillance_assessment_immutable ON market_surveillance_assessments;
CREATE TRIGGER market_surveillance_assessment_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON market_surveillance_assessments FOR EACH STATEMENT EXECUTE FUNCTION market_surveillance_immutable();
DROP TRIGGER IF EXISTS market_surveillance_signal_preserve ON market_surveillance_signals;
CREATE TRIGGER market_surveillance_signal_preserve BEFORE DELETE OR TRUNCATE ON market_surveillance_signals FOR EACH STATEMENT EXECUTE FUNCTION market_surveillance_immutable();

-- 시장 전체의 신규 주문 허용 상태는 플랫폼 DB의 한 행이 권위다. 취소 요청은 이 상태와 무관하게 계속 허용한다.
CREATE TABLE IF NOT EXISTS market_trading_states (
 base_asset varchar(20) NOT NULL,quote_asset varchar(20) NOT NULL,state varchar(12) NOT NULL CHECK(state IN ('OPEN','HALTED')),
 version bigint NOT NULL CHECK(version>=0),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),PRIMARY KEY(base_asset,quote_asset)
);
INSERT INTO market_trading_states(base_asset,quote_asset,state,version) VALUES('BTC','USDT','OPEN',0) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS market_state_requests (
 id uuid PRIMARY KEY,base_asset varchar(20) NOT NULL,quote_asset varchar(20) NOT NULL,expected_state varchar(12) NOT NULL,
 expected_version bigint NOT NULL,desired_state varchar(12) NOT NULL,signal_id uuid REFERENCES market_surveillance_signals(id),
 requester_id bigint NOT NULL REFERENCES users(id),requester_person uuid NOT NULL,requester_version bigint NOT NULL,
 checker_id bigint NOT NULL REFERENCES users(id),checker_person uuid NOT NULL,checker_version bigint NOT NULL,request_key uuid NOT NULL,
 reason varchar(300) NOT NULL,payload text NOT NULL,payload_hash varchar(64) NOT NULL,expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 kind varchar(24) NOT NULL DEFAULT 'MARKET_STATE' CHECK(kind='MARKET_STATE'),
 UNIQUE(requester_id,request_key),CHECK(expected_state IN ('OPEN','HALTED') AND desired_state IN ('OPEN','HALTED') AND expected_state<>desired_state),
 CHECK(requester_id<>checker_id AND requester_person<>checker_person AND length(reason) BETWEEN 1 AND 300 AND payload_hash~'^[0-9a-f]{64}$'),
 CHECK((desired_state='HALTED' AND signal_id IS NOT NULL) OR(desired_state='OPEN' AND signal_id IS NULL)),
 FOREIGN KEY(base_asset,quote_asset) REFERENCES market_trading_states(base_asset,quote_asset),
 FOREIGN KEY(kind,id) REFERENCES operator_approval_bindings(kind,request_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE IF NOT EXISTS market_state_views (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES market_state_requests(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,payload_hash varchar(64) NOT NULL,
 viewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS market_state_decisions (
 request_id uuid PRIMARY KEY REFERENCES market_state_requests(id),approver_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 approved boolean NOT NULL,payload_hash varchar(64) NOT NULL,authorization_id uuid NOT NULL UNIQUE,
 decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS market_state_changes (
 request_id uuid PRIMARY KEY REFERENCES market_state_decisions(request_id),base_asset varchar(20) NOT NULL,quote_asset varchar(20) NOT NULL,
 previous_state varchar(12) NOT NULL,previous_version bigint NOT NULL,next_state varchar(12) NOT NULL,next_version bigint NOT NULL,
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 FOREIGN KEY(base_asset,quote_asset) REFERENCES market_trading_states(base_asset,quote_asset),CHECK(next_version=previous_version+1)
);

CREATE OR REPLACE FUNCTION market_state_request_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_market record;maker record;checker record;
BEGIN
 SELECT * INTO current_market FROM market_trading_states WHERE base_asset=NEW.base_asset AND quote_asset=NEW.quote_asset FOR UPDATE;
 SELECT * INTO maker FROM users WHERE id=NEW.requester_id FOR UPDATE;SELECT * INTO checker FROM users WHERE id=NEW.checker_id FOR UPDATE;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.created_at>clock_timestamp() OR NEW.expires_at<=NEW.created_at OR NEW.expires_at>NEW.created_at+interval '10 minutes 10 seconds'
  OR current_market.state<>NEW.expected_state OR current_market.version<>NEW.expected_version
  OR maker.role<>'OPERATOR' OR checker.role<>'APPROVER' OR NOT maker.enabled OR NOT checker.enabled
  OR maker.operator_person_id<>NEW.requester_person OR checker.operator_person_id<>NEW.checker_person
  OR maker.version<>NEW.requester_version OR checker.version<>NEW.checker_version
  OR maker.operator_access_expires_at<=clock_timestamp() OR checker.operator_access_expires_at<=clock_timestamp()
  OR maker.operator_recovery_request_id IS NOT NULL OR checker.operator_recovery_request_id IS NOT NULL
  OR (NEW.desired_state='HALTED' AND NOT EXISTS(SELECT 1 FROM market_surveillance_signals WHERE id=NEW.signal_id))
  OR NOT EXISTS(SELECT 1 FROM operator_approval_bindings b WHERE b.kind='MARKET_STATE' AND b.request_id=NEW.id AND b.requester_id=NEW.requester_id
    AND b.requester_version=NEW.requester_version AND b.payload_hash=NEW.payload_hash AND b.expires_at=NEW.expires_at),true)
 THEN RAISE EXCEPTION '현재 시장 상태와 분리된 담당자·승인 요청입니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS market_state_request_binding_check ON market_state_requests;
CREATE CONSTRAINT TRIGGER market_state_request_binding_check AFTER INSERT ON market_state_requests DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION market_state_request_binding();
CREATE OR REPLACE FUNCTION market_state_view_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE request record;actor record;
BEGIN SELECT * INTO request FROM market_state_requests WHERE id=NEW.request_id;SELECT * INTO actor FROM users WHERE id=NEW.actor_id FOR UPDATE;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.viewed_at>clock_timestamp() OR NEW.payload_hash<>request.payload_hash
  OR NEW.actor_id NOT IN(request.requester_id,request.checker_id) OR actor.operator_person_id<>NEW.actor_person OR actor.version<>NEW.actor_version
  OR NOT actor.enabled OR actor.role NOT IN('OPERATOR','APPROVER') OR actor.operator_access_expires_at<=clock_timestamp()
  OR actor.operator_recovery_request_id IS NOT NULL,true) THEN RAISE EXCEPTION '현재 지정 인물의 시장 상태 요청만 열람할 수 있습니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_state_view_binding_check ON market_state_views;
CREATE TRIGGER market_state_view_binding_check BEFORE INSERT ON market_state_views FOR EACH ROW EXECUTE FUNCTION market_state_view_binding();
CREATE OR REPLACE FUNCTION market_state_decision_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE request record;checker record;
BEGIN SELECT * INTO request FROM market_state_requests WHERE id=NEW.request_id;SELECT * INTO checker FROM users WHERE id=request.checker_id FOR UPDATE;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.decided_at>request.expires_at OR NEW.approver_id<>request.checker_id OR NEW.payload_hash<>request.payload_hash
  OR checker.role<>'APPROVER' OR NOT checker.enabled OR checker.operator_person_id<>request.checker_person OR checker.version<>request.checker_version
  OR checker.operator_access_expires_at<=clock_timestamp() OR checker.operator_recovery_request_id IS NOT NULL
  OR NOT EXISTS(SELECT 1 FROM market_state_views v WHERE v.request_id=request.id AND v.actor_id=NEW.approver_id AND v.actor_person=request.checker_person
    AND v.actor_version=request.checker_version AND v.session_id=NEW.session_id AND v.payload_hash=request.payload_hash AND v.viewed_at>=clock_timestamp()-interval '5 minutes')
  OR NOT EXISTS(SELECT 1 FROM operator_authorization_challenges c WHERE c.id=NEW.authorization_id AND c.user_id=NEW.approver_id AND c.session_id=NEW.session_id
    AND c.kind='MARKET_STATE' AND c.request_id=request.id AND c.payload_hash=request.payload_hash AND c.approve=NEW.approved AND c.consumed),true)
 THEN RAISE EXCEPTION '시장 상태 결정과 현재 열람·내용 결합 서명이 다릅니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_state_decision_binding_check ON market_state_decisions;
CREATE TRIGGER market_state_decision_binding_check BEFORE INSERT ON market_state_decisions FOR EACH ROW EXECUTE FUNCTION market_state_decision_binding();
CREATE OR REPLACE FUNCTION market_state_change_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE request record;decision record;current_market record;
BEGIN SELECT * INTO request FROM market_state_requests WHERE id=NEW.request_id;SELECT * INTO decision FROM market_state_decisions WHERE request_id=NEW.request_id;
 SELECT * INTO current_market FROM market_trading_states WHERE base_asset=NEW.base_asset AND quote_asset=NEW.quote_asset;
 IF coalesce(NEW.created_txid<>txid_current() OR NOT decision.approved OR NEW.changed_at>request.expires_at
  OR NEW.base_asset<>request.base_asset OR NEW.quote_asset<>request.quote_asset OR NEW.previous_state<>request.expected_state
  OR NEW.previous_version<>request.expected_version OR NEW.next_state<>request.desired_state OR current_market.state<>NEW.next_state OR current_market.version<>NEW.next_version,true)
 THEN RAISE EXCEPTION '승인 요청과 정확히 일치하는 시장 상태 변경이 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS market_state_change_binding_check ON market_state_changes;
CREATE CONSTRAINT TRIGGER market_state_change_binding_check AFTER INSERT ON market_state_changes DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION market_state_change_binding();
CREATE OR REPLACE FUNCTION market_state_update_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.base_asset<>OLD.base_asset OR NEW.quote_asset<>OLD.quote_asset OR NEW.version<>OLD.version+1 OR NEW.state=OLD.state
  OR NOT EXISTS(SELECT 1 FROM market_state_changes c JOIN market_state_decisions d ON d.request_id=c.request_id AND d.approved
    WHERE c.base_asset=NEW.base_asset AND c.quote_asset=NEW.quote_asset AND c.previous_state=OLD.state AND c.previous_version=OLD.version
      AND c.next_state=NEW.state AND c.next_version=NEW.version AND c.created_txid=txid_current())
 THEN RAISE EXCEPTION '독립 승인된 시장 상태 변경만 허용합니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_state_update_binding_check ON market_trading_states;
CREATE TRIGGER market_state_update_binding_check BEFORE UPDATE ON market_trading_states FOR EACH ROW EXECUTE FUNCTION market_state_update_binding();

CREATE OR REPLACE FUNCTION market_state_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint;action_name text;target_name text;detail_text text;stamp timestamptz;
BEGIN
 IF TG_TABLE_NAME='market_state_requests' THEN actor:=NEW.requester_id;action_name:='MARKET_STATE_PROPOSED';target_name:='market-state:'||NEW.id;detail_text:=NEW.payload_hash;stamp:=NEW.created_at;
 ELSIF TG_TABLE_NAME='market_state_views' THEN actor:=NEW.actor_id;action_name:='MARKET_STATE_VIEWED';target_name:='market-state-view:'||NEW.id;detail_text:=NEW.payload_hash;stamp:=NEW.viewed_at;
 ELSIF TG_TABLE_NAME='market_state_decisions' THEN actor:=NEW.approver_id;action_name:='MARKET_STATE_DECIDED';target_name:='market-state:'||NEW.request_id;detail_text:=NEW.payload_hash;stamp:=NEW.decided_at;
 ELSE actor:=NEW.approver_id;END IF;
 IF TG_TABLE_NAME<>'market_state_changes' AND NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id=actor AND a.action=action_name AND a.target=target_name AND a.outcome='SUCCESS' AND a.detail=detail_text AND a.occurred_at>=stamp)
 THEN RAISE EXCEPTION '시장 상태 업무와 필수 감사는 함께 보존해야 합니다';END IF;RETURN NULL;
END $$;
CREATE OR REPLACE FUNCTION market_state_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION '시장 상태 요청·열람·결정·변경 이력은 변경하거나 삭제할 수 없습니다';END $$;
DO $$ DECLARE table_name text;BEGIN
 FOREACH table_name IN ARRAY ARRAY['market_state_requests','market_state_views','market_state_decisions','market_state_changes'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS %I ON %I',table_name||'_immutable',table_name);
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION market_state_immutable()',table_name||'_immutable',table_name);
  IF table_name<>'market_state_changes' THEN
   EXECUTE format('DROP TRIGGER IF EXISTS market_state_audit_check ON %I',table_name);
   EXECUTE format('CREATE CONSTRAINT TRIGGER market_state_audit_check AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION market_state_audit()',table_name);
  END IF;
 END LOOP;
END $$;

-- 자동 신호는 사람의 사건 판단과 분리한다. 현재 상태는 작업 큐이고 모든 판단은 별도 불변 사건으로 남긴다.
CREATE TABLE IF NOT EXISTS market_surveillance_cases (
 id uuid PRIMARY KEY,signal_id uuid NOT NULL UNIQUE REFERENCES market_surveillance_signals(id),status varchar(24) NOT NULL CHECK(status IN ('OPEN','UNDER_REVIEW','CLOSED_NO_ACTION','ESCALATED')),
 assignee_id bigint REFERENCES users(id),version bigint NOT NULL DEFAULT 0 CHECK(version>=0),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((status='OPEN' AND assignee_id IS NULL) OR(status<>'OPEN' AND assignee_id IS NOT NULL))
);
CREATE TABLE IF NOT EXISTS market_surveillance_case_events (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES market_surveillance_cases(id),event_type varchar(24) NOT NULL CHECK(event_type IN ('OPENED','CLAIMED','OBSERVATION','CLOSED_NO_ACTION','ESCALATED')),
 actor_id bigint REFERENCES users(id),actor_person uuid,actor_version bigint,session_id uuid,next_status varchar(24),next_assignee bigint REFERENCES users(id),
 detail varchar(1000) NOT NULL,recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK((event_type='OPENED' AND actor_id IS NULL AND actor_person IS NULL AND actor_version IS NULL AND session_id IS NULL AND next_status='OPEN' AND next_assignee IS NULL)
  OR(event_type<>'OPENED' AND actor_id IS NOT NULL AND actor_person IS NOT NULL AND actor_version IS NOT NULL AND session_id IS NOT NULL)),
 CHECK((event_type='OBSERVATION' AND next_status IS NULL AND next_assignee IS NULL)
  OR(event_type='OPENED' AND next_status='OPEN' AND next_assignee IS NULL)
  OR(event_type='CLAIMED' AND next_status='UNDER_REVIEW' AND next_assignee=actor_id)
  OR(event_type IN('CLOSED_NO_ACTION','ESCALATED') AND next_status=event_type AND next_assignee=actor_id))
);
CREATE INDEX IF NOT EXISTS market_surveillance_case_queue ON market_surveillance_cases(status,updated_at,id);
CREATE INDEX IF NOT EXISTS market_surveillance_case_history ON market_surveillance_case_events(case_id,recorded_at,id);
CREATE OR REPLACE FUNCTION market_surveillance_case_event_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor record;current_case record;
BEGIN
 SELECT * INTO current_case FROM market_surveillance_cases WHERE id=NEW.case_id;
 IF NEW.created_txid<>txid_current() OR NEW.recorded_at>clock_timestamp() THEN RAISE EXCEPTION '시장 조사 사건 시각이 올바르지 않습니다';END IF;
 IF NEW.event_type='OPENED' THEN
  IF current_case.status<>'OPEN' OR current_case.signal_id IS NULL THEN RAISE EXCEPTION '실제 신호의 열린 사건만 생성할 수 있습니다';END IF;RETURN NEW;
 END IF;
 SELECT * INTO actor FROM users WHERE id=NEW.actor_id FOR UPDATE;
 IF coalesce(actor.role<>'OPERATOR' OR NOT actor.enabled OR actor.operator_person_id<>NEW.actor_person OR actor.version<>NEW.actor_version
  OR actor.operator_access_expires_at<=clock_timestamp() OR actor.operator_recovery_request_id IS NOT NULL
  OR NOT EXISTS(SELECT 1 FROM refresh_tokens t WHERE t.user_id=NEW.actor_id AND t.session_id=NEW.session_id AND NOT t.revoked
    AND t.expires_at>localtimestamp AND t.mfa_verified_at>=localtimestamp-interval '5 minutes' AND t.mfa_credential_fingerprint~'^[0-9a-f]{64}$'),true)
 THEN RAISE EXCEPTION '현재 인증된 사건 담당자만 기록할 수 있습니다';END IF;
 IF NEW.event_type='CLAIMED' AND current_case.status<>'OPEN' THEN RAISE EXCEPTION '열린 사건만 배정할 수 있습니다';END IF;
 IF NEW.event_type IN('OBSERVATION','CLOSED_NO_ACTION','ESCALATED') AND(current_case.status<>'UNDER_REVIEW' OR current_case.assignee_id<>NEW.actor_id)
 THEN RAISE EXCEPTION '현재 담당자만 조사하거나 종결할 수 있습니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_surveillance_case_event_binding_check ON market_surveillance_case_events;
CREATE TRIGGER market_surveillance_case_event_binding_check BEFORE INSERT ON market_surveillance_case_events FOR EACH ROW EXECUTE FUNCTION market_surveillance_case_event_binding();
CREATE OR REPLACE FUNCTION market_surveillance_case_insert_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM market_surveillance_signals WHERE id=NEW.signal_id)
  OR NOT EXISTS(SELECT 1 FROM market_surveillance_case_events e WHERE e.case_id=NEW.id AND e.event_type='OPENED' AND e.created_txid=txid_current())
 THEN RAISE EXCEPTION '시장 신호와 열린 사건 이력이 함께 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS market_surveillance_case_insert_binding_check ON market_surveillance_cases;
CREATE CONSTRAINT TRIGGER market_surveillance_case_insert_binding_check AFTER INSERT ON market_surveillance_cases DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION market_surveillance_case_insert_binding();
CREATE OR REPLACE FUNCTION market_surveillance_case_update_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.id<>OLD.id OR NEW.signal_id<>OLD.signal_id OR NEW.created_at<>OLD.created_at OR NEW.version<>OLD.version+1 OR NEW.status=OLD.status
  OR NOT EXISTS(SELECT 1 FROM market_surveillance_case_events e WHERE e.case_id=NEW.id AND e.created_txid=txid_current()
    AND e.next_status=NEW.status AND e.next_assignee IS NOT DISTINCT FROM NEW.assignee_id)
 THEN RAISE EXCEPTION '시장 사건 현재 상태에는 같은 트랜잭션의 이력이 필요합니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS market_surveillance_case_update_binding_check ON market_surveillance_cases;
CREATE TRIGGER market_surveillance_case_update_binding_check BEFORE UPDATE ON market_surveillance_cases FOR EACH ROW EXECUTE FUNCTION market_surveillance_case_update_binding();
DROP TRIGGER IF EXISTS market_surveillance_case_event_immutable ON market_surveillance_case_events;
CREATE TRIGGER market_surveillance_case_event_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON market_surveillance_case_events FOR EACH STATEMENT EXECUTE FUNCTION market_surveillance_immutable();
DROP TRIGGER IF EXISTS market_surveillance_case_preserve ON market_surveillance_cases;
CREATE TRIGGER market_surveillance_case_preserve BEFORE DELETE OR TRUNCATE ON market_surveillance_cases FOR EACH STATEMENT EXECUTE FUNCTION market_surveillance_immutable();
CREATE OR REPLACE FUNCTION market_surveillance_case_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE action_name text;target_name text;detail_text text;signal uuid;current_version bigint;
BEGIN
 SELECT signal_id,version INTO signal,current_version FROM market_surveillance_cases WHERE id=NEW.case_id;
 target_name:='market-surveillance-case:'||NEW.case_id;
 IF NEW.event_type='OPENED' THEN action_name:='MARKET_SURVEILLANCE_CASE_OPENED';detail_text:='SIGNAL:'||signal;
 ELSIF NEW.event_type='CLAIMED' THEN action_name:='MARKET_CASE_CLAIMED';detail_text:='VERSION:'||current_version;
 ELSIF NEW.event_type='OBSERVATION' THEN action_name:='MARKET_CASE_OBSERVED';detail_text:='EVENT:'||NEW.id;
 ELSE action_name:='MARKET_CASE_CLOSED';detail_text:=NEW.event_type||':VERSION:'||current_version;END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NOT DISTINCT FROM NEW.actor_id AND a.action=action_name AND a.target=target_name
  AND a.outcome='SUCCESS' AND a.detail=detail_text AND a.occurred_at>=NEW.recorded_at)
 THEN RAISE EXCEPTION '시장 조사 사건과 필수 감사는 함께 보존해야 합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS market_surveillance_case_audit_check ON market_surveillance_case_events;
CREATE CONSTRAINT TRIGGER market_surveillance_case_audit_check AFTER INSERT ON market_surveillance_case_events DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION market_surveillance_case_audit();
