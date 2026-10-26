-- 외부 지급 전의 검토·독립 결정·실행 확정을 구분한다. 승인 상태만으로 원장 잔액을 차감하지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_payout_reviews (
 id uuid PRIMARY KEY,position bigserial UNIQUE,withdrawal_id uuid NOT NULL REFERENCES bitcoin_withdrawals(id),
 user_id bigint NOT NULL REFERENCES users(id),transfer_intent_id uuid NOT NULL REFERENCES bitcoin_withdrawal_transfer_intents(id),
 transfer_attempt_id uuid NOT NULL REFERENCES bitcoin_withdrawal_transfer_attempts(id),scope_hash varchar(64) NOT NULL,
 requester_id bigint NOT NULL REFERENCES users(id),requester_person uuid NOT NULL,requester_version bigint NOT NULL,
 checker_id bigint NOT NULL REFERENCES users(id),checker_person uuid NOT NULL,checker_version bigint NOT NULL,request_key uuid NOT NULL,
 key_id varchar(64) NOT NULL,ciphertext bytea NOT NULL,payload_hash varchar(64) NOT NULL,expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current(),
 kind varchar(24) NOT NULL DEFAULT 'BTC_WITHDRAWAL_PAYOUT' CHECK(kind='BTC_WITHDRAWAL_PAYOUT'),
 UNIQUE(requester_id,request_key),CHECK(requester_id<>checker_id AND requester_id<>user_id AND checker_id<>user_id AND requester_person<>checker_person),
 CHECK(scope_hash ~ '^[0-9a-f]{64}$' AND key_id ~ '^[0-9a-f]{64}$' AND payload_hash ~ '^[0-9a-f]{64}$' AND octet_length(ciphertext) BETWEEN 28 AND 16412),
 CHECK(expires_at>created_at AND expires_at<=created_at+interval '10 minutes'),
 FOREIGN KEY(kind,id) REFERENCES operator_approval_bindings(kind,request_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX IF NOT EXISTS bitcoin_withdrawal_payout_review_latest ON bitcoin_withdrawal_payout_reviews(withdrawal_id,position DESC);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_payout_views (
 id uuid PRIMARY KEY,review_id uuid NOT NULL REFERENCES bitcoin_withdrawal_payout_reviews(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,payload_hash varchar(64) NOT NULL,
 viewed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE INDEX IF NOT EXISTS bitcoin_withdrawal_payout_view_source ON bitcoin_withdrawal_payout_views(review_id,actor_id,session_id);
-- 챌린지는 단기 인증 상태다. 소비 시점에 검증하고 결정/내용 결합/감사를 영속화하며 임시 행을 영구 참조하지 않는다.
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_payout_decisions (
 review_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawal_payout_reviews(id),approver_id bigint NOT NULL REFERENCES users(id),
 session_id uuid NOT NULL,approved boolean NOT NULL,payload_hash varchar(64) NOT NULL,authorization_id uuid NOT NULL UNIQUE,
 decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS bitcoin_withdrawal_payout_commitments (
 withdrawal_id uuid PRIMARY KEY REFERENCES bitcoin_withdrawals(id),review_id uuid NOT NULL UNIQUE REFERENCES bitcoin_withdrawal_payout_decisions(review_id),
 user_id bigint NOT NULL REFERENCES users(id),payload_hash varchar(64) NOT NULL,
 committed_at timestamptz NOT NULL DEFAULT clock_timestamp(),created_txid bigint NOT NULL DEFAULT txid_current()
);
CREATE OR REPLACE FUNCTION bitcoin_payout_people(target uuid) RETURNS boolean LANGUAGE sql AS $$
 SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_reviews r JOIN users m ON m.id=r.requester_id JOIN users c ON c.id=r.checker_id
  WHERE r.id=target AND m.enabled AND m.role='OPERATOR' AND c.enabled AND c.role='APPROVER'
   AND m.operator_person_id=r.requester_person AND m.version=r.requester_version AND m.operator_access_expires_at>clock_timestamp()
   AND c.operator_person_id=r.checker_person AND c.version=r.checker_version AND c.operator_access_expires_at>clock_timestamp()
   AND m.id<>c.id AND m.operator_person_id<>c.operator_person_id);
$$;
CREATE OR REPLACE FUNCTION bitcoin_payout_source(target uuid) RETURNS boolean LANGUAGE sql AS $$
 SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_reviews r JOIN bitcoin_withdrawals w ON w.id=r.withdrawal_id AND w.user_id=r.user_id
  JOIN bitcoin_withdrawal_transfer_intents i ON i.id=r.transfer_intent_id AND i.withdrawal_id=w.id AND i.user_id=w.user_id
  JOIN bitcoin_withdrawal_transfer_attempts a ON a.id=r.transfer_attempt_id AND a.intent_id=i.id AND a.stage='TRANSFER'
  JOIN bitcoin_withdrawal_transfer_results observed ON observed.attempt_id=a.id AND observed.status='VERIFIED'
  JOIN bitcoin_withdrawal_reservation_events e ON e.withdrawal_id=w.id AND e.event_type='withdrawal_reserved'
  JOIN users u ON u.id=w.user_id AND u.enabled AND u.role='CUSTOMER' AND NOT u.trading_restricted
  WHERE r.id=target AND r.expires_at>clock_timestamp() AND bitcoin_payout_people(r.id)
   AND EXISTS(SELECT 1 FROM bitcoin_withdrawal_quotes q WHERE q.id=w.id AND q.user_version=u.version)
   AND EXISTS(SELECT 1 FROM kyc_profiles k JOIN kyc_attempts a ON a.id=k.active_attempt WHERE k.user_id=u.id
    AND k.identity_status='VERIFIED' AND k.valid_until>clock_timestamp() AND a.environment='live'
    AND EXISTS(SELECT 1 FROM kyc_reconciliations r WHERE r.attempt_id=k.active_attempt AND r.fresh_at BETWEEN clock_timestamp()-interval '15 minutes' AND clock_timestamp()))
   AND NOT EXISTS(SELECT 1 FROM relationship_active_exits WHERE user_id=u.id)
   AND NOT EXISTS(SELECT 1 FROM bitcoin_credit_chain_risks WHERE user_id=u.id)
   AND NOT EXISTS(SELECT 1 FROM deposit_monitoring_cases c WHERE c.user_id=u.id AND NOT monitoring_case_closed(c.id))
   AND(NOT EXISTS(SELECT 1 FROM deposit_monitoring_inputs WHERE user_id=u.id)
    OR EXISTS(SELECT 1 FROM deposit_monitoring_heads h JOIN deposit_monitoring_assessments ma ON ma.id=h.assessment_id AND ma.user_id=h.user_id
     WHERE h.user_id=u.id AND ma.input_cursor=(SELECT max(input_cursor) FROM deposit_monitoring_inputs WHERE user_id=u.id)
      AND ma.policy_id='NATIVE_SMALL_REFERENCE_INFLOW_V1' AND ma.outcome IN('NO_SIGNAL','REVIEW_REQUIRED') AND ma.unknown_transfers=0))
   AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_cancellations WHERE withdrawal_id=w.id)
   AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_events WHERE withdrawal_id=w.id)
   AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_reviews newer WHERE newer.withdrawal_id=w.id AND newer.position>r.position));
$$;
CREATE OR REPLACE FUNCTION bitcoin_payout_review_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original record;maker record;checker record;previous record;
BEGIN
 PERFORM id FROM users WHERE id IN(NEW.user_id,NEW.requester_id,NEW.checker_id) ORDER BY id FOR UPDATE;
 SELECT * INTO original FROM bitcoin_withdrawal_transfer_intents WHERE id=NEW.transfer_intent_id;
 SELECT * INTO maker FROM users WHERE id=NEW.requester_id;SELECT * INTO checker FROM users WHERE id=NEW.checker_id;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.created_at>clock_timestamp() OR original.withdrawal_id<>NEW.withdrawal_id OR original.user_id<>NEW.user_id
  OR original.requester_id<>NEW.requester_id OR original.requester_person<>NEW.requester_person OR original.requester_version<>NEW.requester_version
  OR maker.role<>'OPERATOR' OR NOT maker.enabled OR maker.operator_person_id<>NEW.requester_person OR maker.version<>NEW.requester_version OR maker.operator_access_expires_at<=clock_timestamp()
  OR checker.role<>'APPROVER' OR NOT checker.enabled OR checker.operator_person_id<>NEW.checker_person OR checker.version<>NEW.checker_version OR checker.operator_access_expires_at<=clock_timestamp(),true)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_transfer_attempts a JOIN bitcoin_withdrawal_transfer_results r ON r.attempt_id=a.id WHERE a.id=NEW.transfer_attempt_id AND a.intent_id=NEW.transfer_intent_id AND a.stage='TRANSFER' AND r.status='VERIFIED')
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_cancellations WHERE withdrawal_id=NEW.withdrawal_id)
  OR EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=NEW.withdrawal_id) THEN
  RAISE EXCEPTION '현재 출금·확인된 이전·지정 독립 인물이 필요합니다';END IF;
 SELECT r.expires_at,r.scope_hash,d.approved INTO previous FROM bitcoin_withdrawal_payout_reviews r LEFT JOIN bitcoin_withdrawal_payout_decisions d ON d.review_id=r.id
  WHERE r.withdrawal_id=NEW.withdrawal_id ORDER BY r.position DESC LIMIT 1;
 IF FOUND AND previous.approved IS DISTINCT FROM false AND previous.expires_at>clock_timestamp() AND previous.scope_hash=NEW.scope_hash THEN RAISE EXCEPTION '현재 지급 검토를 새 요청으로 우회할 수 없습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_review_binding_check ON bitcoin_withdrawal_payout_reviews;
CREATE TRIGGER bitcoin_payout_review_binding_check BEFORE INSERT ON bitcoin_withdrawal_payout_reviews FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_review_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_view_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE review record;expected_person uuid;expected_version bigint;
BEGIN
 SELECT * INTO review FROM bitcoin_withdrawal_payout_reviews WHERE id=NEW.review_id;
 PERFORM id FROM users WHERE id IN(review.user_id,review.requester_id,review.checker_id,NEW.actor_id) ORDER BY id FOR UPDATE;
 IF NEW.actor_id=review.requester_id THEN expected_person:=review.requester_person;expected_version:=review.requester_version;
 ELSIF NEW.actor_id=review.checker_id THEN expected_person:=review.checker_person;expected_version:=review.checker_version;END IF;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.viewed_at>clock_timestamp() OR NEW.payload_hash<>review.payload_hash
  OR NOT bitcoin_payout_people(review.id) OR NOT monitoring_investigation_actor(NEW.actor_id,expected_person,expected_version,NEW.session_id,ARRAY['OPERATOR','APPROVER']::varchar[]),true) THEN
  RAISE EXCEPTION '현재 지정 인물의 같은 지급 내용만 열람할 수 있습니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_view_binding_check ON bitcoin_withdrawal_payout_views;
CREATE TRIGGER bitcoin_payout_view_binding_check BEFORE INSERT ON bitcoin_withdrawal_payout_views FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_view_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_decision_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE review record;
BEGIN
 SELECT * INTO review FROM bitcoin_withdrawal_payout_reviews WHERE id=NEW.review_id;
 PERFORM id FROM users WHERE id IN(review.user_id,review.requester_id,review.checker_id,NEW.approver_id) ORDER BY id FOR UPDATE;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.decided_at>clock_timestamp() OR NOT bitcoin_payout_source(review.id)
  OR NEW.approver_id<>review.checker_id OR NEW.payload_hash<>review.payload_hash
  OR NOT monitoring_investigation_actor(NEW.approver_id,review.checker_person,review.checker_version,NEW.session_id,ARRAY['APPROVER']::varchar[])
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_views v WHERE v.review_id=review.id AND v.actor_id=NEW.approver_id AND v.actor_person=review.checker_person
   AND v.actor_version=review.checker_version AND v.session_id=NEW.session_id AND v.payload_hash=review.payload_hash AND v.viewed_at>=clock_timestamp()-interval '5 minutes')
  OR NOT EXISTS(SELECT 1 FROM operator_authorization_challenges c WHERE c.id=NEW.authorization_id AND c.user_id=NEW.approver_id AND c.session_id=NEW.session_id AND c.kind='BTC_WITHDRAWAL_PAYOUT'
   AND c.request_id=NEW.review_id AND c.payload_hash=NEW.payload_hash AND c.approve=NEW.approved AND c.consumed),true) THEN
  RAISE EXCEPTION '지급 결정과 현재 원본·독립 인물·열람·내용 서명이 다릅니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_decision_binding_check ON bitcoin_withdrawal_payout_decisions;
CREATE TRIGGER bitcoin_payout_decision_binding_check BEFORE INSERT ON bitcoin_withdrawal_payout_decisions FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_decision_binding();
CREATE OR REPLACE FUNCTION bitcoin_payout_commit_binding() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE review record;
BEGIN
 SELECT * INTO review FROM bitcoin_withdrawal_payout_reviews WHERE id=NEW.review_id;
 PERFORM id FROM users WHERE id IN(review.user_id,review.requester_id,review.checker_id) ORDER BY id FOR UPDATE;
 IF coalesce(NEW.created_txid<>txid_current() OR NEW.committed_at>clock_timestamp() OR NEW.withdrawal_id<>review.withdrawal_id OR NEW.user_id<>review.user_id
  OR NEW.payload_hash<>review.payload_hash OR NOT bitcoin_payout_source(review.id)
  OR NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_decisions WHERE review_id=review.id AND approved),true) THEN
  RAISE EXCEPTION '현재 승인된 같은 출금만 실행 확정할 수 있습니다';END IF;RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_commit_binding_check ON bitcoin_withdrawal_payout_commitments;
CREATE TRIGGER bitcoin_payout_commit_binding_check BEFORE INSERT ON bitcoin_withdrawal_payout_commitments FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_commit_binding();
-- 취소와 실행 확정은 같은 고객 행 아래 직렬화한다. 먼저 확정된 의도를 반대쪽에서 덮지 않는다.
CREATE OR REPLACE FUNCTION bitcoin_payout_cancel_exclusion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM id FROM users WHERE id=NEW.user_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=NEW.withdrawal_id) THEN RAISE EXCEPTION '지급 실행이 확정된 출금은 고객 취소할 수 없습니다';END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS bitcoin_payout_cancel_exclusion_check ON bitcoin_withdrawal_cancellations;
CREATE TRIGGER bitcoin_payout_cancel_exclusion_check BEFORE INSERT ON bitcoin_withdrawal_cancellations FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_cancel_exclusion();
CREATE OR REPLACE FUNCTION bitcoin_payout_audit() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actor bigint;action_name text;target_name text;stamp timestamptz;
BEGIN
 IF TG_TABLE_NAME='bitcoin_withdrawal_payout_reviews' THEN actor:=NEW.requester_id;action_name:='BTC_PAYOUT_REVIEW_PROPOSED';target_name:='bitcoin-payout-review:'||NEW.id;stamp:=NEW.created_at;
 ELSIF TG_TABLE_NAME='bitcoin_withdrawal_payout_views' THEN actor:=NEW.actor_id;action_name:='BTC_PAYOUT_REVIEW_VIEWED';target_name:='bitcoin-payout-view:'||NEW.id;stamp:=NEW.viewed_at;
 ELSIF TG_TABLE_NAME='bitcoin_withdrawal_payout_decisions' THEN actor:=NEW.approver_id;action_name:='BTC_PAYOUT_REVIEW_DECIDED';target_name:='bitcoin-payout-review:'||NEW.review_id;stamp:=NEW.decided_at;
 ELSE actor:=NULL;action_name:='BTC_PAYOUT_EXECUTION_COMMITTED';target_name:='bitcoin-withdrawal:'||NEW.withdrawal_id;stamp:=NEW.committed_at;END IF;
 IF NOT EXISTS(SELECT 1 FROM security_audit a WHERE a.actor_id IS NOT DISTINCT FROM actor AND a.action=action_name AND a.target=target_name AND a.outcome='SUCCESS'
  AND a.detail=NEW.payload_hash AND a.occurred_at>=stamp) THEN RAISE EXCEPTION '지급 검토·독립 결정·실행 확정과 필수 감사는 함께 보존해야 합니다';END IF;RETURN NULL;
END $$;
DO $$ DECLARE relation text;BEGIN
 FOREACH relation IN ARRAY ARRAY['bitcoin_withdrawal_payout_reviews','bitcoin_withdrawal_payout_views','bitcoin_withdrawal_payout_decisions','bitcoin_withdrawal_payout_commitments'] LOOP
  EXECUTE format('DROP TRIGGER IF EXISTS financial_preserve ON %I',relation);
  EXECUTE format('CREATE TRIGGER financial_preserve BEFORE UPDATE OR DELETE OR TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable()',relation);
  EXECUTE format('DROP TRIGGER IF EXISTS bitcoin_payout_audit_check ON %I',relation);
  EXECUTE format('CREATE CONSTRAINT TRIGGER bitcoin_payout_audit_check AFTER INSERT ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION bitcoin_payout_audit()',relation);
 END LOOP;
END $$;
