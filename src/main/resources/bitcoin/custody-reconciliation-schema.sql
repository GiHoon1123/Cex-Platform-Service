-- 노드 관찰값과 수탁 원장 비교는 금융 원본을 고치지 않고 실행별 불변 증거로 남긴다.
CREATE TABLE IF NOT EXISTS bitcoin_custody_reconciliation_runs (
 id uuid PRIMARY KEY,network varchar(32) NOT NULL,wallet varchar(64) NOT NULL,status varchar(16) NOT NULL,
 node_block_hash varchar(64),node_block_height bigint,wallet_trusted numeric(30,8),wallet_untrusted numeric(30,8),
 wallet_immature numeric(30,8),ledger_custody numeric(30,8),
 source_payload text,source_hash varchar(64),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),completed_at timestamptz,
 CHECK(status IN('RUNNING','MATCHED','INCOMPLETE','MISMATCH','FAILED')),
 CHECK((status='RUNNING' AND completed_at IS NULL) OR(status<>'RUNNING' AND completed_at IS NOT NULL)),
 CHECK(source_hash IS NULL OR source_hash~'^[0-9a-f]{64}$')
);
CREATE TABLE IF NOT EXISTS bitcoin_custody_reconciliation_issues (
 run_id uuid NOT NULL REFERENCES bitcoin_custody_reconciliation_runs(id),code varchar(48) NOT NULL,
 expected varchar(160) NOT NULL,actual varchar(160) NOT NULL,PRIMARY KEY(run_id,code)
);
CREATE OR REPLACE FUNCTION bitcoin_custody_reconciliation_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND OLD.status='RUNNING' AND NEW.id=OLD.id AND NEW.network=OLD.network AND NEW.wallet=OLD.wallet
   AND NEW.created_at=OLD.created_at AND NEW.status<>'RUNNING' THEN RETURN NEW;END IF;
 RAISE EXCEPTION '완료한 수탁 대사 증거는 변경하거나 삭제할 수 없습니다';
END $$;
DROP TRIGGER IF EXISTS bitcoin_custody_reconciliation_runs_immutable ON bitcoin_custody_reconciliation_runs;
CREATE TRIGGER bitcoin_custody_reconciliation_runs_immutable BEFORE UPDATE OR DELETE ON bitcoin_custody_reconciliation_runs FOR EACH ROW EXECUTE FUNCTION bitcoin_custody_reconciliation_immutable();
DROP TRIGGER IF EXISTS bitcoin_custody_reconciliation_issues_immutable ON bitcoin_custody_reconciliation_issues;
CREATE OR REPLACE FUNCTION bitcoin_custody_reconciliation_issue_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION '수탁 대사 쟁점은 변경하거나 삭제할 수 없습니다';END $$;
CREATE TRIGGER bitcoin_custody_reconciliation_issues_immutable BEFORE UPDATE OR DELETE ON bitcoin_custody_reconciliation_issues FOR EACH ROW EXECUTE FUNCTION bitcoin_custody_reconciliation_issue_immutable();
CREATE OR REPLACE FUNCTION bitcoin_custody_reconciliation_no_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION '수탁 대사 증거는 비울 수 없습니다';END $$;
DROP TRIGGER IF EXISTS bitcoin_custody_reconciliation_runs_no_truncate ON bitcoin_custody_reconciliation_runs;
CREATE TRIGGER bitcoin_custody_reconciliation_runs_no_truncate BEFORE TRUNCATE ON bitcoin_custody_reconciliation_runs EXECUTE FUNCTION bitcoin_custody_reconciliation_no_truncate();
DROP TRIGGER IF EXISTS bitcoin_custody_reconciliation_issues_no_truncate ON bitcoin_custody_reconciliation_issues;
CREATE TRIGGER bitcoin_custody_reconciliation_issues_no_truncate BEFORE TRUNCATE ON bitcoin_custody_reconciliation_issues EXECUTE FUNCTION bitcoin_custody_reconciliation_no_truncate();
