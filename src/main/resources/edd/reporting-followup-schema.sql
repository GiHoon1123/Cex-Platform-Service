-- 외부 결과는 직원의 관찰이다. 접수 참조가 있어도 자동 제출 성공이나 법적 기한 충족으로 승격하지 않는다.
CREATE TABLE IF NOT EXISTS reporting_followups (
 id uuid PRIMARY KEY,review_id uuid NOT NULL REFERENCES compliance_reviews(id),user_id bigint NOT NULL REFERENCES users(id),
 actor_id bigint NOT NULL REFERENCES users(id),request_key uuid NOT NULL,
 payload text NOT NULL,payload_hash varchar(64) NOT NULL,facts_cipher bytea NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),UNIQUE(actor_id,request_key)
);
CREATE INDEX IF NOT EXISTS reporting_followup_review_time ON reporting_followups(review_id,recorded_at,id);
DROP TRIGGER IF EXISTS reporting_followup_no_change ON reporting_followups;
CREATE TRIGGER reporting_followup_no_change BEFORE UPDATE OR DELETE OR TRUNCATE ON reporting_followups FOR EACH STATEMENT EXECUTE FUNCTION kyc_event_immutable();
