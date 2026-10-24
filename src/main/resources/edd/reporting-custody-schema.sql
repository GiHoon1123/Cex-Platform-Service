-- 원래 판단과 작성자를 고치지 않고 후속 업무 인계 요청·결정만 추가한다.
CREATE TABLE IF NOT EXISTS reporting_custody_requests (
 id uuid PRIMARY KEY,review_id uuid NOT NULL REFERENCES compliance_reviews(id),user_id bigint NOT NULL REFERENCES users(id),
 generation bigint NOT NULL CHECK(generation>0),requester_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 primary_id bigint NOT NULL REFERENCES users(id),checker_id bigint NOT NULL REFERENCES users(id),
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,reason_cipher bytea NOT NULL,expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(requester_id,request_key),UNIQUE(id,review_id,generation)
);
CREATE INDEX IF NOT EXISTS reporting_custody_request_review ON reporting_custody_requests(review_id,generation);
CREATE TABLE IF NOT EXISTS reporting_custody_views (
 id uuid PRIMARY KEY,request_id uuid NOT NULL REFERENCES reporting_custody_requests(id),actor_id bigint NOT NULL REFERENCES users(id),
 actor_person uuid NOT NULL,actor_version bigint NOT NULL,session_id uuid NOT NULL,payload_hash varchar(64) NOT NULL,
 viewed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS reporting_custody_decisions (
 request_id uuid PRIMARY KEY,review_id uuid NOT NULL,generation bigint NOT NULL,
 approver_id bigint NOT NULL REFERENCES users(id),approver_person uuid NOT NULL,approved boolean NOT NULL,
 decided_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(request_id,review_id,generation) REFERENCES reporting_custody_requests(id,review_id,generation)
);
-- 같은 세대의 인계 승인은 하나만 허용한다. 반려·만료한 요청은 지우지 않고 새로 요청한다.
CREATE UNIQUE INDEX IF NOT EXISTS reporting_custody_one_approval ON reporting_custody_decisions(review_id,generation) WHERE approved;
DROP TRIGGER IF EXISTS reporting_custody_request_no_change ON reporting_custody_requests;
CREATE TRIGGER reporting_custody_request_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON reporting_custody_requests FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS reporting_custody_view_no_change ON reporting_custody_views;
CREATE TRIGGER reporting_custody_view_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON reporting_custody_views FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
DROP TRIGGER IF EXISTS reporting_custody_decision_no_change ON reporting_custody_decisions;
CREATE TRIGGER reporting_custody_decision_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON reporting_custody_decisions FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
-- 최초 배정과 승인된 인계는 하나의 업무 상태 흐름이다. 옛 프로그램을 실행하는 호환 경로가 아니다.
CREATE OR REPLACE VIEW reporting_current_custody AS
 WITH latest AS (
  SELECT DISTINCT ON (r.review_id) r.* FROM reporting_custody_requests r
   JOIN reporting_custody_decisions d ON d.request_id=r.id AND d.approved
   ORDER BY r.review_id,r.generation DESC
 )
 SELECT r.id AS review_id,r.requester_id AS member_id,(r.payload::jsonb->>'requesterPerson')::uuid AS member_person,0::bigint AS generation,r.payload_hash AS stamp
  FROM compliance_reviews r WHERE NOT EXISTS(SELECT 1 FROM latest l WHERE l.review_id=r.id)
 UNION ALL
 SELECT r.id,r.checker_id,(r.payload::jsonb->>'checkerPerson')::uuid,0::bigint,r.payload_hash
  FROM compliance_reviews r WHERE NOT EXISTS(SELECT 1 FROM latest l WHERE l.review_id=r.id)
 UNION ALL
 SELECT l.review_id,l.primary_id,(l.payload::jsonb->'primary'->>'person')::uuid,l.generation,l.payload_hash FROM latest l
 UNION ALL
 SELECT l.review_id,l.checker_id,(l.payload::jsonb->'checker'->>'person')::uuid,l.generation,l.payload_hash FROM latest l;
