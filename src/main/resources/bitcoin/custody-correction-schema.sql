-- 수탁 차이는 원래 원장을 수정하지 않고 지정 두 사람의 승인과 새 균형 역분개로만 정정한다.
CREATE TABLE IF NOT EXISTS bitcoin_custody_correction_requests (
 id uuid PRIMARY KEY,run_id uuid NOT NULL UNIQUE REFERENCES bitcoin_custody_reconciliation_runs(id),
 requester_id bigint NOT NULL REFERENCES users(id),requester_person uuid NOT NULL,requester_version bigint NOT NULL,
 checker_id bigint NOT NULL REFERENCES users(id),checker_person uuid NOT NULL,checker_version bigint NOT NULL,request_key uuid NOT NULL,
 expected_balance numeric(30,8) NOT NULL,observed_balance numeric(30,8) NOT NULL,difference numeric(30,8) NOT NULL CHECK(difference<>0),
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 kind varchar(24) NOT NULL DEFAULT 'BTC_CUSTODY_CORRECTION' CHECK(kind='BTC_CUSTODY_CORRECTION'),
 UNIQUE(requester_id,request_key),CHECK(requester_id<>checker_id AND requester_person<>checker_person AND payload_hash~'^[0-9a-f]{64}$' AND difference=observed_balance-expected_balance),
 FOREIGN KEY(kind,id) REFERENCES operator_approval_bindings(kind,request_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE TABLE IF NOT EXISTS bitcoin_custody_correction_views (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES bitcoin_custody_correction_requests(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,payload_hash varchar(64) NOT NULL,viewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS bitcoin_custody_correction_decisions (
 request_id uuid PRIMARY KEY REFERENCES bitcoin_custody_correction_requests(id),approver_id bigint NOT NULL REFERENCES users(id),session_id uuid NOT NULL,
 approved boolean NOT NULL,payload_hash varchar(64) NOT NULL,authorization_id uuid NOT NULL UNIQUE,decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS bitcoin_custody_correction_executions (
 request_id uuid PRIMARY KEY REFERENCES bitcoin_custody_correction_decisions(request_id),verification_run uuid NOT NULL UNIQUE REFERENCES bitcoin_custody_reconciliation_runs(id),
 operation_key varchar(160) NOT NULL UNIQUE REFERENCES ledger_transactions(operation_key) DEFERRABLE INITIALLY DEFERRED,
 executed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE OR REPLACE FUNCTION bitcoin_custody_correction_people(target uuid) RETURNS boolean LANGUAGE sql AS $$
 SELECT EXISTS(SELECT 1 FROM bitcoin_custody_correction_requests r JOIN users m ON m.id=r.requester_id JOIN users c ON c.id=r.checker_id
  WHERE r.id=target AND m.enabled AND c.enabled AND m.role='OPERATOR' AND c.role='APPROVER'
   AND m.operator_person_id=r.requester_person AND c.operator_person_id=r.checker_person
   AND m.version=r.requester_version AND c.version=r.checker_version
   AND m.operator_access_expires_at>clock_timestamp() AND c.operator_access_expires_at>clock_timestamp()
   AND m.operator_recovery_request_id IS NULL AND c.operator_recovery_request_id IS NULL
   AND m.id<>c.id AND m.operator_person_id<>c.operator_person_id);
$$;
-- 서비스 코드가 우회되더라도 최신 불일치·역할 분리·열람·서명·감사가 하나의 원본으로 묶이게 한다.
CREATE OR REPLACE FUNCTION bitcoin_custody_correction_request_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE maker record;checker record;
BEGIN
 SELECT * INTO maker FROM users WHERE id=NEW.requester_id FOR UPDATE;
 SELECT * INTO checker FROM users WHERE id=NEW.checker_id FOR UPDATE;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.created_at>clock_timestamp() OR NEW.expires_at<=NEW.created_at OR NEW.expires_at>NEW.created_at+interval '10 minutes 10 seconds'
  OR maker.role<>'OPERATOR' OR checker.role<>'APPROVER' OR maker.operator_person_id<>NEW.requester_person OR checker.operator_person_id<>NEW.checker_person
  OR maker.version<>NEW.requester_version OR checker.version<>NEW.checker_version OR maker.operator_person_id=checker.operator_person_id
  OR NOT maker.enabled OR NOT checker.enabled OR maker.operator_access_expires_at<=clock_timestamp() OR checker.operator_access_expires_at<=clock_timestamp()
  OR maker.operator_recovery_request_id IS NOT NULL OR checker.operator_recovery_request_id IS NOT NULL
  OR NOT EXISTS(SELECT 1 FROM bitcoin_custody_reconciliation_runs r JOIN bitcoin_custody_reconciliation_issues i ON i.run_id=r.id AND i.code='CUSTODY_BALANCE_MISMATCH'
    WHERE r.id=NEW.run_id AND r.status='MISMATCH' AND r.completed_at>=NEW.created_at-interval '10 minutes'
      AND i.expected::numeric=NEW.expected_balance AND i.actual::numeric=NEW.observed_balance)
  OR NOT EXISTS(SELECT 1 FROM operator_approval_bindings b WHERE b.kind='BTC_CUSTODY_CORRECTION' AND b.request_id=NEW.id
    AND b.requester_id=NEW.requester_id AND b.requester_version=maker.version AND b.payload_hash=NEW.payload_hash AND b.expires_at=NEW.expires_at),true) THEN
  RAISE EXCEPTION '현재 수탁 차이와 분리된 담당자·승인 결합이 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_custody_correction_request_binding_check ON bitcoin_custody_correction_requests;
CREATE CONSTRAINT TRIGGER bitcoin_custody_correction_request_binding_check AFTER INSERT ON bitcoin_custody_correction_requests DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_custody_correction_request_binding();
CREATE OR REPLACE FUNCTION bitcoin_custody_correction_view_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE request record;wanted_person uuid;wanted_version bigint;
BEGIN
 SELECT * INTO request FROM bitcoin_custody_correction_requests WHERE id=NEW.request_id;
 IF NEW.actor_id=request.requester_id THEN SELECT operator_person_id,version INTO wanted_person,wanted_version FROM users WHERE id=request.requester_id FOR UPDATE;
 ELSIF NEW.actor_id=request.checker_id THEN SELECT operator_person_id,version INTO wanted_person,wanted_version FROM users WHERE id=request.checker_id FOR UPDATE;END IF;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.viewed_at>clock_timestamp() OR NEW.payload_hash<>request.payload_hash OR NOT bitcoin_custody_correction_people(request.id)
  OR NEW.actor_person<>wanted_person OR NEW.actor_version<>wanted_version
  OR NOT monitoring_investigation_actor(NEW.actor_id,wanted_person,wanted_version,NEW.session_id,ARRAY['OPERATOR','APPROVER']::varchar[]),true) THEN
  RAISE EXCEPTION '현재 지정 인물의 같은 수탁 정정 내용만 열람할 수 있습니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_custody_correction_view_binding_check ON bitcoin_custody_correction_views;
CREATE TRIGGER bitcoin_custody_correction_view_binding_check BEFORE INSERT ON bitcoin_custody_correction_views FOR EACH ROW EXECUTE FUNCTION bitcoin_custody_correction_view_binding();
CREATE OR REPLACE FUNCTION bitcoin_custody_correction_decision_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE request record;
BEGIN
 SELECT * INTO request FROM bitcoin_custody_correction_requests WHERE id=NEW.request_id;
 PERFORM id FROM users WHERE id IN(request.requester_id,request.checker_id) ORDER BY id FOR UPDATE;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.decided_at>clock_timestamp() OR NEW.decided_at>request.expires_at OR NOT bitcoin_custody_correction_people(request.id)
  OR NEW.approver_id<>request.checker_id OR NEW.payload_hash<>request.payload_hash
  OR NOT monitoring_investigation_actor(NEW.approver_id,request.checker_person,request.checker_version,NEW.session_id,ARRAY['APPROVER']::varchar[])
  OR NOT EXISTS(SELECT 1 FROM bitcoin_custody_correction_views v WHERE v.request_id=request.id AND v.actor_id=NEW.approver_id
    AND v.actor_person=request.checker_person AND v.actor_version=request.checker_version
    AND v.session_id=NEW.session_id AND v.payload_hash=request.payload_hash AND v.viewed_at>=clock_timestamp()-interval '5 minutes')
  OR NOT EXISTS(SELECT 1 FROM operator_authorization_challenges c WHERE c.id=NEW.authorization_id AND c.user_id=NEW.approver_id
    AND c.session_id=NEW.session_id AND c.kind='BTC_CUSTODY_CORRECTION' AND c.request_id=request.id
    AND c.payload_hash=request.payload_hash AND c.approve=NEW.approved AND c.consumed),true) THEN
  RAISE EXCEPTION '수탁 정정 결정과 현재 열람·내용 결합 서명이 다릅니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_custody_correction_decision_binding_check ON bitcoin_custody_correction_decisions;
CREATE TRIGGER bitcoin_custody_correction_decision_binding_check BEFORE INSERT ON bitcoin_custody_correction_decisions FOR EACH ROW EXECUTE FUNCTION bitcoin_custody_correction_decision_binding();
CREATE OR REPLACE FUNCTION bitcoin_custody_correction_execution_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE request record;decision record;
BEGIN
 SELECT * INTO request FROM bitcoin_custody_correction_requests WHERE id=NEW.request_id;
 SELECT * INTO decision FROM bitcoin_custody_correction_decisions WHERE request_id=NEW.request_id;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.executed_at>clock_timestamp() OR NEW.executed_at>request.expires_at OR NOT decision.approved OR NOT bitcoin_custody_correction_people(request.id)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_custody_reconciliation_runs r JOIN bitcoin_custody_reconciliation_issues i ON i.run_id=r.id AND i.code='CUSTODY_BALANCE_MISMATCH'
    WHERE r.id=NEW.verification_run AND r.status='MISMATCH' AND r.completed_at>=decision.decided_at AND r.completed_at>=clock_timestamp()-interval '10 minutes'
      AND i.expected::numeric=request.expected_balance AND i.actual::numeric=request.observed_balance)
  OR NOT EXISTS(SELECT 1 FROM ledger_transactions t WHERE t.operation_key=NEW.operation_key AND t.kind='BTC_CUSTODY_CORRECTION' AND t.created_txid=txid_current())
  OR (SELECT count(*) FROM ledger_entries e WHERE e.operation_key=NEW.operation_key)<>2
  OR NOT EXISTS(SELECT 1 FROM ledger_entries e WHERE e.operation_key=NEW.operation_key AND e.owner_id=0 AND e.bucket='CUSTODY' AND e.asset='BTC' AND e.delta=-request.difference)
  OR NOT EXISTS(SELECT 1 FROM ledger_entries e WHERE e.operation_key=NEW.operation_key AND e.owner_id=0 AND e.bucket='SUSPENSE' AND e.asset='BTC' AND e.delta=request.difference),true) THEN
  RAISE EXCEPTION '새 대사로 재검증된 승인 정정과 정확한 균형 분개가 필요합니다';END IF;RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS bitcoin_custody_correction_execution_binding_check ON bitcoin_custody_correction_executions;
CREATE CONSTRAINT TRIGGER bitcoin_custody_correction_execution_binding_check AFTER INSERT ON bitcoin_custody_correction_executions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_custody_correction_execution_binding();
CREATE OR REPLACE FUNCTION bitcoin_custody_correction_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint;action_name text;target_name text;detail_text text;stamp timestamptz;
BEGIN
 IF TG_TABLE_NAME='bitcoin_custody_correction_requests' THEN actor:=NEW.requester_id;action_name:='BTC_CUSTODY_CORRECTION_PROPOSED';target_name:='bitcoin-custody-correction:'||NEW.id;detail_text:=NEW.payload_hash;stamp:=NEW.created_at;
 ELSIF TG_TABLE_NAME='bitcoin_custody_correction_views' THEN actor:=NEW.actor_id;action_name:='BTC_CUSTODY_CORRECTION_VIEWED';target_name:='bitcoin-custody-correction-view:'||NEW.id;detail_text:=NEW.payload_hash;stamp:=NEW.viewed_at;
 ELSIF TG_TABLE_NAME='bitcoin_custody_correction_decisions' THEN actor:=NEW.approver_id;action_name:='BTC_CUSTODY_CORRECTION_DECIDED';target_name:='bitcoin-custody-correction:'||NEW.request_id;detail_text:=NEW.payload_hash;stamp:=NEW.decided_at;
 ELSE actor:=NULL;action_name:='BTC_CUSTODY_CORRECTION_EXECUTED';target_name:='bitcoin-custody-correction:'||NEW.request_id;detail_text:='VERIFICATION:'||NEW.verification_run;stamp:=NEW.executed_at;END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NOT DISTINCT FROM actor AND a.action=action_name AND a.target=target_name AND a.outcome='SUCCESS' AND a.detail=detail_text AND a.occurred_at>=stamp) THEN
  RAISE EXCEPTION '수탁 정정과 필수 감사는 함께 보존해야 합니다';END IF;RETURN NULL;
END $$;
CREATE OR REPLACE FUNCTION bitcoin_custody_correction_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION '수탁 정정 요청·열람·결정·실행은 변경하거나 삭제할 수 없습니다';END $$;
DO $$ DECLARE table_name text;BEGIN
 FOREACH table_name IN ARRAY ARRAY['bitcoin_custody_correction_requests','bitcoin_custody_correction_views','bitcoin_custody_correction_decisions','bitcoin_custody_correction_executions'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS %I ON %I',table_name||'_immutable',table_name);
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION bitcoin_custody_correction_immutable()',table_name||'_immutable',table_name);
  EXECUTE format('DROP TRIGGER IF EXISTS bitcoin_custody_correction_audit_check ON %I',table_name);
  EXECUTE format('CREATE CONSTRAINT TRIGGER bitcoin_custody_correction_audit_check AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_custody_correction_audit()',table_name);
 END LOOP;
END $$;
-- 수탁 계정의 새 항목은 승인된 정정 실행과 같은 트랜잭션의 정확한 금액일 때만 추가 허용한다.
CREATE OR REPLACE FUNCTION bitcoin_pending_entry_kind() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.operation_key AND kind='BTC_DEPOSIT_PENDING') THEN RETURN NEW;END IF;
 IF NEW.bucket='PENDING' AND NEW.delta<0 AND EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.operation_key AND kind='BTC_DEPOSIT_CREDIT') THEN
  IF EXISTS(SELECT 1 FROM bitcoin_credited_deposits c JOIN bitcoin_credit_authorizations a ON a.receipt_id=c.receipt_id WHERE c.operation_key=NEW.operation_key AND c.created_txid=txid_current() AND a.user_id=NEW.owner_id AND a.asset=NEW.asset AND NEW.delta=-a.amount) THEN RETURN NEW;END IF;
 END IF;
 IF NEW.owner_id=0 AND NEW.bucket='CUSTODY' AND NEW.asset='BTC' THEN
  IF EXISTS(SELECT 1 FROM bitcoin_withdrawal_completion_events c JOIN bitcoin_withdrawals w ON w.id=c.withdrawal_id JOIN ledger_transactions t ON t.operation_key=c.operation_key WHERE c.operation_key=NEW.operation_key AND c.created_txid=txid_current() AND t.kind='BTC_WITHDRAWAL_COMPLETE' AND NEW.delta=w.amount) THEN RETURN NEW;END IF;
  IF EXISTS(SELECT 1 FROM bitcoin_withdrawal_completion_events c JOIN bitcoin_withdrawals w ON w.id=c.withdrawal_id JOIN bitcoin_payout_executions e ON e.withdrawal_id=w.id JOIN ledger_transactions t ON t.operation_key=c.miner_operation_key WHERE c.miner_operation_key=NEW.operation_key AND c.created_txid=txid_current() AND t.kind='BTC_WITHDRAWAL_MINER_FEE' AND NEW.delta=w.fee+e.company_exposure) THEN RETURN NEW;END IF;
  IF EXISTS(SELECT 1 FROM bitcoin_custody_correction_executions x JOIN bitcoin_custody_correction_requests r ON r.id=x.request_id JOIN bitcoin_custody_correction_decisions d ON d.request_id=r.id AND d.approved JOIN ledger_transactions t ON t.operation_key=x.operation_key WHERE x.operation_key=NEW.operation_key AND t.kind='BTC_CUSTODY_CORRECTION' AND t.created_txid=txid_current() AND NEW.delta=-r.difference) THEN RETURN NEW;END IF;
 END IF;
 RAISE EXCEPTION '보류/수탁 항목은 검증된 입금·출금 또는 승인 정정에서만 생성할 수 있습니다';
END $$;
