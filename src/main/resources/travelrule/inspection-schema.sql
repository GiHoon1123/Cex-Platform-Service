-- 실제로 확인한 수신 원본·당시 운영자·세션을 보존한다. 이름·생일·주소는 이 접근 표나 감사 문자열에 복사하지 않는다.
CREATE TABLE IF NOT EXISTS codevasp_transfer_inspections (
 id uuid PRIMARY KEY,attempt_id uuid NOT NULL REFERENCES codevasp_transfer_results(attempt_id),
 receipt_id uuid NOT NULL REFERENCES bitcoin_deposit_receipts(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,
 key_id varchar(64) NOT NULL CHECK(key_id ~ '^[a-f0-9]{64}$'),
 request_mac varchar(64) NOT NULL CHECK(request_mac ~ '^[a-f0-9]{64}$'),response_mac varchar(64) NOT NULL CHECK(response_mac ~ '^[a-f0-9]{64}$'),
 viewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE OR REPLACE FUNCTION codevasp_transfer_inspection_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.created_txid<>txid_current() OR NEW.viewed_at>clock_timestamp() OR NOT EXISTS(
  SELECT 1 FROM codevasp_transfer_attempts a JOIN codevasp_transfer_results r ON r.attempt_id=a.id
   JOIN codevasp_transfer_heads h ON h.intent_id=a.intent_id AND h.attempt_id=a.id
   JOIN codevasp_transfer_detail_assessments d ON d.attempt_id=a.id
   JOIN users u ON u.id=NEW.actor_id JOIN refresh_tokens s ON s.user_id=u.id AND s.session_id=NEW.session_id
   WHERE a.id=NEW.attempt_id AND a.receipt_id=NEW.receipt_id AND r.status='DATA_RECEIVED'
    AND a.key_id=NEW.key_id AND r.key_id=NEW.key_id AND a.content_mac=NEW.request_mac AND r.content_mac=NEW.response_mac
    AND u.enabled=true AND u.role IN ('OPERATOR','APPROVER') AND u.operator_person_id=NEW.actor_person AND u.version=NEW.actor_version
    AND s.revoked=false AND s.mfa_verified_at IS NOT NULL AND s.mfa_credential_fingerprint ~ '^[a-f0-9]{64}$'
 ) THEN RAISE EXCEPTION '검토 접근과 현재 운영자·세션·수신 원본이 다릅니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS codevasp_transfer_inspection_binding_check ON codevasp_transfer_inspections;
CREATE TRIGGER codevasp_transfer_inspection_binding_check BEFORE INSERT ON codevasp_transfer_inspections FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_inspection_binding();
DROP TRIGGER IF EXISTS codevasp_transfer_inspection_immutable ON codevasp_transfer_inspections;
CREATE TRIGGER codevasp_transfer_inspection_immutable BEFORE UPDATE OR DELETE OR TRUNCATE ON codevasp_transfer_inspections FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
-- 나중에 다른 호출자가 접근 표만 쓰고 감사를 빠뜨려도 개인정보 응답을 성공으로 확정하지 못하게 한다.
CREATE OR REPLACE FUNCTION codevasp_transfer_inspection_audit_complete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.action='CODE_TRANSFER_EVIDENCE_INSPECTED'
  AND a.target='codevasp-inspection:'||NEW.id::text AND a.actor_id=NEW.actor_id AND a.actor_person=NEW.actor_person
  AND a.outcome='SUCCESS' AND a.occurred_at>=NEW.viewed_at AND length(a.source_address)>0) THEN
  RAISE EXCEPTION '개인정보 접근 기록에는 해당 운영자의 필수 감사가 필요합니다';END IF;
 RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS codevasp_transfer_inspection_audit_complete_check ON codevasp_transfer_inspections;
CREATE CONSTRAINT TRIGGER codevasp_transfer_inspection_audit_complete_check AFTER INSERT ON codevasp_transfer_inspections
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION codevasp_transfer_inspection_audit_complete();
