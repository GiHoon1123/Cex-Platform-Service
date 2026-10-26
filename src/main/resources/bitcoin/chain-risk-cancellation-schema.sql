-- 자동 조치는 원래 금융 사실의 취소가 아니다. 현재 미해결 위험과 취소할 지정가 원본을 불변 연결한다.
CREATE TABLE IF NOT EXISTS bitcoin_chain_risk_order_cancellations (
 order_id bigint PRIMARY KEY REFERENCES order_cancellations(order_id) DEFERRABLE INITIALLY DEFERRED,
 user_id bigint NOT NULL REFERENCES users(id),receipt_id uuid NOT NULL REFERENCES bitcoin_credit_authorizations(receipt_id),
 observation_id bigint NOT NULL REFERENCES bitcoin_transaction_observations(id),
 policy varchar(64) NOT NULL CHECK(policy='BTC_CHAIN_RISK_CANCEL_LIMIT_ORDERS_V1'),
 audit_detail text GENERATED ALWAYS AS ('RECEIPT:'||receipt_id::text||':OBSERVATION:'||observation_id::text||':POLICY:'||policy) STORED,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE INDEX IF NOT EXISTS bitcoin_risk_cancel_receipt ON bitcoin_chain_risk_order_cancellations(receipt_id,order_id);
CREATE OR REPLACE FUNCTION bitcoin_chain_risk_cancel_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original record;
BEGIN
 -- 원본 조회보다 먼저 고객→주문 순으로 잠근다. 위험 해제·새 관찰·고객의 기존 취소와 순서를 고정한다.
 PERFORM id FROM users WHERE id=NEW.user_id FOR UPDATE;
 PERFORM id FROM orders WHERE id=NEW.order_id FOR UPDATE;
 SELECT s.*,o.user_id owner_id,o.status INTO original FROM order_submissions s JOIN orders o ON o.id=s.order_id WHERE s.order_id=NEW.order_id;
 IF COALESCE(NOT FOUND OR NEW.created_txid<>txid_current() OR NEW.created_at<transaction_timestamp() OR NEW.created_at>clock_timestamp()
  OR original.user_id<>NEW.user_id OR original.owner_id<>NEW.user_id OR original.order_side<>'limit'
  OR original.status IS NULL OR original.status IN('filled','cancelled','rejected','expired')
  OR EXISTS(SELECT 1 FROM order_cancellations WHERE order_id=NEW.order_id)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_credit_chain_risks r WHERE r.user_id=NEW.user_id AND r.receipt_id=NEW.receipt_id AND r.observation_id=NEW.observation_id),true) THEN
  RAISE EXCEPTION '자동 취소 근거와 현재 미해결 위험·본인 지정가 원본이 다릅니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_risk_cancel_binding_check ON bitcoin_chain_risk_order_cancellations;
CREATE TRIGGER bitcoin_chain_risk_cancel_binding_check BEFORE INSERT ON bitcoin_chain_risk_order_cancellations FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_risk_cancel_binding();
CREATE OR REPLACE FUNCTION bitcoin_chain_risk_cancel_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM order_cancellations q JOIN security_audit a ON a.actor_id IS NULL AND a.action='BTC_CHAIN_RISK_CANCEL_PLANNED'
  AND a.target='order:'||NEW.order_id::text AND a.outcome='SUCCESS' AND a.detail=NEW.audit_detail AND a.occurred_at>=q.requested_at AND length(a.source_address)>0
  WHERE q.order_id=NEW.order_id AND q.user_id=NEW.user_id AND q.requested_at>=NEW.created_at) THEN
  RAISE EXCEPTION '자동 취소에는 같은 TX의 취소 예약과 정확한 시스템 근거 감사가 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_risk_cancel_audit_check ON bitcoin_chain_risk_order_cancellations;
CREATE CONSTRAINT TRIGGER bitcoin_chain_risk_cancel_audit_check AFTER INSERT ON bitcoin_chain_risk_order_cancellations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_risk_cancel_audit();
DROP TRIGGER IF EXISTS bitcoin_chain_risk_cancel_preserve ON bitcoin_chain_risk_order_cancellations;
CREATE TRIGGER bitcoin_chain_risk_cancel_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_chain_risk_order_cancellations FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
