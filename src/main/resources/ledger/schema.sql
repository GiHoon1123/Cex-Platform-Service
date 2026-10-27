-- 자산별 균형과 확정 뒤 변경 금지를 디비에서도 강제한다. 기존 금융 기록을 변환하지 않는다.
CREATE TABLE IF NOT EXISTS ledger_transactions (
    operation_key VARCHAR(160) PRIMARY KEY,
    kind VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    created_txid BIGINT NOT NULL DEFAULT txid_current()
);
CREATE TABLE IF NOT EXISTS ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    operation_key VARCHAR(160) NOT NULL REFERENCES ledger_transactions(operation_key),
    owner_id BIGINT NOT NULL CHECK(owner_id>=0),
    bucket VARCHAR(12) NOT NULL,
    asset VARCHAR(50) NOT NULL,
    delta NUMERIC(38,9) NOT NULL CHECK(delta<>0),
    UNIQUE(operation_key,owner_id,bucket,asset),
    CONSTRAINT ledger_entry_bucket_owner CHECK ((owner_id>0 AND bucket IN ('AVAILABLE','LOCKED','PENDING')) OR (owner_id=0 AND bucket IN ('FEE','OPENING','CUSTODY','SUSPENSE')))
);
-- 기존 금융 행을 보존한 채 새 계정 제약을 먼저 검증한 뒤 이전 익명 제약을 제거한다. 시작마다 전체 재검증하지 않는다.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conname='ledger_entry_bucket_owner' AND conrelid='ledger_entries'::regclass AND pg_get_constraintdef(oid) LIKE '%SUSPENSE%') THEN
        ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS ledger_entry_bucket_owner;
        ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entry_bucket_owner CHECK
         ((owner_id>0 AND bucket IN ('AVAILABLE','LOCKED','PENDING')) OR (owner_id=0 AND bucket IN ('FEE','OPENING','CUSTODY','SUSPENSE')));
    END IF;
END $$;
ALTER TABLE ledger_entries DROP CONSTRAINT IF EXISTS ledger_entries_check;
CREATE INDEX IF NOT EXISTS idx_ledger_account ON ledger_entries(owner_id,asset,bucket);
CREATE OR REPLACE FUNCTION cex_ledger_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION '확정 원장은 변경하거나 삭제할 수 없습니다'; END $$;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='ledger_transactions_immutable' AND tgrelid='ledger_transactions'::regclass) THEN
        CREATE TRIGGER ledger_transactions_immutable BEFORE UPDATE OR DELETE ON ledger_transactions FOR EACH ROW EXECUTE FUNCTION cex_ledger_immutable();
        CREATE TRIGGER ledger_entries_immutable BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION cex_ledger_immutable();
    END IF;
END $$;
-- 전체 비우기는 행 삭제 트리거를 실행하지 않는다. 확정 원본 두 표의 문장 단위 비우기도 별도로 차단한다.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='ledger_transactions_no_truncate' AND tgrelid='ledger_transactions'::regclass) THEN
        CREATE TRIGGER ledger_transactions_no_truncate BEFORE TRUNCATE ON ledger_transactions FOR EACH STATEMENT EXECUTE FUNCTION cex_ledger_immutable();
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='ledger_entries_no_truncate' AND tgrelid='ledger_entries'::regclass) THEN
        CREATE TRIGGER ledger_entries_no_truncate BEFORE TRUNCATE ON ledger_entries FOR EACH STATEMENT EXECUTE FUNCTION cex_ledger_immutable();
    END IF;
END $$;
CREATE OR REPLACE FUNCTION cex_ledger_entry_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM ledger_transactions WHERE operation_key=NEW.operation_key AND created_txid=txid_current()) THEN
        RAISE EXCEPTION '확정한 과거 거래에 원장 항목을 추가할 수 없습니다';
    END IF;
    RETURN NEW;
END $$;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='ledger_entry_owner' AND tgrelid='ledger_entries'::regclass) THEN
        CREATE TRIGGER ledger_entry_owner BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE FUNCTION cex_ledger_entry_owner();
    END IF;
END $$;
CREATE OR REPLACE FUNCTION cex_ledger_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS(SELECT 1 FROM ledger_entries WHERE operation_key=NEW.operation_key GROUP BY asset HAVING sum(delta)<>0) THEN
        RAISE EXCEPTION '자산별 원장 합계가 맞지 않습니다';
    END IF;
    IF EXISTS(SELECT 1 FROM ledger_entries e JOIN ledger_entries changed ON changed.operation_key=NEW.operation_key AND changed.owner_id=e.owner_id AND changed.asset=e.asset AND changed.bucket=e.bucket
        WHERE e.owner_id>0 GROUP BY e.owner_id,e.asset,e.bucket HAVING sum(e.delta)<0) THEN
        RAISE EXCEPTION '고객 원장 잔고가 음수입니다';
    END IF;
    RETURN NEW;
END $$;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='ledger_balance_commit' AND tgrelid='ledger_transactions'::regclass) THEN
        CREATE CONSTRAINT TRIGGER ledger_balance_commit AFTER INSERT ON ledger_transactions DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION cex_ledger_balanced();
    END IF;
END $$;
-- 대사 보고서는 기준 순번과 원본 증거를 남긴다. 금액을 자동 수정하는 경로는 없다.
CREATE TABLE IF NOT EXISTS reconciliation_runs (
    id UUID PRIMARY KEY, started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), completed_at TIMESTAMPTZ,
    engine_id VARCHAR(36), through_sequence BIGINT, status VARCHAR(16) NOT NULL
);
CREATE TABLE IF NOT EXISTS reconciliation_evidence (
    run_id UUID NOT NULL REFERENCES reconciliation_runs(id), event_id VARCHAR(120) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(run_id,event_id)
);
CREATE TABLE IF NOT EXISTS reconciliation_issues (
    id BIGSERIAL PRIMARY KEY, run_id UUID NOT NULL REFERENCES reconciliation_runs(id),
    category VARCHAR(50) NOT NULL, reference TEXT NOT NULL, expected TEXT NOT NULL, actual TEXT NOT NULL
);
-- 완료한 대사 증거를 나중에 바꾸거나 보태서 정상 보고서로 위장하지 못하게 한다.
CREATE OR REPLACE FUNCTION cex_reconciliation_open() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM reconciliation_runs WHERE id=NEW.run_id AND status='RUNNING') THEN
        RAISE EXCEPTION '완료한 대사에 증거를 추가할 수 없습니다';
    END IF;
    RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION cex_reconciliation_finished() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR OLD.status<>'RUNNING' THEN RAISE EXCEPTION '대사 보고서를 변경하거나 삭제할 수 없습니다'; END IF;
    RETURN NEW;
END $$;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM pg_trigger WHERE tgname='reconciliation_evidence_immutable' AND tgrelid='reconciliation_evidence'::regclass) THEN
        CREATE TRIGGER reconciliation_evidence_immutable BEFORE UPDATE OR DELETE ON reconciliation_evidence FOR EACH ROW EXECUTE FUNCTION cex_ledger_immutable();
        CREATE TRIGGER reconciliation_issues_immutable BEFORE UPDATE OR DELETE ON reconciliation_issues FOR EACH ROW EXECUTE FUNCTION cex_ledger_immutable();
        CREATE TRIGGER reconciliation_evidence_open BEFORE INSERT ON reconciliation_evidence FOR EACH ROW EXECUTE FUNCTION cex_reconciliation_open();
        CREATE TRIGGER reconciliation_issues_open BEFORE INSERT ON reconciliation_issues FOR EACH ROW EXECUTE FUNCTION cex_reconciliation_open();
        CREATE TRIGGER reconciliation_finished BEFORE UPDATE OR DELETE ON reconciliation_runs FOR EACH ROW EXECUTE FUNCTION cex_reconciliation_finished();
    END IF;
END $$;
