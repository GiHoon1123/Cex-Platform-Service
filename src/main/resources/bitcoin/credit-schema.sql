-- 자동 반입 판단의 경제적 원본과 실제 확인 근거를 보존한다. AVAILABLE/엔진 공개는 후속 확정 이벤트가 담당한다.
CREATE TABLE IF NOT EXISTS bitcoin_credit_intents (
 receipt_id uuid PRIMARY KEY REFERENCES bitcoin_pending_deposits(receipt_id),user_id bigint NOT NULL REFERENCES users(id),
 asset varchar(16) NOT NULL CHECK(asset='BTC'),amount numeric(30,9) NOT NULL CHECK(amount>0 AND amount=trunc(amount,8)),
 policy_id varchar(40) NOT NULL CHECK(policy_id='BTC_VERIFIED_VASP_CREDIT_V1'),
 basis text NOT NULL CHECK(octet_length(basis)<=131072 AND jsonb_typeof(basis::jsonb)='object'),
 basis_hash varchar(64) NOT NULL CHECK(basis_hash=encode(sha256(convert_to(basis,'UTF8')),'hex')),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
-- 애플리케이션의 성공 분류만 저장하지 않는다. 참조한 현재 고객/원본/머리/정책/원장을 DB에서도 결합한다.
-- COALESCE의 기본 true는 필수 JSON 값이 없어서 비교가 NULL이 되는 경우도 허용 대신 차단한다.
CREATE OR REPLACE FUNCTION bitcoin_credit_intent_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE b jsonb;
BEGIN
 -- 직접 SQL도 현재 근거를 읽기 전에 고객 잠금을 얻는다. 마지막 외래키 검사에서
 -- 뒤늦게 대기하는 것만으로는 기다리는 동안 바뀐 고객/체인 사실을 재검증할 수 없다.
 PERFORM id FROM users WHERE id=NEW.user_id FOR UPDATE;
 b:=NEW.basis::jsonb;
 IF COALESCE(NEW.created_txid<>txid_current() OR NEW.recorded_at>clock_timestamp()
  OR b->>'policy'<>NEW.policy_id OR(b->>'checkedAt')::timestamptz>NEW.recorded_at OR(b->>'checkedAt')::timestamptz<clock_timestamp()-interval '10 seconds'
  OR(b->'funds'->>'depositId')::uuid<>NEW.receipt_id OR(b->'funds'->>'customerId')::bigint<>NEW.user_id OR b->'funds'->>'asset'<>NEW.asset OR(b->'funds'->>'amount')::numeric<>NEW.amount
  OR NOT EXISTS(
   SELECT 1 FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses addr ON addr.id=r.address_id AND addr.user_id=r.user_id
    JOIN bitcoin_pending_deposits p ON p.receipt_id=r.id JOIN ledger_transactions lt ON lt.operation_key=p.operation_key AND lt.kind='BTC_DEPOSIT_PENDING'
    JOIN users u ON u.id=r.user_id JOIN cdd_profiles cp ON cp.user_id=u.id JOIN cdd_assessments ca ON ca.id=cp.assessment_id AND ca.declaration_id=cp.declaration_id AND ca.user_id=u.id
    JOIN cdd_declarations cd ON cd.id=cp.declaration_id AND cd.user_id=u.id JOIN kyc_profiles kp ON kp.user_id=u.id JOIN kyc_attempts ka ON ka.id=kp.active_attempt
    JOIN bitcoin_observation_heads oh ON oh.network=r.network AND oh.txid=r.txid JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id AND o.wallet=addr.wallet
    JOIN bitcoin_observation_wallet_debits wd ON wd.observation_id=o.id AND wd.wallet_debit=false
    JOIN bitcoin_deposit_chain_heads dh ON dh.receipt_id=r.id JOIN bitcoin_deposit_chain_decisions dc ON dc.id=dh.decision_id AND dc.observation_id=o.id
    JOIN bitcoin_deposit_valuations v ON v.receipt_id=r.id JOIN deposit_monitoring_heads mh ON mh.user_id=u.id JOIN deposit_monitoring_assessments ma ON ma.id=mh.assessment_id
    JOIN bitcoin_current_deposit_reconciliation q ON q.receipt_id=r.id
    JOIN codevasp_transfer_attempts ta ON ta.id=(b->'origin'->>'transferAttemptId')::uuid AND ta.receipt_id=r.id AND ta.user_id=u.id AND ta.declaration_id=cp.declaration_id AND ta.assessment_id=cp.assessment_id
    JOIN codevasp_transfer_heads th ON th.intent_id=ta.intent_id AND th.attempt_id=ta.id JOIN codevasp_transfer_results tr ON tr.attempt_id=ta.id AND tr.status='DATA_RECEIVED'
    JOIN codevasp_transfer_detail_assessments td ON td.attempt_id=ta.id AND td.status='CONSISTENT' AND td.policy_id='BTC_REQUEST_DETAILS_V1'
    JOIN codevasp_deposit_searches s ON s.id=ta.search_id AND s.receipt_id=r.id JOIN codevasp_search_heads sh ON sh.search_id=s.id JOIN codevasp_search_results sr ON sr.attempt_id=sh.attempt_id AND sr.status='FOUND'
    JOIN codevasp_directory_heads drh ON drh.source_id=s.source_id AND drh.attempt_id=(b->'origin'->>'directoryAttemptId')::bigint
   WHERE r.id=NEW.receipt_id AND r.user_id=NEW.user_id AND r.asset=NEW.asset AND r.amount=NEW.amount AND p.operation_key='btc:pending:'||r.id::text
    AND r.network=b->'funds'->>'network' AND addr.wallet=b->'funds'->>'wallet' AND r.txid=b->'funds'->>'txid' AND r.vout=(b->'funds'->>'vout')::integer
    AND u.enabled AND u.role='CUSTOMER' AND NOT u.trading_restricted AND u.version=(b->'identity'->>'userVersion')::bigint
    AND NOT EXISTS(SELECT 1 FROM relationship_active_exits e WHERE e.user_id=u.id)
    AND NOT EXISTS(SELECT 1 FROM bitcoin_credit_chain_risks cr WHERE cr.user_id=u.id)
    AND cp.declaration_id=(b->'identity'->>'declarationId')::uuid AND cp.assessment_id=(b->'identity'->>'assessmentId')::uuid AND kp.active_attempt=(b->'identity'->>'kycAttemptId')::uuid
    AND ca.outcome='STANDARD' AND ca.policy_version='CDD_DOMESTIC_INDIVIDUAL_V1' AND ca.attempt_id=kp.active_attempt AND ca.status_digest=kp.status_digest
    AND ca.key_id=b->'identity'->>'keyId' AND cd.key_id=ca.key_id AND ca.identity_mac=b->'identity'->>'identityMac' AND cd.content_mac=b->'identity'->>'declarationMac'
    AND kp.identity_status='VERIFIED' AND kp.valid_until>clock_timestamp() AND ka.environment='live'
    AND EXISTS(SELECT 1 FROM kyc_reconciliations kr WHERE kr.attempt_id=kp.active_attempt AND kr.fresh_at>clock_timestamp()-interval '15 minutes' AND kr.fresh_at<=clock_timestamp())
    AND o.status='CONFIRMED' AND o.confirmations>=6 AND o.coinbase=false AND o.read_started_at BETWEEN clock_timestamp()-interval '2 minutes' AND clock_timestamp()
    AND dc.policy_id='BTC_CHAIN_V1' AND dc.reason='CHAIN_READY' AND o.id=(b->'chain'->>'observationId')::bigint AND dc.id=(b->'chain'->>'decisionId')::bigint
    AND o.block_hash=b->'chain'->>'blockHash' AND o.block_height=(b->'chain'->>'blockHeight')::bigint
    AND v.status='VALUED' AND v.policy_id='BTC_PENDING_KRW_REFERENCE_V1' AND v.price_attempt_id=(b->'valuation'->>'priceAttemptId')::bigint AND v.reference_at=(b->'valuation'->>'referenceAt')::timestamptz AND v.krw_value=(b->'valuation'->>'krwValue')::numeric
    AND ma.id=(b->'risk'->>'assessmentId')::uuid AND ma.input_cursor=(b->'risk'->>'cursor')::bigint AND ma.outcome=b->'risk'->>'outcome'
    AND ma.policy_id='NATIVE_SMALL_REFERENCE_INFLOW_V1' AND ma.outcome IN ('NO_SIGNAL','REVIEW_REQUIRED') AND ma.unknown_transfers=0
    AND ma.input_cursor=(SELECT max(i.input_cursor) FROM deposit_monitoring_inputs i WHERE i.user_id=u.id)
    AND NOT EXISTS(SELECT 1 FROM deposit_monitoring_cases c WHERE c.user_id=u.id AND NOT monitoring_case_closed(c.id))
    AND q.id=(b->'reconciliation'->>'runId')::uuid AND q.start_position=(b->'reconciliation'->>'startPosition')::bigint AND q.status='MATCHED' AND q.network=r.network AND q.wallet=addr.wallet
    AND q.snapshot_at BETWEEN clock_timestamp()-interval '2 minutes' AND clock_timestamp() AND q.completed_at<=clock_timestamp()
    AND EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_evidence qe WHERE qe.run_id=q.id AND qe.kind='PLATFORM' AND qe.payload_hash=b->'reconciliation'->>'platformHash')
    AND EXISTS(SELECT 1 FROM bitcoin_deposit_reconciliation_evidence qe WHERE qe.run_id=q.id AND qe.kind='NODE' AND qe.payload_hash=b->'reconciliation'->>'nodeHash')
    AND ta.intent_id=(b->'origin'->>'transferIntentId')::uuid AND ta.search_id=(b->'origin'->>'searchId')::uuid AND s.source_id=b->'origin'->>'sourceId'
    AND sh.attempt_id=(b->'origin'->>'searchAttemptId')::bigint AND sr.payload::jsonb->>'entityId'=ta.peer_id AND ta.peer_id=b->'origin'->'counterparty'->>'entityId'
    AND ta.key_id=b->'origin'->>'keyId' AND tr.key_id=ta.key_id AND ta.content_mac=b->'origin'->>'requestMac' AND tr.content_mac=b->'origin'->>'responseMac'
    AND (b->'origin'->'counterparty'->>'validFrom')::timestamptz<=clock_timestamp() AND (b->'origin'->'counterparty'->>'validUntil')::timestamptz>clock_timestamp()
    AND(SELECT count(*) FROM ledger_entries le WHERE le.operation_key=p.operation_key)=2
    AND EXISTS(SELECT 1 FROM ledger_entries le WHERE le.operation_key=p.operation_key AND le.owner_id=u.id AND le.bucket='PENDING' AND le.asset=r.asset AND le.delta=r.amount)
    AND EXISTS(SELECT 1 FROM ledger_entries le WHERE le.operation_key=p.operation_key AND le.owner_id=0 AND le.bucket='CUSTODY' AND le.asset=r.asset AND le.delta=-r.amount)
  ),true) THEN RAISE EXCEPTION '입금 의도와 현재 고객·체인·출처·탐지·대사·금융 원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_credit_intent_binding_check ON bitcoin_credit_intents;
CREATE TRIGGER bitcoin_credit_intent_binding_check BEFORE INSERT ON bitcoin_credit_intents FOR EACH ROW EXECUTE FUNCTION bitcoin_credit_intent_binding();
-- 감사 호출이 조용히 빠져도 TX 종료 시점에 커밋을 거부한다. 과거 감사는 새 의도의 기록 시각 조건을 만족하지 못한다.
CREATE OR REPLACE FUNCTION bitcoin_credit_intent_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NULL AND a.action='BTC_CREDIT_INTENT_AUTHORIZED' AND a.target='bitcoin-credit:'||NEW.receipt_id::text
  AND a.outcome='SUCCESS' AND a.detail='POLICY:'||NEW.policy_id||':BASIS:'||NEW.basis_hash AND a.occurred_at>=NEW.recorded_at AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '입금 반입 의도에는 같은 근거 지문의 필수 감사가 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_credit_intent_audit_check ON bitcoin_credit_intents;
CREATE CONSTRAINT TRIGGER bitcoin_credit_intent_audit_check AFTER INSERT ON bitcoin_credit_intents DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_credit_intent_audit();
DROP TRIGGER IF EXISTS bitcoin_credit_intents_immutable ON bitcoin_credit_intents;
CREATE TRIGGER bitcoin_credit_intents_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON bitcoin_credit_intents FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
