-- 운영 일정과 경제적 원본을 구분한다. 시도/대기는 바뀌어도 처음 정한 주문·소유자·거래쌍은 바꾸지 않는다.
CREATE OR REPLACE FUNCTION order_cancellation_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original record;
BEGIN
 IF TG_OP='INSERT' THEN
  SELECT * INTO original FROM order_submissions WHERE order_id=NEW.order_id;
  IF NOT FOUND OR original.user_id<>NEW.user_id OR original.base_mint<>NEW.base_mint OR original.quote_mint<>NEW.quote_mint OR NEW.attempts<>0 OR NEW.acknowledged_at IS NOT NULL THEN
   RAISE EXCEPTION '취소 요청과 불변 접수 원본이 다릅니다';END IF;
  -- 감사와 결합할 시각은 호스트 시계의 추정 대신 실제 저장 시각이다.
  NEW.requested_at:=clock_timestamp();NEW.updated_at:=NEW.requested_at;
 ELSE
  NEW.updated_at:=clock_timestamp();
  IF NEW.order_id<>OLD.order_id OR NEW.user_id<>OLD.user_id OR NEW.base_mint<>OLD.base_mint OR NEW.quote_mint<>OLD.quote_mint OR NEW.requested_at<>OLD.requested_at
   OR NEW.attempts<OLD.attempts OR NEW.attempts>OLD.attempts+1 OR(OLD.acknowledged_at IS NOT NULL AND (NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at OR NEW.attempts<>OLD.attempts OR NEW.next_attempt_at<>OLD.next_attempt_at)) THEN
   RAISE EXCEPTION '취소 원본 또는 이미 확인한 응답을 덮어쓸 수 없습니다';END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS order_cancellation_binding_check ON order_cancellations;
CREATE TRIGGER order_cancellation_binding_check BEFORE INSERT OR UPDATE ON order_cancellations FOR EACH ROW EXECUTE FUNCTION order_cancellation_binding();
CREATE OR REPLACE FUNCTION order_cancellation_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE action_name text;required_detail text;stamp timestamptz;actor bigint;
BEGIN
 IF TG_OP='INSERT' THEN
  -- 고객이 직접 남긴 요청과 검증된 시스템 위험 조치를 구분한다. 시스템을 고객으로 위장해 기록하지 않는다.
  IF EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id=NEW.user_id AND a.action='ORDER_CANCEL_REQUESTED' AND a.target='order:'||NEW.order_id::text
   AND a.outcome='SUCCESS' AND a.detail='INTENT_ONLY_ENGINE_RESULT_FOLLOWS' AND a.occurred_at>=NEW.requested_at AND length(a.source_address)>0) THEN RETURN NULL;END IF;
  IF EXISTS(SELECT 1 FROM bitcoin_chain_risk_order_cancellations c JOIN security_audit a ON a.actor_id IS NULL AND a.action='BTC_CHAIN_RISK_CANCEL_PLANNED'
   AND a.target='order:'||NEW.order_id::text AND a.detail=c.audit_detail AND a.outcome='SUCCESS' AND a.occurred_at>=NEW.requested_at AND length(a.source_address)>0
   WHERE c.order_id=NEW.order_id AND c.user_id=NEW.user_id AND c.created_txid=txid_current()) THEN RETURN NULL;END IF;
  RAISE EXCEPTION '취소 요청에는 고객의 실제 의도 또는 검증된 시스템 위험 근거·감사가 필요합니다';
 ELSE
  stamp:=NEW.updated_at;actor:=NULL;
  IF NEW.attempts>OLD.attempts THEN action_name:='ORDER_CANCEL_ATTEMPT_STARTED';required_detail:='ATTEMPT:'||NEW.attempts::text;
  ELSIF NEW.acknowledged_at IS DISTINCT FROM OLD.acknowledged_at THEN action_name:='ORDER_CANCEL_ATTEMPT_FINISHED';required_detail:='ATTEMPT:'||NEW.attempts::text||':ACKNOWLEDGED';
  ELSIF NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at THEN action_name:='ORDER_CANCEL_ATTEMPT_FINISHED';required_detail:='ATTEMPT:'||NEW.attempts::text||':UNCERTAIN';
  ELSE RETURN NULL;END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NOT DISTINCT FROM actor AND a.action=action_name AND a.target='order:'||NEW.order_id::text
  AND a.outcome='SUCCESS' AND a.detail=required_detail AND a.occurred_at>=stamp AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '취소 요청과 재확인 변경에는 같은 내용의 필수 감사가 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS order_cancellation_audit_check ON order_cancellations;
CREATE CONSTRAINT TRIGGER order_cancellation_audit_check AFTER INSERT OR UPDATE ON order_cancellations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION order_cancellation_audit();
DROP TRIGGER IF EXISTS order_cancellation_preserve ON order_cancellations;
CREATE TRIGGER order_cancellation_preserve BEFORE DELETE OR TRUNCATE ON order_cancellations FOR EACH STATEMENT EXECUTE FUNCTION cex_ledger_immutable();
