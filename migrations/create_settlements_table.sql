-- =====================================================
-- settlements 테이블 생성
-- Create settlements table for settlement summary
-- =====================================================
-- 
-- 목적:
-- - 일별/월별 정산 요약 정보 저장
-- - 거래소의 일별/월별 성과를 요약하여 기록
-- 
-- 정산에서의 중요성:
-- ==================
-- 1. 거래소 수익 집계:
--    - 일별/월별 총 거래 건수 및 거래량
--    - 일별/월별 총 수수료 수익
--    - 거래소가 벌어들인 수익을 한눈에 파악 가능
-- 
-- 2. 거래쌍별 성과 분석:
--    - base_mint별로 집계하여 어떤 자산이 수익성이 높은지 분석
-- 
-- 3. 검증 상태 관리:
--    - validation_status: 정산 검증 상태
--    - 검증 실패 시 validation_error에 상세 에러 메시지 저장
-- =====================================================

CREATE TABLE IF NOT EXISTS settlements (
    id BIGSERIAL PRIMARY KEY,
    
    -- 정산일 (YYYY-MM-DD)
    -- 일별 정산: 해당 날짜 (예: 2026-01-28)
    -- 월별 정산: 해당 월의 첫 날 (예: 2026-01-01)
    settlement_date DATE NOT NULL,
    
    -- 정산 유형: 'daily' (일별) 또는 'monthly' (월별)
    settlement_type VARCHAR(20) NOT NULL CHECK (settlement_type IN ('daily', 'monthly')),
    
    -- 총 거래 건수
    -- 해당 기간 동안 발생한 모든 체결 건수
    total_trades BIGINT NOT NULL DEFAULT 0,
    
    -- 총 거래량 (USDT 기준)
    -- 해당 기간 동안 발생한 모든 거래의 총 금액
    -- 계산식: SUM(price × amount) for all trades
    total_volume DECIMAL(30, 9) NOT NULL DEFAULT 0,
    
    -- 총 수수료 수익 (USDT 기준)
    -- 해당 기간 동안 거래소가 벌어들인 총 수수료 수익
    -- 계산식: SUM(fee_amount) from trade_fees
    -- 예: 거래 1에서 0.2달러, 거래 2에서 20달러 → 총 20.2달러
    total_fee_revenue DECIMAL(30, 9) NOT NULL DEFAULT 0,
    
    -- 거래한 사용자 수
    -- 해당 기간 동안 거래에 참여한 고유 사용자 수
    total_users BIGINT NOT NULL DEFAULT 0,
    
    -- 기준 자산 (NULL이면 전체, 특정 자산이면 해당 자산만)
    -- NULL: 모든 거래쌍 포함
    -- "SOL": SOL/USDT 거래만 포함
    -- "BTC": BTC/USDT 거래만 포함
    base_mint VARCHAR(255),
    
    -- 기준 통화 (기본값: USDT)
    quote_mint VARCHAR(255) DEFAULT 'USDT',
    
    -- 검증 상태: 'pending', 'validated', 'failed'
    -- 'pending': 아직 검증하지 않음
    -- 'validated': 검증 통과 (모든 검증 항목 통과)
    -- 'failed': 검증 실패 (검증 에러 발생)
    validation_status VARCHAR(20) DEFAULT 'pending' CHECK (validation_status IN ('pending', 'validated', 'failed')),
    
    -- 검증 실패 시 에러 메시지
    -- validation_status가 'failed'일 때 상세 에러 메시지 저장
    validation_error TEXT,
    
    -- 정산 데이터 생성 시간
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- 검증 완료 시간
    -- validation_status가 'validated' 또는 'failed'로 변경된 시간
    validated_at TIMESTAMPTZ,
    
    -- 유니크 제약조건: 같은 날짜, 같은 유형, 같은 거래쌍은 중복 불가
    UNIQUE(settlement_date, settlement_type, base_mint, quote_mint)
);

-- 인덱스 생성 (성능 최적화)
-- ===========================

-- settlement_date 인덱스: 날짜별 정산 조회 시 사용
CREATE INDEX IF NOT EXISTS idx_settlements_date ON settlements(settlement_date);

-- settlement_type 인덱스: 일별/월별 정산 조회 시 사용
CREATE INDEX IF NOT EXISTS idx_settlements_type ON settlements(settlement_type);

-- 복합 인덱스: 날짜와 유형으로 조회 시 사용 (가장 중요!)
CREATE INDEX IF NOT EXISTS idx_settlements_date_type ON settlements(settlement_date, settlement_type);

-- base_mint 인덱스: 거래쌍별 정산 조회 시 사용
CREATE INDEX IF NOT EXISTS idx_settlements_base_mint ON settlements(base_mint);

-- validation_status 인덱스: 검증 상태별 조회 시 사용
CREATE INDEX IF NOT EXISTS idx_settlements_validation_status ON settlements(validation_status);

-- =====================================================
-- 테이블 생성 완료
-- =====================================================
-- 
-- 사용 예시:
-- ==========
-- 1. 일별 정산 데이터 조회:
--    SELECT * FROM settlements 
--    WHERE settlement_date = '2026-01-28' AND settlement_type = 'daily';
-- 
-- 2. 월별 총 수수료 수익 조회:
--    SELECT SUM(total_fee_revenue) 
--    FROM settlements 
--    WHERE settlement_date BETWEEN '2026-01-01' AND '2026-01-31' 
--    AND settlement_type = 'daily';
-- 
-- 3. 거래쌍별 수익 분석:
--    SELECT base_mint, SUM(total_fee_revenue) as total_revenue
--    FROM settlements
--    WHERE settlement_date BETWEEN '2026-01-01' AND '2026-01-31'
--    AND settlement_type = 'daily'
--    GROUP BY base_mint;
-- =====================================================
