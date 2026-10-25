-- 실행 권한의 시작 관찰 이후, 같은 네트워크·지갑·출력의 근거 상실을 찾는다.
-- 판단 워커가 실패해도 이미 확정한 노드 관찰을 놓치지 않으며 과거 금융 원본은 바꾸지 않는다.
CREATE INDEX IF NOT EXISTS bitcoin_credit_authorizations_owner ON bitcoin_credit_authorizations(user_id);
CREATE OR REPLACE VIEW bitcoin_credit_chain_risks AS
 SELECT a.receipt_id,a.user_id,a.asset,a.amount,o.id AS observation_id,risk.reason,o.recorded_at
  FROM bitcoin_credit_authorizations a JOIN bitcoin_deposit_receipts r ON r.id=a.receipt_id
  JOIN bitcoin_deposit_addresses addr ON addr.id=r.address_id
  JOIN bitcoin_transaction_observations o ON o.network=r.network AND o.wallet=addr.wallet AND o.txid=r.txid
   AND o.id>(a.basis::jsonb->'chain'->>'observationId')::bigint
  CROSS JOIN LATERAL(SELECT count(*) AS total,
    count(*) FILTER(WHERE e->>'address'=addr.address AND e->>'scriptPubKey'=addr.script_pub_key AND(e->>'amount')::numeric=r.amount) AS matching
    FROM jsonb_array_elements(o.outputs) e WHERE(e->>'vout')::integer=r.vout) output
  CROSS JOIN LATERAL(SELECT CASE
   WHEN output.total<>1 OR output.matching<>1 THEN 'OUTPUT_MISMATCH'
   WHEN o.status='CONFLICTED' THEN 'CONFLICTED'
   WHEN o.status='UNCONFIRMED' THEN 'WAITING_CHAIN'
   WHEN o.coinbase AND o.confirmations<101 THEN 'IMMATURE_COINBASE'
   WHEN o.confirmations<6 THEN 'WAITING_CONFIRMATIONS' END AS reason) risk
  WHERE risk.reason IS NOT NULL;

-- 같은 거래에 여러 수신 고객이 있으면 ID 순서로 잠근다. 권한이 아직 미커밋이어도
-- 수신 원본의 고객을 먼저 잠그므로 관찰 공개와 신규 금융 권한의 순서를 고정한다.
-- 노드 RPC는 이 짧은 저장 트랜잭션 밖에서 이미 끝났다. 엔진 코어에는 락을 추가하지 않는다.
CREATE OR REPLACE FUNCTION bitcoin_observation_execution_risk_lock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM u.id FROM users u WHERE u.id IN(
  SELECT r.user_id FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses addr ON addr.id=r.address_id
   WHERE r.network=NEW.network AND r.txid=NEW.txid AND addr.wallet=NEW.wallet)
  ORDER BY u.id FOR UPDATE OF u;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_observation_execution_guard ON bitcoin_transaction_observations;
CREATE TRIGGER bitcoin_observation_execution_guard BEFORE INSERT ON bitcoin_transaction_observations
 FOR EACH ROW EXECUTE FUNCTION bitcoin_observation_execution_risk_lock();

-- 위험 관찰은 같은 TX의 현재 포인터·지갑 사용 근거·필수 감사까지 함께 확정한다.
-- 후속 체인 판단의 실패로 이미 저장한 위험 사실을 없는 것으로 되돌리지 않는다.
CREATE OR REPLACE FUNCTION bitcoin_observation_execution_risk_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM bitcoin_credit_chain_risks WHERE observation_id=NEW.id) THEN
  IF NOT EXISTS(SELECT 1 FROM bitcoin_observation_heads h WHERE h.network=NEW.network AND h.txid=NEW.txid AND h.observation_id=NEW.id)
   OR NOT EXISTS(SELECT 1 FROM bitcoin_observation_wallet_debits w WHERE w.observation_id=NEW.id)
   OR NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NULL AND a.action='BTC_TRANSACTION_OBSERVED'
    AND a.target='bitcoin:'||NEW.network||':'||NEW.txid AND a.outcome='SUCCESS'
    AND a.detail='OBSERVATION:'||NEW.id::text AND a.occurred_at>=NEW.recorded_at AND length(a.source_address)>0)
   THEN RAISE EXCEPTION '실행 권한 이후 위험 관찰에는 현재 원본과 필수 감사가 필요합니다';END IF;
 END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_observation_execution_risk_audit_check ON bitcoin_transaction_observations;
CREATE CONSTRAINT TRIGGER bitcoin_observation_execution_risk_audit_check AFTER INSERT ON bitcoin_transaction_observations
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_observation_execution_risk_audit();
