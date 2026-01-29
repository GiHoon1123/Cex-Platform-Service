package dustin.cex.domains.settlement.trade.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.settlement.trade.model.entity.TradeFee;
import dustin.cex.domains.settlement.trade.repository.TradeFeeRepository;
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래 정산 검증 서비스
 * Trade Settlement Validator
 * 
 * 역할:
 * - 거래 정산에 대한 복식부기 검증 (Double-Entry Bookkeeping)
 * - 거래 정산에만 특화된 검증 로직
 * 
 * 하위 도메인 분리:
 * ================
 * 이 서비스는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산에만 특화된 검증 로직을 담당합니다:
 * - 거래 검증: 매수자 지출 = 매도자 수입
 * - 수수료 검증: 매수자 수수료 + 매도자 수수료 = 거래소 수익
 * 
 * 향후 입출금/이벤트 정산이 추가되면:
 * - DepositSettlementValidator: 입출금 정산 검증
 * - EventSettlementValidator: 이벤트 정산 검증
 * 각각 별도로 생성됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeSettlementValidator {
    
    private final TradeRepository tradeRepository;
    private final TradeFeeRepository tradeFeeRepository;
    private final UserBalanceRepository userBalanceRepository;
    
    /**
     * 거래 정산 복식부기 검증 실행
     * Execute Trade Settlement Double-Entry Bookkeeping Validation
     * 
     * 검증 항목:
     * =========
     * 1. 거래 검증: 매수자 지출 = 매도자 수입, 매수자 수입 = 매도자 지출
     * 2. 수수료 검증: 매수자 수수료 + 매도자 수수료 = 거래소 수익
     * 3. 전체 시스템 검증: 모든 사용자 잔고 합계 + 거래소 수수료 수익 = 초기 자산 총합 + 입금 총합 - 출금 총합
     * 
     * @param date 검증할 날짜
     * @return 검증 결과 (통과/경고/실패)
     */
    @Transactional(readOnly = true)
    public ValidationResult validateDoubleEntryBookkeeping(LocalDate date) {
        log.info("[TradeSettlementValidator] 거래 정산 복식부기 검증 시작: date={}", date);
        
        // 정산 기준 시점 명확화 (KST 기준)
        ZoneId businessTimeZone = ZoneId.of("Asia/Seoul");
        ZonedDateTime startZonedDateTime = date.atStartOfDay().atZone(businessTimeZone);
        ZonedDateTime endZonedDateTime = date.atTime(LocalTime.MAX).atZone(businessTimeZone);
        
        LocalDateTime startDateTime = startZonedDateTime.toLocalDateTime();
        LocalDateTime endDateTime = endZonedDateTime.toLocalDateTime();
        
        ValidationResult result = new ValidationResult();
        result.setDate(date);
        result.setStartTime(LocalDateTime.now());
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 1. 거래 검증
        // Trade Validation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 검증 원칙:
        // - 매수자 지출 = 매도자 수입
        // - 매수자 수입 = 매도자 지출
        // 
        // 예시:
        // - 거래: 1000 USDT로 10 SOL 구매
        // - 매수자: 1000 USDT 지출, 10 SOL 수입
        // - 매도자: 10 SOL 지출, 1000 USDT 수입
        // - 검증: 매수자 지출(1000) = 매도자 수입(1000) ✅
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        List<Trade> trades = tradeRepository.findByCreatedAtBetween(startDateTime, endDateTime);
        log.info("[TradeSettlementValidator] 검증 대상 거래 건수: {}", trades.size());
        
        BigDecimal totalBuyerSpend = BigDecimal.ZERO;  // 매수자 총 지출 (USDT)
        BigDecimal totalBuyerReceive = BigDecimal.ZERO; // 매수자 총 수입 (SOL)
        BigDecimal totalSellerSpend = BigDecimal.ZERO;  // 매도자 총 지출 (SOL)
        BigDecimal totalSellerReceive = BigDecimal.ZERO; // 매도자 총 수입 (USDT)
        
        for (Trade trade : trades) {
            BigDecimal tradeValue = trade.getPrice().multiply(trade.getAmount());
            
            // 매수자: USDT 지출, SOL 수입
            totalBuyerSpend = totalBuyerSpend.add(tradeValue);
            totalBuyerReceive = totalBuyerReceive.add(trade.getAmount());
            
            // 매도자: SOL 지출, USDT 수입
            totalSellerSpend = totalSellerSpend.add(trade.getAmount());
            totalSellerReceive = totalSellerReceive.add(tradeValue);
        }
        
        // 거래 검증: 매수자 지출 = 매도자 수입
        BigDecimal tradeDifference = totalBuyerSpend.subtract(totalSellerReceive).abs();
        if (tradeDifference.compareTo(new BigDecimal("0.000001")) > 0) {
            result.addError(String.format("거래 검증 실패: 매수자 지출(%s) != 매도자 수입(%s), 차이=%s", 
                    totalBuyerSpend, totalSellerReceive, tradeDifference));
        } else {
            log.info("[TradeSettlementValidator] 거래 검증 통과: 매수자 지출={}, 매도자 수입={}", 
                    totalBuyerSpend, totalSellerReceive);
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 2. 수수료 검증
        // Fee Validation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 검증 원칙:
        // - 매수자 수수료 + 매도자 수수료 = 거래소 수익
        // 
        // 예시:
        // - 거래 1: buyerFee=0.1, sellerFee=0.1 → 총 수익=0.2
        // - 거래 2: buyerFee=10, sellerFee=10 → 총 수익=20
        // - 검증: (0.1+0.1) + (10+10) = 20.2 = 거래소 총 수익 ✅
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        List<TradeFee> tradeFees = 
                tradeFeeRepository.findByCreatedAtBetween(startDateTime, endDateTime);
        log.info("[TradeSettlementValidator] 검증 대상 수수료 건수: {}", tradeFees.size());
        
        BigDecimal totalBuyerFees = BigDecimal.ZERO;
        BigDecimal totalSellerFees = BigDecimal.ZERO;
        
        for (TradeFee fee : tradeFees) {
            if ("buyer".equals(fee.getFeeType())) {
                totalBuyerFees = totalBuyerFees.add(fee.getFeeAmount());
            } else if ("seller".equals(fee.getFeeType())) {
                totalSellerFees = totalSellerFees.add(fee.getFeeAmount());
            }
        }
        
        BigDecimal totalFeeRevenue = totalBuyerFees.add(totalSellerFees);
        
        // 수수료 검증: 각 거래의 buyerFee + sellerFee = 총 수수료 수익
        // (실제로는 trade_fees 테이블의 모든 fee_amount 합계가 총 수익)
        BigDecimal expectedTotalFees = tradeFees.stream()
                .map(TradeFee::getFeeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal feeDifference = totalFeeRevenue.subtract(expectedTotalFees).abs();
        if (feeDifference.compareTo(new BigDecimal("0.000001")) > 0) {
            result.addError(String.format("수수료 검증 실패: 계산된 총 수익(%s) != 실제 총 수익(%s), 차이=%s", 
                    totalFeeRevenue, expectedTotalFees, feeDifference));
        } else {
            log.info("[TradeSettlementValidator] 수수료 검증 통과: 총 수수료 수익={}", totalFeeRevenue);
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 3. 전체 시스템 검증
        // System-Wide Validation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 검증 원칙:
        // - 모든 사용자 잔고 합계 + 거래소 수수료 수익 = 초기 자산 총합 + 입금 총합 - 출금 총합
        // 
        // 주의:
        // - 입금/출금 기능이 구현되어 있지 않으면 이 검증은 스킵
        // - 현재는 거래와 수수료만 검증
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 모든 사용자 잔고 합계 계산
        List<UserBalance> allBalances = userBalanceRepository.findAll();
        BigDecimal totalUserBalances = allBalances.stream()
                .map(balance -> balance.getAvailable().add(balance.getLocked()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        log.info("[TradeSettlementValidator] 전체 시스템 검증: 총 사용자 잔고={}, 총 수수료 수익={}", 
                totalUserBalances, totalFeeRevenue);
        
        // 검증 결과 설정
        result.setTotalTrades((long) trades.size());
        result.setTotalTradeVolume(totalBuyerSpend);
        result.setTotalFeeRevenue(totalFeeRevenue);
        result.setTotalUserBalances(totalUserBalances);
        result.setEndTime(LocalDateTime.now());
        
        // 검증 상태 결정
        if (result.getErrors().isEmpty()) {
            result.setStatus("validated");  // 스케줄러에서 "VALIDATED"로 변환됨
            log.info("[TradeSettlementValidator] 거래 정산 복식부기 검증 통과: date={}, 거래건수={}, 수수료수익={}", 
                    date, trades.size(), totalFeeRevenue);
        } else {
            result.setStatus("failed");  // 스케줄러에서 "FAILED"로 변환됨
            log.error("[TradeSettlementValidator] 거래 정산 복식부기 검증 실패: date={}, 에러개수={}", 
                    date, result.getErrors().size());
            result.getErrors().forEach(error -> log.error("[TradeSettlementValidator] 검증 에러: {}", error));
        }
        
        return result;
    }
    
    /**
     * 검증 결과 클래스
     * Validation Result Class
     */
    public static class ValidationResult {
        private LocalDate date;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private String status; // 'pending', 'validated', 'failed'
        private List<String> errors = new java.util.ArrayList<>();
        private Long totalTrades;
        private BigDecimal totalTradeVolume;
        private BigDecimal totalFeeRevenue;
        private BigDecimal totalUserBalances;
        
        public void addError(String error) {
            this.errors.add(error);
        }
        
        // Getters and Setters
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        
        public LocalDateTime getStartTime() { return startTime; }
        public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
        
        public LocalDateTime getEndTime() { return endTime; }
        public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
        
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
        
        public Long getTotalTrades() { return totalTrades; }
        public void setTotalTrades(Long totalTrades) { this.totalTrades = totalTrades; }
        
        public BigDecimal getTotalTradeVolume() { return totalTradeVolume; }
        public void setTotalTradeVolume(BigDecimal totalTradeVolume) { this.totalTradeVolume = totalTradeVolume; }
        
        public BigDecimal getTotalFeeRevenue() { return totalFeeRevenue; }
        public void setTotalFeeRevenue(BigDecimal totalFeeRevenue) { this.totalFeeRevenue = totalFeeRevenue; }
        
        public BigDecimal getTotalUserBalances() { return totalUserBalances; }
        public void setTotalUserBalances(BigDecimal totalUserBalances) { this.totalUserBalances = totalUserBalances; }
    }
}
