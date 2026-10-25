-- 복구 근거는 위험 해제나 원장 정정이 아니다. 시작/완료를 불변 기록으로 남긴다.
CREATE OR REPLACE FUNCTION bitcoin_chain_recovery_ledger_fingerprint(owner bigint) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT encode(sha256(convert_to(jsonb_build_object(
  'entries',(SELECT count(*) FROM ledger_entries WHERE owner_id=owner),
  'lastEntry',(SELECT coalesce(max(id),0) FROM ledger_entries WHERE owner_id=owner),
  'balances',coalesce((SELECT jsonb_agg(jsonb_build_array(mint_address,available::text,locked::text) ORDER BY mint_address,id)
    FROM user_balances WHERE user_id=owner),'[]'::jsonb))::text,'UTF8')),'hex');
$$;
CREATE OR REPLACE FUNCTION bitcoin_chain_recovery_quiet(owner bigint) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT NOT EXISTS(SELECT 1 FROM orders WHERE user_id=owner AND(status IS NULL OR status NOT IN('filled','cancelled','rejected','expired')))
  AND NOT EXISTS(SELECT 1 FROM user_balances WHERE user_id=owner AND locked<>0)
  AND NOT EXISTS(SELECT 1 FROM ledger_entries WHERE owner_id=owner AND bucket='LOCKED' GROUP BY asset HAVING sum(delta)<>0);
$$;
-- 원본은 한 입금으로 고정한다. 반복 관찰이 늘면 범위 해시가 달라져 이전 복구 근거를 재사용할 수 없다.
CREATE OR REPLACE FUNCTION bitcoin_chain_recovery_basis(receipt uuid) RETURNS TABLE(user_id bigint,authorization_hash text,
 engine_id uuid,event_id text,payload_hash text,risk_count bigint,first_risk_id bigint,last_risk_id bigint,scope_hash text,ledger_hash text) LANGUAGE sql STABLE AS $$
 SELECT a.user_id,a.basis_hash::text,c.engine_id,c.event_id::text,c.payload_hash::text,
  s.n,s.first_id,s.last_id,encode(sha256(convert_to(jsonb_build_object('receipt',a.receipt_id,'authorization',a.basis_hash,
   'event',c.event_id,'count',s.n,'first',s.first_id,'last',s.last_id)::text,'UTF8')),'hex'),bitcoin_chain_recovery_ledger_fingerprint(a.user_id)
 FROM bitcoin_credit_authorizations a JOIN bitcoin_credited_deposits c ON c.receipt_id=a.receipt_id
 CROSS JOIN LATERAL(SELECT count(*) n,min(observation_id) first_id,max(observation_id) last_id
  FROM bitcoin_credit_chain_risks r WHERE r.receipt_id=a.receipt_id) s
 WHERE a.receipt_id=receipt AND s.n>0 AND bitcoin_chain_recovery_quiet(a.user_id);
$$;
CREATE TABLE IF NOT EXISTS bitcoin_chain_recovery_probes (
 id uuid PRIMARY KEY,receipt_id uuid NOT NULL REFERENCES bitcoin_credited_deposits(receipt_id),user_id bigint NOT NULL REFERENCES users(id),
 actor_id bigint NOT NULL REFERENCES users(id),actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,request_key uuid NOT NULL,
 scope_hash varchar(64) NOT NULL,ledger_hash varchar(64) NOT NULL,
 payload text NOT NULL CHECK(octet_length(payload)<=16384 AND jsonb_typeof(payload::jsonb)='object'),
 payload_hash varchar(64) NOT NULL CHECK(payload_hash=encode(sha256(convert_to(payload,'UTF8')),'hex')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 UNIQUE(actor_id,request_key),CHECK(actor_id<>user_id)
);
CREATE TABLE IF NOT EXISTS bitcoin_chain_recovery_results (
 probe_id uuid PRIMARY KEY REFERENCES bitcoin_chain_recovery_probes(id),status varchar(8) NOT NULL CHECK(status IN('READY','HELD')),
 reason varchar(48) NOT NULL,financial_run uuid REFERENCES reconciliation_runs(id),native_run uuid REFERENCES bitcoin_deposit_reconciliation_runs(id),
 block_hash varchar(64),block_height bigint,expires_at timestamptz,
 completed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 CHECK(status<>'READY' OR(reason='VERIFIED_RECOVERY_BASIS' AND financial_run IS NOT NULL AND native_run IS NOT NULL AND block_hash ~ '^[a-f0-9]{64}$' AND block_height>=0 AND expires_at IS NOT NULL))
);
CREATE OR REPLACE FUNCTION bitcoin_chain_recovery_probe_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE basis record;p jsonb;
BEGIN
 -- 앱의 동일 고객 잠금과 같은 순서를 사용해 직접 SQL도 현재 사실 확인 전에 직렬화한다.
 PERFORM id FROM users WHERE id IN(NEW.actor_id,NEW.user_id) ORDER BY id FOR UPDATE;
 SELECT * INTO basis FROM bitcoin_chain_recovery_basis(NEW.receipt_id);p:=NEW.payload::jsonb;
 IF COALESCE(NOT FOUND OR NEW.created_txid<>txid_current() OR NEW.created_at>clock_timestamp()
  OR NEW.user_id<>basis.user_id OR NEW.scope_hash<>basis.scope_hash OR NEW.ledger_hash<>basis.ledger_hash
  OR(p->>'id')::uuid<>NEW.id OR(p->>'receiptId')::uuid<>NEW.receipt_id OR(p->>'actorId')::bigint<>NEW.actor_id
  OR p->>'scopeHash'<>NEW.scope_hash OR p->>'ledgerHash'<>NEW.ledger_hash OR p->>'policy'<>'BTC_CHAIN_RECOVERY_PROOF_V1'
  OR NOT EXISTS(SELECT 1 FROM users u JOIN refresh_tokens t ON t.user_id=u.id AND t.session_id=NEW.session_id
   WHERE u.id=NEW.actor_id AND u.enabled AND u.role IN('OPERATOR','APPROVER') AND u.version=NEW.actor_version AND u.operator_person_id=NEW.actor_person
    AND u.operator_access_expires_at>clock_timestamp() AND NOT t.revoked AND t.mfa_verified_at IS NOT NULL AND t.mfa_credential_fingerprint ~ '^[a-f0-9]{64}$'),true) THEN
  RAISE EXCEPTION '복구 검증 시작과 현재 운영 주체·실제 입금 위험 범위·금융 상태가 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_recovery_probe_binding_check ON bitcoin_chain_recovery_probes;
CREATE TRIGGER bitcoin_chain_recovery_probe_binding_check BEFORE INSERT ON bitcoin_chain_recovery_probes FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_recovery_probe_binding();
-- 조회 워커의 주장만 저장하지 않는다. 두 실제 대사와 같은 고객의 불변 상태, 현재 정상 관찰을 결합한다.
CREATE OR REPLACE FUNCTION bitcoin_chain_recovery_result_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE probe record;basis record;native record;financial record;receipt record;node jsonb;
BEGIN
 SELECT * INTO probe FROM bitcoin_chain_recovery_probes WHERE id=NEW.probe_id;
 PERFORM id FROM users WHERE id IN(probe.actor_id,probe.user_id) ORDER BY id FOR UPDATE;
 IF NEW.created_txid<>txid_current() OR NEW.completed_at<probe.created_at OR NEW.completed_at>clock_timestamp() THEN
  RAISE EXCEPTION '복구 완료와 시작 기록의 순서가 다릅니다';END IF;
 IF NEW.status='READY' THEN
  SELECT * INTO basis FROM bitcoin_chain_recovery_basis(probe.receipt_id);
  IF NOT FOUND OR basis.scope_hash<>probe.scope_hash OR basis.ledger_hash<>probe.ledger_hash THEN
   RAISE EXCEPTION '대사 도중 위험 또는 금융 원본이 바뀌었습니다';END IF;
  SELECT * INTO native FROM bitcoin_current_deposit_reconciliation WHERE id=NEW.native_run AND receipt_id=probe.receipt_id;
  SELECT * INTO financial FROM reconciliation_runs WHERE id=NEW.financial_run;
  SELECT r.*,a.wallet,a.address,a.script_pub_key INTO receipt FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id WHERE r.id=probe.receipt_id;
  SELECT payload::jsonb INTO node FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=NEW.native_run AND kind='NODE';
  IF COALESCE(native.status<>'MATCHED' OR native.snapshot_at<probe.created_at OR native.snapshot_at<clock_timestamp()-interval '2 minutes' OR native.completed_at>clock_timestamp()
   OR financial.status<>'MATCHED' OR financial.started_at<probe.created_at OR financial.completed_at<clock_timestamp()-interval '2 minutes' OR financial.completed_at>clock_timestamp()
   OR financial.engine_id<>basis.engine_id::text OR NOT EXISTS(SELECT 1 FROM reconciliation_evidence e WHERE e.run_id=NEW.financial_run AND e.event_id=basis.event_id AND e.payload_hash=basis.payload_hash)
   OR node->>'contract'<>'BTC_RECEIPT_AUDIT_V1' OR node->'metadata'->>'network'<>receipt.network OR node->'metadata'->>'txid'<>receipt.txid
   OR node->'metadata'->>'status'<>'CONFIRMED' OR(node->'metadata'->>'confirmations')::bigint<6 OR(node->'metadata'->>'coinbase')::boolean OR(node->'metadata'->>'walletDebit')::boolean
   OR node->'metadata'->>'blockHash'<>NEW.block_hash OR(node->'metadata'->>'blockHeight')::bigint<>NEW.block_height
   OR jsonb_array_length(node->'outputs')<>1 OR(node->'outputs'->0->>'vout')::integer<>receipt.vout
   OR node->'outputs'->0->>'address'<>receipt.address OR node->'outputs'->0->>'scriptPubKey'<>receipt.script_pub_key OR(node->'outputs'->0->>'amount')::numeric<>receipt.amount
   OR NEW.expires_at<=clock_timestamp() OR NEW.expires_at>least(native.snapshot_at+interval '2 minutes',financial.completed_at+interval '2 minutes')
   OR NOT EXISTS(SELECT 1 FROM bitcoin_observation_heads h JOIN bitcoin_transaction_observations o ON o.id=h.observation_id
     JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id AND NOT w.wallet_debit
     JOIN bitcoin_deposit_chain_heads ch ON ch.receipt_id=probe.receipt_id JOIN bitcoin_deposit_chain_decisions d ON d.id=ch.decision_id AND d.observation_id=o.id AND d.reason='CHAIN_READY'
     WHERE h.network=receipt.network AND h.txid=receipt.txid AND o.wallet=receipt.wallet AND o.status='CONFIRMED' AND o.confirmations>=6 AND NOT o.coinbase
      AND o.block_hash=NEW.block_hash AND o.block_height=NEW.block_height AND o.read_started_at BETWEEN clock_timestamp()-interval '2 minutes' AND clock_timestamp()),true) THEN
   RAISE EXCEPTION '복구 준비에는 최신 정상 체인과 실제 금융·입금 대사 근거가 필요합니다';END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_recovery_result_binding_check ON bitcoin_chain_recovery_results;
CREATE TRIGGER bitcoin_chain_recovery_result_binding_check BEFORE INSERT ON bitcoin_chain_recovery_results FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_recovery_result_binding();
CREATE OR REPLACE FUNCTION bitcoin_chain_recovery_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_id uuid;actor bigint;stamp timestamptz;required_detail text;
BEGIN
 IF TG_TABLE_NAME='bitcoin_chain_recovery_probes' THEN target_id:=NEW.id;actor:=NEW.actor_id;stamp:=NEW.created_at;required_detail:=NEW.payload_hash;
 ELSE target_id:=NEW.probe_id;SELECT actor_id INTO actor FROM bitcoin_chain_recovery_probes WHERE id=target_id;stamp:=NEW.completed_at;required_detail:=NEW.status||':'||NEW.reason;END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id=actor AND a.action=TG_ARGV[0] AND a.target='bitcoin-chain-recovery:'||target_id::text
  AND a.outcome='SUCCESS' AND a.detail=required_detail AND a.occurred_at>=stamp AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '복구 근거에는 같은 시작/완료 내용의 필수 감사가 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_chain_recovery_start_audit ON bitcoin_chain_recovery_probes;
CREATE CONSTRAINT TRIGGER bitcoin_chain_recovery_start_audit AFTER INSERT ON bitcoin_chain_recovery_probes DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_recovery_audit('BTC_CHAIN_RECOVERY_STARTED');
DROP TRIGGER IF EXISTS bitcoin_chain_recovery_finish_audit ON bitcoin_chain_recovery_results;
CREATE CONSTRAINT TRIGGER bitcoin_chain_recovery_finish_audit AFTER INSERT ON bitcoin_chain_recovery_results DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_chain_recovery_audit('BTC_CHAIN_RECOVERY_FINISHED');
DROP TRIGGER IF EXISTS bitcoin_chain_recovery_probe_immutable ON bitcoin_chain_recovery_probes;
CREATE TRIGGER bitcoin_chain_recovery_probe_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_chain_recovery_probes FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS bitcoin_chain_recovery_result_immutable ON bitcoin_chain_recovery_results;
CREATE TRIGGER bitcoin_chain_recovery_result_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_chain_recovery_results FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
