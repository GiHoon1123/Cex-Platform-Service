-- =====================================================
-- trade_fees 테이블 생성
-- Create trade_fees table for fee recording
-- =====================================================
-- 
-- 목적:
-- - 각 거래에서 발생한 수수료를 상세히 기록
-- - 정산 시 수수료 수익 집계의 기초 데이터
-- 
-- 정산에서의 중요성:
-- ==================
-- 1. 수수료 수익 집계:
--    - 거래소가 벌어들인 총 수수료 수익을 계산하기 위해 필요
--    - 거래 금액에 따라 수수료 금액이 다름 (1000달러 → 0.1달러, 100000달러 → 10달러)
--    - 각 거래마다 실제 수수료 금액을 기록해야 총 수수료 수익을 집계할 수 있음
-- 
-- 2. 사용자별 수수료 납부 내역:
--    - 사용자가 지불한 총 수수료를 집계하여 리포트 제공
--    - 세금 신고용 데이터로 활용 가능
-- 
-- 3. 거래쌍별 수수료 분석:
--    - 어떤 거래쌍에서 수수료 수익이 많이 발생하는지 분석
-- 
-- 데이터 구조 설명:
-- =================
-- - trade_id: 어떤 거래에서 발생한 수수료인지 (trades 테이블 참조)
-- - user_id: 누가 수수료를 납부했는지 (users 테이블 참조)
-- - fee_type: 'buyer' (매수자) 또는 'seller' (매도자)
-- - fee_rate: 적용된 수수료율 (예: 0.0001 = 0.01%)
-- - fee_amount: 실제 수수료 금액 (예: 0.1달러 또는 10달러)
-- - fee_mint: 수수료가 차감된 자산 (SOL 또는 USDT)
-- - trade_value: 거래 금액 (수수료 계산 기준)
-- 
-- 예시:
-- =====
-- 거래 1: 1000 USDT로 10 SOL 구매
-- - buyerFee: fee_rate=0.0001, fee_amount=0.1, trade_value=1000
-- - sellerFee: fee_rate=0.0001, fee_amount=0.1, trade_value=1000
-- - 거래소 수익: 0.1 + 0.1 = 0.2 USDT
-- 
-- 거래 2: 100000 USDT로 1000 SOL 구매
-- - buyerFee: fee_rate=0.0001, fee_amount=10, trade_value=100000
-- - sellerFee: fee_rate=0.0001, fee_amount=10, trade_value=100000
-- - 거래소 수익: 10 + 10 = 20 USDT
-- 
-- 일별 정산 시:
-- - SELECT SUM(fee_amount) FROM trade_fees WHERE created_at BETWEEN '2026-01-28' AND '2026-01-29'
-- - 결과: 0.2 + 20 = 20.2 USDT (하루 총 수수료 수익)
-- =====================================================

CREATE TABLE IF NOT EXISTS trade_fees (
    id BIGSERIAL PRIMARY KEY,
    
    -- 거래 ID (어떤 거래에서 발생한 수수료인지)
    -- 하나의 거래(Trade)에서 두 개의 수수료가 발생 (매수자 수수료 + 매도자 수수료)
    trade_id BIGINT NOT NULL REFERENCES trades(id) ON DELETE CASCADE,
    
    -- 사용자 ID (누가 수수료를 납부했는지)
    -- 매수자 또는 매도자 중 한 명
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    
    -- 수수료 유형: 'buyer' (매수자) 또는 'seller' (매도자)
    -- 'buyer': 매수 주문을 낸 사용자가 납부한 수수료
    -- 'seller': 매도 주문을 낸 사용자가 납부한 수수료
    fee_type VARCHAR(20) NOT NULL CHECK (fee_type IN ('buyer', 'seller')),
    
    -- 적용된 수수료율 (소수점 형식)
    -- 예: 0.0001 = 0.01% (만분의 일)
    -- 중요성: 수수료 정책이 변경되어도 과거 거래의 수수료율을 추적 가능
    fee_rate DECIMAL(10, 6) NOT NULL,
    
    -- 실제 수수료 금액 (거래 금액 × 수수료율)
    -- 계산식: fee_amount = trade_value × fee_rate
    -- 예: trade_value=1000, fee_rate=0.0001 → fee_amount=0.1
    -- 예: trade_value=100000, fee_rate=0.0001 → fee_amount=10
    -- 정산 활용: 일별/월별 총 수수료 수익 = SUM(fee_amount)
    fee_amount DECIMAL(30, 9) NOT NULL,
    
    -- 수수료가 차감된 자산 종류
    -- 값: 'USDT' (대부분의 경우), 'SOL' (특정 거래쌍에서 가능)
    -- 정산 활용: 자산별 수수료 수익 집계
    fee_mint VARCHAR(255) NOT NULL,
    
    -- 거래 금액 (수수료 계산의 기준이 된 금액)
    -- 의미: 체결 가격 × 체결 수량 = 거래 금액
    -- 예: 100 USDT × 10 SOL = 1000 USDT
    -- 정산 활용: 거래 금액별 수수료 수익 분석
    trade_value DECIMAL(30, 9) NOT NULL,
    
    -- 수수료 기록 생성 시간
    -- 정산 활용: 일별/월별 수수료 수익 집계 시 날짜 필터링에 사용
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 인덱스 생성 (성능 최적화)
-- ===========================

-- trade_id 인덱스: 특정 거래의 모든 수수료 내역 조회 시 사용
CREATE INDEX IF NOT EXISTS idx_trade_fees_trade_id ON trade_fees(trade_id);

-- user_id 인덱스: 사용자별 수수료 납부 내역 조회 시 사용
CREATE INDEX IF NOT EXISTS idx_trade_fees_user_id ON trade_fees(user_id);

-- created_at 인덱스: 일별/월별 수수료 수익 집계 시 사용 (가장 중요!)
CREATE INDEX IF NOT EXISTS idx_trade_fees_created_at ON trade_fees(created_at);

-- fee_mint 인덱스: 자산별 수수료 수익 집계 시 사용
CREATE INDEX IF NOT EXISTS idx_trade_fees_fee_mint ON trade_fees(fee_mint);

-- 복합 인덱스: 사용자별 일별 수수료 집계 시 사용 (성능 최적화)
CREATE INDEX IF NOT EXISTS idx_trade_fees_user_created ON trade_fees(user_id, created_at);

-- 복합 인덱스: 자산별 일별 수수료 집계 시 사용 (성능 최적화)
CREATE INDEX IF NOT EXISTS idx_trade_fees_mint_created ON trade_fees(fee_mint, created_at);

-- =====================================================
-- 테이블 생성 완료
-- =====================================================
-- 
-- 사용 예시:
-- ==========
-- 1. 일별 총 수수료 수익 조회:
--    SELECT SUM(fee_amount) as total_revenue
--    FROM trade_fees
--    WHERE created_at BETWEEN '2026-01-28 00:00:00' AND '2026-01-29 00:00:00';
-- 
-- 2. 사용자별 일별 수수료 납부액:
--    SELECT SUM(fee_amount) as total_fees_paid
--    FROM trade_fees
--    WHERE user_id = 1 AND created_at BETWEEN '2026-01-28' AND '2026-01-29';
-- 
-- 3. 거래쌍별 수수료 수익:
--    SELECT fee_mint, SUM(fee_amount) as total_revenue
--    FROM trade_fees
--    WHERE created_at BETWEEN '2026-01-28' AND '2026-01-29'
--    GROUP BY fee_mint;
-- =====================================================
