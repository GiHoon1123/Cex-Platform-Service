-- 원래 종료 개시 승인과 철회 요청·제공·결정은 모두 추가만 허용한다.
CREATE TABLE IF NOT EXISTS relationship_exit_rescissions (
 id uuid PRIMARY KEY,exit_id uuid NOT NULL,user_id bigint NOT NULL,requester_id bigint NOT NULL REFERENCES users(id),
 checker_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,payload text NOT NULL,payload_hash varchar(64) NOT NULL,
 reason_cipher bytea NOT NULL,expires_at timestamptz NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(exit_id,user_id) REFERENCES relationship_exit_decisions(review_id,user_id),
 UNIQUE(requester_id,request_key),UNIQUE(id,exit_id,user_id)
);
CREATE INDEX IF NOT EXISTS relationship_rescission_exit ON relationship_exit_rescissions(exit_id);
CREATE TABLE IF NOT EXISTS relationship_exit_rescission_views (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES relationship_exit_rescissions(id),dossier_id uuid NOT NULL REFERENCES edd_case_dossiers(id),
 actor_id bigint NOT NULL REFERENCES users(id),actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,
 page integer NOT NULL CHECK(page>=0),digest varchar(64) NOT NULL,payload_hash varchar(64) NOT NULL,
 viewed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS relationship_exit_rescission_decisions (
 request_id uuid PRIMARY KEY,exit_id uuid NOT NULL,user_id bigint NOT NULL,approver_id bigint NOT NULL REFERENCES users(id),
 approver_person uuid NOT NULL,approved boolean NOT NULL,decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(request_id,exit_id,user_id) REFERENCES relationship_exit_rescissions(id,exit_id,user_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS relationship_rescission_one_approved ON relationship_exit_rescission_decisions(exit_id) WHERE approved;
-- 현재 차단은 원래 판단을 삭제하지 않고 유효한 승인과 독립 철회로 계산한다.
CREATE OR REPLACE VIEW relationship_active_exits AS
 SELECT d.review_id,d.user_id FROM relationship_exit_decisions d WHERE d.approved
 AND NOT EXISTS(SELECT 1 FROM relationship_exit_rescission_decisions w WHERE w.exit_id=d.review_id AND w.approved);
-- 같은 고객 행을 얻은 뒤 현재 결정을 재확인한다. 직접 삽입에도 애플리케이션과 같은 단일 효과를 강제한다.
CREATE OR REPLACE FUNCTION relationship_exit_require_single_active() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
BEGIN
 IF NEW.approved THEN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
   RAISE EXCEPTION '종료 업무는 READ COMMITTED 격리 수준이 필요합니다' USING ERRCODE='25000';
  END IF;
  PERFORM 1 FROM users WHERE id=NEW.user_id FOR UPDATE;
  IF EXISTS(SELECT 1 FROM relationship_active_exits WHERE user_id=NEW.user_id) THEN
   RAISE EXCEPTION '이미 유효한 종료 개시 결정이 있습니다' USING ERRCODE='23505';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION relationship_rescission_require_active() RETURNS trigger LANGUAGE plpgsql VOLATILE AS $$
BEGIN
 IF NEW.approved THEN
  IF current_setting('transaction_isolation') <> 'read committed' THEN
   RAISE EXCEPTION '종료 업무는 READ COMMITTED 격리 수준이 필요합니다' USING ERRCODE='25000';
  END IF;
  PERFORM 1 FROM users WHERE id=NEW.user_id FOR UPDATE;
  IF NOT EXISTS(SELECT 1 FROM relationship_active_exits WHERE user_id=NEW.user_id AND review_id=NEW.exit_id) THEN
   RAISE EXCEPTION '철회할 현재 종료 개시 결정이 없습니다' USING ERRCODE='23505';
  END IF;
 END IF;
 RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS relationship_exit_single_active ON relationship_exit_decisions;
CREATE TRIGGER relationship_exit_single_active BEFORE INSERT ON relationship_exit_decisions FOR EACH ROW EXECUTE FUNCTION relationship_exit_require_single_active();
DROP TRIGGER IF EXISTS relationship_rescission_active ON relationship_exit_rescission_decisions;
CREATE TRIGGER relationship_rescission_active BEFORE INSERT ON relationship_exit_rescission_decisions FOR EACH ROW EXECUTE FUNCTION relationship_rescission_require_active();
DROP TRIGGER IF EXISTS relationship_rescission_no_change ON relationship_exit_rescissions;
CREATE TRIGGER relationship_rescission_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON relationship_exit_rescissions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS relationship_rescission_view_no_change ON relationship_exit_rescission_views;
CREATE TRIGGER relationship_rescission_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON relationship_exit_rescission_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS relationship_rescission_decision_no_change ON relationship_exit_rescission_decisions;
CREATE TRIGGER relationship_rescission_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON relationship_exit_rescission_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
