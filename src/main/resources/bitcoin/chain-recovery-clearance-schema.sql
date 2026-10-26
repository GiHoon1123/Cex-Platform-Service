-- 승인 원본은 위험 관찰을 삭제하지 않는다. 두 사람의 결정이 알고 있던 마지막 위험 ID까지만 해제한다.
CREATE TABLE IF NOT EXISTS bitcoin_chain_clearance_requests (
 id uuid PRIMARY KEY,probe_id uuid NOT NULL REFERENCES bitcoin_chain_recovery_probes(id),
 receipt_id uuid NOT NULL REFERENCES bitcoin_credited_deposits(receipt_id),user_id bigint NOT NULL REFERENCES users(id),
 requester_id bigint NOT NULL REFERENCES users(id),checker_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 last_risk_id bigint NOT NULL REFERENCES bitcoin_transaction_observations(id),payload text NOT NULL CHECK(octet_length(payload)<=16384 AND jsonb_typeof(payload::jsonb)='object'),
 payload_hash varchar(64) NOT NULL CHECK(payload_hash=encode(sha256(convert_to(payload,'UTF8')),'hex')),
 expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 kind varchar(24) NOT NULL DEFAULT 'BTC_CHAIN_RECOVERY' CHECK(kind='BTC_CHAIN_RECOVERY'),
 UNIQUE(requester_id,request_key),CHECK(requester_id<>checker_id AND requester_id<>user_id AND checker_id<>user_id),
 FOREIGN KEY(kind,id) REFERENCES operator_approval_bindings(kind,request_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX IF NOT EXISTS bitcoin_chain_clearance_receipt ON bitcoin_chain_clearance_requests(receipt_id,last_risk_id);
CREATE TABLE IF NOT EXISTS bitcoin_chain_clearance_views (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES bitcoin_chain_clearance_requests(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,payload_hash varchar(64) NOT NULL,
 viewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
-- 같은 요청·지정 행위자·세션의 실제 열람 검사에서 전체 열람 이력을 반복 순회하지 않는다.
CREATE INDEX IF NOT EXISTS bitcoin_chain_clearance_view_source ON bitcoin_chain_clearance_views(request_id,actor_id,session_id);
CREATE TABLE IF NOT EXISTS bitcoin_chain_clearance_decisions (
 request_id uuid PRIMARY KEY REFERENCES bitcoin_chain_clearance_requests(id),approver_id bigint NOT NULL REFERENCES users(id),
 session_id uuid NOT NULL,approved boolean NOT NULL,payload_hash varchar(64) NOT NULL,authorization_id uuid NOT NULL UNIQUE,
 decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
-- 이 뷰의 정의는 초기화 중에도 무필터 뷰로 되돌리지 않는다. 원시 이력 뷰는 별도로 항상 보존한다.
CREATE OR REPLACE VIEW bitcoin_credit_chain_risks AS
 SELECT h.* FROM bitcoin_credit_chain_risk_history h WHERE NOT EXISTS(
  SELECT 1 FROM bitcoin_chain_clearance_requests r JOIN bitcoin_chain_clearance_decisions d ON d.request_id=r.id AND d.approved
   WHERE r.receipt_id=h.receipt_id AND h.observation_id<=r.last_risk_id);

-- 확정했던 근거가 지금도 유효한지 검사한다. 정상 재관찰의 ID는 달라도 같은 출력/앵커라면 허용한다.
-- 새 위험이나 원장 추가·투영 변경·미종결 주문·새 입금 대사 실패가 있으면 예전 근거를 재사용할 수 없다.
CREATE OR REPLACE FUNCTION bitcoin_chain_recovery_current(target uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM bitcoin_chain_recovery_probes p JOIN bitcoin_chain_recovery_results r ON r.probe_id=p.id AND r.status='READY'
  JOIN bitcoin_chain_recovery_basis(p.receipt_id) b ON b.scope_hash=p.scope_hash AND b.ledger_hash=p.ledger_hash
  JOIN bitcoin_current_deposit_reconciliation n ON n.id=r.native_run AND n.receipt_id=p.receipt_id AND n.status='MATCHED'
  JOIN bitcoin_deposit_receipts receipt ON receipt.id=p.receipt_id JOIN bitcoin_deposit_addresses addr ON addr.id=receipt.address_id
  JOIN bitcoin_observation_heads h ON h.network=receipt.network AND h.txid=receipt.txid
  JOIN bitcoin_transaction_observations o ON o.id=h.observation_id AND o.wallet=addr.wallet
  JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id AND NOT w.wallet_debit
  JOIN bitcoin_deposit_chain_heads ch ON ch.receipt_id=p.receipt_id
  JOIN bitcoin_deposit_chain_decisions d ON d.id=ch.decision_id AND d.observation_id=o.id AND d.reason='CHAIN_READY'
  WHERE p.id=target AND r.expires_at>clock_timestamp() AND n.snapshot_at>=clock_timestamp()-interval '2 minutes'
   AND o.status='CONFIRMED' AND o.confirmations>=6 AND NOT o.coinbase AND o.block_hash=r.block_hash AND o.block_height=r.block_height
   AND o.read_started_at BETWEEN clock_timestamp()-interval '2 minutes' AND clock_timestamp());
$$;
CREATE OR REPLACE FUNCTION bitcoin_chain_clearance_request_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE proof record;basis record;p jsonb;maker record;checker record;money record;
BEGIN
 PERFORM id FROM users WHERE id IN(NEW.requester_id,NEW.checker_id,NEW.user_id) ORDER BY id FOR UPDATE;
 SELECT s.*,r.financial_run,r.native_run,r.block_hash,r.block_height,r.expires_at INTO proof FROM bitcoin_chain_recovery_probes s JOIN bitcoin_chain_recovery_results r ON r.probe_id=s.id WHERE s.id=NEW.probe_id;
 SELECT * INTO basis FROM bitcoin_chain_recovery_basis(NEW.receipt_id);p:=NEW.payload::jsonb;
 SELECT * INTO maker FROM users WHERE id=NEW.requester_id;SELECT * INTO checker FROM users WHERE id=NEW.checker_id;
 SELECT asset,amount INTO money FROM bitcoin_credit_authorizations WHERE receipt_id=NEW.receipt_id;
 IF COALESCE(NEW.created_txid<>txid_current() OR NEW.created_at>clock_timestamp() OR NEW.expires_at<>proof.expires_at OR NEW.expires_at<=clock_timestamp()
  OR NOT bitcoin_chain_recovery_current(NEW.probe_id) OR proof.actor_id<>NEW.requester_id OR proof.receipt_id<>NEW.receipt_id OR proof.user_id<>NEW.user_id OR NEW.last_risk_id<>basis.last_risk_id
  OR maker.role<>'OPERATOR' OR NOT maker.enabled OR maker.operator_access_expires_at<=clock_timestamp() OR checker.role<>'APPROVER' OR NOT checker.enabled OR checker.operator_access_expires_at<=clock_timestamp()
  OR maker.operator_person_id=checker.operator_person_id OR(p->>'requesterPerson')::uuid<>maker.operator_person_id OR(p->>'requesterVersion')::bigint<>maker.version
  OR(p->>'checkerPerson')::uuid<>checker.operator_person_id OR(p->>'checkerVersion')::bigint<>checker.version
  OR(p->>'id')::uuid<>NEW.id OR(p->>'proofId')::uuid<>NEW.probe_id OR p->>'proofHash'<>proof.payload_hash OR(p->>'receiptId')::uuid<>NEW.receipt_id OR(p->>'customerId')::bigint<>NEW.user_id
  OR p->>'asset'<>money.asset OR(p->>'amount')::numeric<>money.amount OR(p->>'riskCount')::bigint<>basis.risk_count OR(p->>'firstRiskId')::bigint<>basis.first_risk_id OR(p->>'lastRiskId')::bigint<>NEW.last_risk_id
  OR p->>'scopeHash'<>proof.scope_hash OR p->>'ledgerHash'<>proof.ledger_hash OR(p->>'financialRun')::uuid<>proof.financial_run OR(p->>'nativeRun')::uuid<>proof.native_run
  OR p->>'blockHash'<>proof.block_hash OR(p->>'blockHeight')::bigint<>proof.block_height OR(p->>'requesterId')::bigint<>NEW.requester_id OR(p->>'checkerId')::bigint<>NEW.checker_id
  OR(p->>'expiresAt')::timestamptz<>NEW.expires_at OR p->>'policy'<>'BTC_CHAIN_RECOVERY_CLEARANCE_V1',true) THEN
  RAISE EXCEPTION '체인 해제 요청과 현재 복구 근거·위험 범위·지정 인물이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_clearance_request_binding_check ON bitcoin_chain_clearance_requests;
CREATE TRIGGER bitcoin_chain_clearance_request_binding_check BEFORE INSERT ON bitcoin_chain_clearance_requests FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_clearance_request_binding();
CREATE OR REPLACE FUNCTION bitcoin_chain_clearance_view_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;p jsonb;expected_person uuid;expected_version bigint;
BEGIN
 SELECT * INTO r FROM bitcoin_chain_clearance_requests WHERE id=NEW.request_id;p:=r.payload::jsonb;
 PERFORM id FROM users WHERE id IN(r.requester_id,r.checker_id,r.user_id,NEW.actor_id) ORDER BY id FOR UPDATE;
 IF NEW.actor_id=r.requester_id THEN expected_person:=(p->>'requesterPerson')::uuid;expected_version:=(p->>'requesterVersion')::bigint;
 ELSIF NEW.actor_id=r.checker_id THEN expected_person:=(p->>'checkerPerson')::uuid;expected_version:=(p->>'checkerVersion')::bigint;END IF;
 IF COALESCE(NEW.created_txid<>txid_current() OR NEW.viewed_at>clock_timestamp() OR NEW.payload_hash<>r.payload_hash
  OR NOT monitoring_investigation_actor(NEW.actor_id,expected_person,expected_version,NEW.session_id,ARRAY['OPERATOR','APPROVER']::varchar[]),true) THEN
  RAISE EXCEPTION '체인 해제 열람과 현재 지정 인물·세션·내용이 다릅니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_clearance_view_binding_check ON bitcoin_chain_clearance_views;
CREATE TRIGGER bitcoin_chain_clearance_view_binding_check BEFORE INSERT ON bitcoin_chain_clearance_views FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_clearance_view_binding();
CREATE OR REPLACE FUNCTION bitcoin_chain_clearance_decision_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record;p jsonb;
BEGIN
 SELECT * INTO r FROM bitcoin_chain_clearance_requests WHERE id=NEW.request_id;p:=r.payload::jsonb;
 PERFORM id FROM users WHERE id IN(r.requester_id,r.checker_id,r.user_id,NEW.approver_id) ORDER BY id FOR UPDATE;
 IF COALESCE(NEW.created_txid<>txid_current() OR NEW.decided_at>clock_timestamp() OR r.expires_at<=clock_timestamp() OR NOT bitcoin_chain_recovery_current(r.probe_id)
  OR NEW.approver_id<>r.checker_id OR NEW.payload_hash<>r.payload_hash
  OR NOT monitoring_investigation_actor(NEW.approver_id,(p->>'checkerPerson')::uuid,(p->>'checkerVersion')::bigint,NEW.session_id,ARRAY['APPROVER']::varchar[])
  OR NOT EXISTS(SELECT 1 FROM users u WHERE u.id=r.requester_id AND u.role='OPERATOR' AND u.enabled AND u.version=(p->>'requesterVersion')::bigint AND u.operator_person_id=(p->>'requesterPerson')::uuid AND u.operator_access_expires_at>clock_timestamp())
  OR NOT EXISTS(SELECT 1 FROM users u WHERE u.id=r.checker_id AND u.operator_access_expires_at>clock_timestamp())
  OR NOT EXISTS(SELECT 1 FROM bitcoin_chain_clearance_views v WHERE v.request_id=r.id AND v.actor_id=NEW.approver_id AND v.actor_person=(p->>'checkerPerson')::uuid AND v.actor_version=(p->>'checkerVersion')::bigint AND v.session_id=NEW.session_id AND v.payload_hash=r.payload_hash)
  OR NOT EXISTS(SELECT 1 FROM operator_authorization_challenges c WHERE c.id=NEW.authorization_id AND c.user_id=NEW.approver_id AND c.session_id=NEW.session_id AND c.kind='BTC_CHAIN_RECOVERY'
   AND c.request_id=NEW.request_id AND c.payload_hash=NEW.payload_hash AND c.approve=NEW.approved AND c.consumed),true) THEN
  RAISE EXCEPTION '체인 해제 결정과 현재 복구·인물·범위·일회 서명이 다릅니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_clearance_decision_binding_check ON bitcoin_chain_clearance_decisions;
CREATE TRIGGER bitcoin_chain_clearance_decision_binding_check BEFORE INSERT ON bitcoin_chain_clearance_decisions FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_clearance_decision_binding();
CREATE OR REPLACE FUNCTION bitcoin_chain_clearance_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint;target_id uuid;stamp timestamptz;
BEGIN
 IF TG_TABLE_NAME='bitcoin_chain_clearance_requests' THEN actor:=NEW.requester_id;target_id:=NEW.id;stamp:=NEW.created_at;
 ELSIF TG_TABLE_NAME='bitcoin_chain_clearance_views' THEN actor:=NEW.actor_id;target_id:=NEW.id;stamp:=NEW.viewed_at;
 ELSE actor:=NEW.approver_id;target_id:=NEW.request_id;stamp:=NEW.decided_at;END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id=actor AND a.action=TG_ARGV[0] AND a.target=TG_ARGV[1]||target_id::text
  AND a.outcome='SUCCESS' AND a.detail=NEW.payload_hash AND a.occurred_at>=stamp AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '체인 해제 업무에는 같은 내용의 필수 감사가 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_clearance_request_audit ON bitcoin_chain_clearance_requests;
CREATE CONSTRAINT TRIGGER bitcoin_chain_clearance_request_audit AFTER INSERT ON bitcoin_chain_clearance_requests DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_clearance_audit('BTC_CHAIN_CLEARANCE_PROPOSED','bitcoin-chain-clearance:');
DROP TRIGGER IF EXISTS bitcoin_chain_clearance_view_audit ON bitcoin_chain_clearance_views;
CREATE CONSTRAINT TRIGGER bitcoin_chain_clearance_view_audit AFTER INSERT ON bitcoin_chain_clearance_views DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_clearance_audit('BTC_CHAIN_CLEARANCE_VIEWED','bitcoin-chain-clearance-view:');
DROP TRIGGER IF EXISTS bitcoin_chain_clearance_decision_audit ON bitcoin_chain_clearance_decisions;
CREATE CONSTRAINT TRIGGER bitcoin_chain_clearance_decision_audit AFTER INSERT ON bitcoin_chain_clearance_decisions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_clearance_audit('BTC_CHAIN_CLEARANCE_DECIDED','bitcoin-chain-clearance:');
DROP TRIGGER IF EXISTS bitcoin_chain_clearance_requests_immutable ON bitcoin_chain_clearance_requests;
CREATE TRIGGER bitcoin_chain_clearance_requests_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_chain_clearance_requests FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS bitcoin_chain_clearance_views_immutable ON bitcoin_chain_clearance_views;
CREATE TRIGGER bitcoin_chain_clearance_views_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_chain_clearance_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS bitcoin_chain_clearance_decisions_immutable ON bitcoin_chain_clearance_decisions;
CREATE TRIGGER bitcoin_chain_clearance_decisions_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_chain_clearance_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
