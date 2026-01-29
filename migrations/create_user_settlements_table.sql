-- =====================================================
-- user_settlements 테이블 생성
-- Create user_settlements table for user-specific settlement
-- =====================================================
-- 
-- 목적:
-- - 사용자별 일별/월별 거래 및 수수료 내역 요약
-- - 사용자 리포트 생성의 기초 데이터
-- 
-- 정산에서의 중요성:
-- ==================
-- 1. 사용자별 거래 리포트:
--    - 사용자가 얼마나 거래했는지, 얼마나 수수료를 납부했는지 파악
-- 
-- 2. VIP 등급 산정:
--    - 거래량 기준으로 VIP 등급 결정
-- 
-- 3. 세금 신고용 데이터:
--    - 사용자가 지불한 총 수수료를 세금 신고에 활용
-- =====================================================

CREATE TABLE IF NOT EXISTS user_settlements (
    id BIGSERIAL PRIMARY KEY,
    
    -- 사용자 ID (누구의 정산 내역인지)
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- 정산일 (YYYY-MM-DD)
    -- 일별 정산: 해당 날짜 (예: 2026-01-28)
    -- 월별 정산: 해당 월의 첫 날 (예: 2026-01-01)
    settlement_date DATE NOT NULL,
    
    -- 정산 유형: 'daily' (일별) 또는 'monthly' (월별)
    settlement_type VARCHAR(20) NOT NULL CHECK (settlement_type IN ('daily', 'monthly')),
    
    -- 사용자 총 거래 건수
    -- 해당 기간 동안 사용자가 참여한 모든 체결 건수
    -- 매수자 또는 매도자로 참여한 거래 모두 포함
    total_trades BIGINT NOT NULL DEFAULT 0,
    
    -- 사용자 총 거래량 (USDT 기준)
    -- 해당 기간 동안 사용자가 거래한 총 금액
    -- 계산식: SUM(price × amount) for user's trades
    total_volume DECIMAL(30, 9) NOT NULL DEFAULT 0,
    
    -- 사용자가 지불한 총 수수료
    -- 해당 기간 동안 사용자가 납부한 모든 수수료의 합계
    -- 계산식: SUM(fee_amount) from trade_fees where user_id = ...
    -- 예: 거래 1에서 0.1달러, 거래 2에서 0.2달러 → 총 0.3달러
    total_fees_paid DECIMAL(30, 9) NOT NULL DEFAULT 0,
    
    -- 기준 자산 (NULL이면 전체, 특정 자산이면 해당 자산만)
    -- NULL: 사용자의 모든 거래쌍 포함
    -- "SOL": 사용자의 SOL/USDT 거래만 포함
    base_mint VARCHAR(255),
    
    -- 기준 통화 (기본값: USDT)
    quote_mint VARCHAR(255) DEFAULT 'USDT',
    
    -- 정산 데이터 생성 시간
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- 유니크 제약조건: 같은 사용자, 같은 날짜, 같은 유형, 같은 거래쌍은 중복 불가
    UNIQUE(user_id, settlement_date, settlement_type, base_mint, quote_mint)
);

-- 인덱스 생성 (성능 최적화)
-- ===========================

-- user_id 인덱스: 사용자별 정산 조회 시 사용
CREATE INDEX IF NOT EXISTS idx_user_settlements_user_id ON user_settlements(user_id);

-- settlement_date 인덱스: 날짜별 정산 조회 시 사용
CREATE INDEX IF NOT EXISTS idx_user_settlements_date ON user_settlements(settlement_date);

-- 복합 인덱스: 사용자별 일별 정산 조회 시 사용 (가장 중요!)
CREATE INDEX IF NOT EXISTS idx_user_settlements_user_date ON user_settlements(user_id, settlement_date);

-- settlement_type 인덱스: 일별/월별 정산 조회 시 사용
CREATE INDEX IF NOT EXISTS idx_user_settlements_type ON user_settlements(settlement_type);

-- =====================================================
-- 테이블 생성 완료
-- =====================================================
-- 
-- 사용 예시:
-- ==========
-- 1. 사용자별 일별 정산 조회:
--    SELECT * FROM user_settlements 
--    WHERE user_id = 1 AND settlement_date = '2026-01-28' AND settlement_type = 'daily';
-- 
-- 2. 사용자별 월별 총 수수료 납부액:
--    SELECT SUM(total_fees_paid) 
--    FROM user_settlements 
--    WHERE user_id = 1 AND settlement_date BETWEEN '2026-01-01' AND '2026-01-31' 
--    AND settlement_type = 'daily';
-- 
-- 3. 거래량 상위 사용자 조회:
--    SELECT user_id, SUM(total_volume) as total_volume
--    FROM user_settlements
--    WHERE settlement_date BETWEEN '2026-01-01' AND '2026-01-31'
--    AND settlement_type = 'daily'
--    GROUP BY user_id
--    ORDER BY total_volume DESC
--    LIMIT 10;
-- =====================================================
