-- 관계 종료 개시는 보고 판단과 별개다. 사유는 암호화하고 지정된 두 인물만 제공받는다.
CREATE TABLE IF NOT EXISTS relationship_exit_requests (
 id uuid PRIMARY KEY,case_id uuid NOT NULL REFERENCES edd_cases(id),user_id bigint NOT NULL REFERENCES users(id),
 referral_id uuid NOT NULL REFERENCES edd_reports(id),requester_id bigint NOT NULL REFERENCES users(id),
 checker_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,facts_cipher bytea NOT NULL,expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(requester_id,request_key),UNIQUE(id,user_id)
);
CREATE INDEX IF NOT EXISTS relationship_exit_request_referral ON relationship_exit_requests(referral_id);
CREATE INDEX IF NOT EXISTS relationship_exit_request_assigned ON relationship_exit_requests(checker_id,created_at);
-- 전체 근거 페이지 제공은 본인 계정·인물·버전·세션과 요청 해시에 묶는다. 실제 독해를 자동 증명하지는 않는다.
CREATE TABLE IF NOT EXISTS relationship_exit_views (
 id uuid PRIMARY KEY,review_id uuid NOT NULL REFERENCES relationship_exit_requests(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,page integer NOT NULL CHECK(page>=0),
 payload_hash varchar(64) NOT NULL,viewed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS relationship_exit_view_actor ON relationship_exit_views(review_id,actor_id,viewed_at);
CREATE TABLE IF NOT EXISTS relationship_exit_decisions (
 review_id uuid PRIMARY KEY,user_id bigint NOT NULL,approver_id bigint NOT NULL REFERENCES users(id),
 approver_person uuid NOT NULL,approved boolean NOT NULL,decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(review_id,user_id) REFERENCES relationship_exit_requests(id,user_id)
);
-- 과거의 영구 단일 승인 제약은 철회 후 새 결정을 막는다. 지원하지 않는 옛 스키마를 자동 변환하지 않는다.
DO $$ BEGIN
 IF to_regclass('relationship_exit_one_approved') IS NOT NULL THEN
  RAISE EXCEPTION '철회와 재개시를 지원하는 현재 종료 스키마가 필요합니다';
 END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS relationship_exit_decision_customer ON relationship_exit_decisions(review_id,user_id);
CREATE INDEX IF NOT EXISTS relationship_exit_approved_lookup ON relationship_exit_decisions(user_id) WHERE approved;
-- 종료 개시 요청·제공·결정은 수정하지 않는다. 자산 반환과 종료 완료는 별도 검증 대상이다.
DROP TRIGGER IF EXISTS relationship_exit_request_no_change ON relationship_exit_requests;
CREATE TRIGGER relationship_exit_request_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON relationship_exit_requests FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS relationship_exit_view_no_change ON relationship_exit_views;
CREATE TRIGGER relationship_exit_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON relationship_exit_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS relationship_exit_decision_no_change ON relationship_exit_decisions;
CREATE TRIGGER relationship_exit_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON relationship_exit_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();

-- 미결 감독의 시간·식별자 순회를 지원한다. 결정 여부는 별도 불변 결정 표에서 확인한다.
CREATE INDEX IF NOT EXISTS relationship_exit_request_created ON relationship_exit_requests(created_at,id);
