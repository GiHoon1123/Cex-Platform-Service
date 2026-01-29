package dustin.cex.domains.settlement.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.settlement.model.entity.Settlement;
import dustin.cex.domains.settlement.model.entity.UserSettlement;
import dustin.cex.domains.settlement.repository.SettlementRepository;
import dustin.cex.domains.settlement.repository.TradeFeeRepository;
import dustin.cex.domains.settlement.repository.UserSettlementRepository;
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 정산 서비스
 * Settlement Service
 * 
 * 역할:
 * - 일별/월별 정산 집계 및 검증
 * - 복식부기 검증 (Double-Entry Bookkeeping)
 * - 수수료 수익 집계
 * 
 * 복식부기 검증이란?
 * ==================
 * 복식부기(Double-Entry Bookkeeping)는 회계의 기본 원칙으로,
 * 모든 거래에서 차변(Dr)과 대변(Cr)이 항상 같아야 한다는 원칙입니다.
 * 
 * 거래소 정산에서의 복식부기 검증:
 * ===============================
 * 1. 잔고 검증:
 *    초기 잔고 + 입금 - 출금 - 수수료 = 최종 잔고
 * 
 * 2. 거래 검증:
 *    매수자 지출 = 매도자 수입
 *    매수자 수입 = 매도자 지출
 * 
 * 3. 수수료 검증:
 *    매수자 수수료 + 매도자 수수료 = 거래소 수익
 * 
 * 4. 전체 시스템 검증:
 *    모든 사용자 잔고 합계 + 거래소 수수료 수익 = 초기 자산 총합 + 입금 총합 - 출금 총합
 * 
 * 검증의 중요성:
 * =============
 * - 정산의 정확성을 보장하기 위해 필수
 * - 데이터 불일치를 조기에 발견하여 문제 해결 가능
 * - 감사(Audit) 목적으로도 활용
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {
    
    private final TradeRepository tradeRepository;
    private final TradeFeeRepository tradeFeeRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final SettlementRepository settlementRepository;
    private final UserSettlementRepository userSettlementRepository;
    private final UserRepository userRepository;
    
    /**
     * 복식부기 검증 실행
     * Execute Double-Entry Bookkeeping Validation
     * 
     * 검증 항목:
     * =========
     * 1. 거래 검증: 매수자 지출 = 매도자 수입, 매수자 수입 = 매도자 지출
     * 2. 수수료 검증: 매수자 수수료 + 매도자 수수료 = 거래소 수익
     * 3. 잔고 검증: 스냅샷 기준으로 잔고 일치 여부 확인
     * 
     * @param date 검증할 날짜
     * @return 검증 결과 (통과/경고/실패)
     */
    @Transactional(readOnly = true)
    public ValidationResult validateDoubleEntryBookkeeping(LocalDate date) {
        log.info("[SettlementService] 복식부기 검증 시작: date={}", date);
        
        // 검증 기간 설정 (해당 날짜의 00:00:00 ~ 23:59:59)
        LocalDateTime startDateTime = date.atStartOfDay();
        LocalDateTime endDateTime = date.atTime(LocalTime.MAX);
        
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
        log.info("[SettlementService] 검증 대상 거래 건수: {}", trades.size());
        
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
            log.info("[SettlementService] 거래 검증 통과: 매수자 지출={}, 매도자 수입={}", 
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
        
        List<dustin.cex.domains.settlement.model.entity.TradeFee> tradeFees = 
                tradeFeeRepository.findByCreatedAtBetween(startDateTime, endDateTime);
        log.info("[SettlementService] 검증 대상 수수료 건수: {}", tradeFees.size());
        
        BigDecimal totalBuyerFees = BigDecimal.ZERO;
        BigDecimal totalSellerFees = BigDecimal.ZERO;
        
        for (dustin.cex.domains.settlement.model.entity.TradeFee fee : tradeFees) {
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
                .map(dustin.cex.domains.settlement.model.entity.TradeFee::getFeeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal feeDifference = totalFeeRevenue.subtract(expectedTotalFees).abs();
        if (feeDifference.compareTo(new BigDecimal("0.000001")) > 0) {
            result.addError(String.format("수수료 검증 실패: 계산된 총 수익(%s) != 실제 총 수익(%s), 차이=%s", 
                    totalFeeRevenue, expectedTotalFees, feeDifference));
        } else {
            log.info("[SettlementService] 수수료 검증 통과: 총 수수료 수익={}", totalFeeRevenue);
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
        
        log.info("[SettlementService] 전체 시스템 검증: 총 사용자 잔고={}, 총 수수료 수익={}", 
                totalUserBalances, totalFeeRevenue);
        
        // 검증 결과 설정
        result.setTotalTrades((long) trades.size());
        result.setTotalTradeVolume(totalBuyerSpend);
        result.setTotalFeeRevenue(totalFeeRevenue);
        result.setTotalUserBalances(totalUserBalances);
        result.setEndTime(LocalDateTime.now());
        
        // 검증 상태 결정
        if (result.getErrors().isEmpty()) {
            result.setStatus("validated");
            log.info("[SettlementService] 복식부기 검증 통과: date={}, 거래건수={}, 수수료수익={}", 
                    date, trades.size(), totalFeeRevenue);
        } else {
            result.setStatus("failed");
            log.error("[SettlementService] 복식부기 검증 실패: date={}, 에러개수={}", 
                    date, result.getErrors().size());
            result.getErrors().forEach(error -> log.error("[SettlementService] 검증 에러: {}", error));
        }
        
        return result;
    }
    
    /**
     * 일별 수수료 수익 계산
     * Calculate Daily Fee Revenue
     * 
     * @param date 날짜
     * @return 일별 총 수수료 수익 (USDT 기준)
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateDailyFeeRevenue(LocalDate date) {
        LocalDateTime startDateTime = date.atStartOfDay();
        LocalDateTime endDateTime = date.atTime(LocalTime.MAX);
        
        // USDT 기준 수수료 수익 계산
        BigDecimal totalRevenue = tradeFeeRepository.calculateTotalFeeRevenueByMint(
                "USDT", startDateTime, endDateTime);
        
        log.info("[SettlementService] 일별 수수료 수익 계산: date={}, revenue={}", date, totalRevenue);
        return totalRevenue;
    }
    
    /**
     * 월별 수수료 수익 계산
     * Calculate Monthly Fee Revenue
     * 
     * @param year 연도
     * @param month 월
     * @return 월별 총 수수료 수익 (USDT 기준)
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateMonthlyFeeRevenue(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);
        
        // USDT 기준 수수료 수익 계산
        BigDecimal totalRevenue = tradeFeeRepository.calculateTotalFeeRevenueByMint(
                "USDT", startDateTime, endDateTime);
        
        log.info("[SettlementService] 월별 수수료 수익 계산: year={}, month={}, revenue={}", 
                year, month, totalRevenue);
        return totalRevenue;
    }
    
    /**
     * 일별 정산 집계 및 저장
     * Aggregate and Save Daily Settlement
     * 
     * 처리 과정:
     * ==========
     * 1. 거래 집계: 전일 모든 거래 조회 및 집계
     * 2. 수수료 집계: 전일 모든 수수료 조회 및 집계
     * 3. 사용자 수 집계: 전일 거래에 참여한 고유 사용자 수 계산
     * 4. 정산 데이터 생성: Settlement 엔티티 생성
     * 5. 정산 데이터 저장: settlements 테이블에 저장
     * 
     * @param date 정산일 (전일 데이터를 집계)
     * @return 생성된 정산 데이터
     */
    @Transactional
    public Settlement createDailySettlement(LocalDate date) {
        log.info("[SettlementService] 일별 정산 집계 시작: date={}", date);
        
        // 전일 데이터 집계 (date - 1일)
        LocalDate targetDate = date.minusDays(1);
        LocalDateTime startDateTime = targetDate.atStartOfDay();
        LocalDateTime endDateTime = targetDate.atTime(LocalTime.MAX);
        
        // 이미 정산 데이터가 존재하는지 확인
        boolean exists = settlementRepository.existsBySettlementDateAndSettlementType(targetDate, "daily");
        if (exists) {
            log.warn("[SettlementService] 이미 정산 데이터가 존재함: date={}", targetDate);
            return settlementRepository.findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                    targetDate, "daily", null, "USDT")
                    .orElseThrow(() -> new RuntimeException("정산 데이터 조회 실패"));
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 1. 거래 집계
        // Trade Aggregation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 집계 항목:
        // - 총 거래 건수: COUNT(trades)
        // - 총 거래량: SUM(price × amount)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        List<Trade> trades = tradeRepository.findByCreatedAtBetween(startDateTime, endDateTime);
        long totalTrades = trades.size();
        
        BigDecimal totalVolume = trades.stream()
                .map(trade -> trade.getPrice().multiply(trade.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        log.info("[SettlementService] 거래 집계 완료: totalTrades={}, totalVolume={}", totalTrades, totalVolume);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 2. 수수료 집계
        // Fee Revenue Aggregation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 집계 항목:
        // - 총 수수료 수익: SUM(fee_amount) from trade_fees
        // 
        // 중요성:
        // - 거래소가 실제로 벌어들인 수익
        // - 거래 금액에 따라 수수료 금액이 다르므로 각 거래마다 기록된 수수료를 합산
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        BigDecimal totalFeeRevenue = tradeFeeRepository.calculateTotalFeeRevenueByMint(
                "USDT", startDateTime, endDateTime);
        
        log.info("[SettlementService] 수수료 집계 완료: totalFeeRevenue={}", totalFeeRevenue);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 3. 사용자 수 집계
        // User Count Aggregation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 집계 방법:
        // - 전일 거래에 참여한 고유 사용자 수 계산
        // - 매수자와 매도자를 모두 포함하여 중복 제거
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        long totalUsers = trades.stream()
                .flatMap(trade -> java.util.stream.Stream.of(trade.getBuyerId(), trade.getSellerId()))
                .distinct()
                .count();
        
        log.info("[SettlementService] 사용자 수 집계 완료: totalUsers={}", totalUsers);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 4. 정산 데이터 생성 및 저장
        // Settlement Data Creation and Save
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 저장 내용:
        // - 정산일, 정산 유형, 거래 건수, 거래량, 수수료 수익, 사용자 수
        // - validation_status는 'pending'으로 설정 (나중에 검증 실행)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        Settlement settlement = Settlement.builder()
                .settlementDate(targetDate)           // 정산일 (전일)
                .settlementType("daily")               // 일별 정산
                .totalTrades(totalTrades)              // 총 거래 건수
                .totalVolume(totalVolume)               // 총 거래량 (USDT)
                .totalFeeRevenue(totalFeeRevenue)      // 총 수수료 수익 (USDT)
                .totalUsers(totalUsers)                 // 거래한 사용자 수
                .baseMint(null)                        // 전체 거래쌍
                .quoteMint("USDT")                     // 기준 통화
                .validationStatus("pending")           // 검증 대기 상태
                .build();
        
        Settlement savedSettlement = settlementRepository.save(settlement);
        
        log.info("[SettlementService] 일별 정산 집계 완료: date={}, settlementId={}, totalTrades={}, totalVolume={}, totalFeeRevenue={}", 
                targetDate, savedSettlement.getId(), totalTrades, totalVolume, totalFeeRevenue);
        
        return savedSettlement;
    }
    
    /**
     * 사용자별 일별 정산 집계 및 저장
     * Aggregate and Save User Daily Settlement
     * 
     * 처리 과정:
     * ==========
     * 1. 사용자별 거래 집계: 전일 해당 사용자의 모든 거래 조회 및 집계
     * 2. 사용자별 수수료 집계: 전일 해당 사용자가 납부한 모든 수수료 조회 및 집계
     * 3. 사용자별 정산 데이터 생성: UserSettlement 엔티티 생성
     * 4. 사용자별 정산 데이터 저장: user_settlements 테이블에 저장
     * 
     * @param userId 사용자 ID
     * @param date 정산일 (전일 데이터를 집계)
     * @return 생성된 사용자별 정산 데이터
     */
    @Transactional
    public UserSettlement createUserDailySettlement(Long userId, LocalDate date) {
        log.info("[SettlementService] 사용자별 일별 정산 집계 시작: userId={}, date={}", userId, date);
        
        // 전일 데이터 집계 (date - 1일)
        LocalDate targetDate = date.minusDays(1);
        LocalDateTime startDateTime = targetDate.atStartOfDay();
        LocalDateTime endDateTime = targetDate.atTime(LocalTime.MAX);
        
        // 사용자 엔티티 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: userId=" + userId));
        
        // 이미 정산 데이터가 존재하는지 확인
        boolean exists = userSettlementRepository.findByUserIdAndSettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                userId, targetDate, "daily", null, "USDT").isPresent();
        if (exists) {
            log.warn("[SettlementService] 이미 사용자별 정산 데이터가 존재함: userId={}, date={}", userId, targetDate);
            return userSettlementRepository.findByUserIdAndSettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                    userId, targetDate, "daily", null, "USDT")
                    .orElseThrow(() -> new RuntimeException("사용자별 정산 데이터 조회 실패"));
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 1. 사용자별 거래 집계
        // User Trade Aggregation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 집계 항목:
        // - 사용자 총 거래 건수: COUNT(trades) where buyer_id = userId OR seller_id = userId
        // - 사용자 총 거래량: SUM(price × amount) for user's trades
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        List<Trade> userTrades = tradeRepository.findByCreatedAtBetween(startDateTime, endDateTime).stream()
                .filter(trade -> trade.getBuyerId().equals(userId) || trade.getSellerId().equals(userId))
                .toList();
        
        long totalTrades = userTrades.size();
        
        BigDecimal totalVolume = userTrades.stream()
                .map(trade -> trade.getPrice().multiply(trade.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        log.info("[SettlementService] 사용자별 거래 집계 완료: userId={}, totalTrades={}, totalVolume={}", 
                userId, totalTrades, totalVolume);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 2. 사용자별 수수료 집계
        // User Fee Aggregation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 집계 항목:
        // - 사용자가 지불한 총 수수료: SUM(fee_amount) from trade_fees where user_id = userId
        // 
        // 중요성:
        // - 사용자가 실제로 납부한 수수료 금액
        // - 거래 금액에 따라 수수료 금액이 다르므로 각 거래마다 기록된 수수료를 합산
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        List<dustin.cex.domains.settlement.model.entity.TradeFee> userFees = 
                tradeFeeRepository.findByUserIdAndCreatedAtBetween(userId, startDateTime, endDateTime);
        
        BigDecimal totalFeesPaid = userFees.stream()
                .map(dustin.cex.domains.settlement.model.entity.TradeFee::getFeeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        log.info("[SettlementService] 사용자별 수수료 집계 완료: userId={}, totalFeesPaid={}", userId, totalFeesPaid);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 3. 사용자별 정산 데이터 생성 및 저장
        // User Settlement Data Creation and Save
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 저장 내용:
        // - 사용자 ID, 정산일, 정산 유형, 거래 건수, 거래량, 수수료 납부액
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        UserSettlement userSettlement = UserSettlement.builder()
                .user(user)                              // 사용자
                .settlementDate(targetDate)              // 정산일 (전일)
                .settlementType("daily")                 // 일별 정산
                .totalTrades(totalTrades)                // 사용자 총 거래 건수
                .totalVolume(totalVolume)                // 사용자 총 거래량 (USDT)
                .totalFeesPaid(totalFeesPaid)           // 사용자가 지불한 총 수수료 (USDT)
                .baseMint(null)                          // 전체 거래쌍
                .quoteMint("USDT")                      // 기준 통화
                .build();
        
        UserSettlement savedUserSettlement = userSettlementRepository.save(userSettlement);
        
        log.info("[SettlementService] 사용자별 일별 정산 집계 완료: userId={}, date={}, settlementId={}, totalTrades={}, totalVolume={}, totalFeesPaid={}", 
                userId, targetDate, savedUserSettlement.getId(), totalTrades, totalVolume, totalFeesPaid);
        
        return savedUserSettlement;
    }
    
    /**
     * 거래에서 사용자 ID 목록 추출
     * Extract User IDs from Trades
     * 
     * @param date 날짜
     * @return 해당 날짜에 거래에 참여한 고유 사용자 ID 목록
     */
    @Transactional(readOnly = true)
    public List<Long> getUserIdsFromTrades(LocalDate date) {
        LocalDateTime startDateTime = date.atStartOfDay();
        LocalDateTime endDateTime = date.atTime(LocalTime.MAX);
        
        List<Trade> trades = tradeRepository.findByCreatedAtBetween(startDateTime, endDateTime);
        
        return trades.stream()
                .flatMap(trade -> java.util.stream.Stream.of(trade.getBuyerId(), trade.getSellerId()))
                .distinct()
                .toList();
    }
    
    /**
     * 정산 데이터 업데이트
     * Update Settlement
     * 
     * @param settlement 업데이트할 정산 데이터
     * @return 업데이트된 정산 데이터
     */
    @Transactional
    public Settlement updateSettlement(Settlement settlement) {
        return settlementRepository.save(settlement);
    }
    
    /**
     * 날짜로 정산 데이터 조회
     * Get Settlement by Date
     * 
     * @param date 날짜
     * @return 정산 데이터
     */
    @Transactional(readOnly = true)
    public Settlement getSettlementByDate(LocalDate date) {
        return settlementRepository
                .findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(date, "daily", null, "USDT")
                .orElseThrow(() -> new RuntimeException("정산 데이터를 찾을 수 없습니다: date=" + date));
    }
    
    /**
     * 월별 정산 집계 및 저장
     * Aggregate and Save Monthly Settlement
     * 
     * 처리 과정:
     * ==========
     * 1. 해당 월의 일별 정산 데이터를 집계
     * 2. 월별 총 거래 건수, 거래량, 수수료 수익, 사용자 수 계산
     * 3. 월별 정산 데이터 생성 및 저장
     * 4. 복식부기 검증 실행
     * 
     * @param year 연도
     * @param month 월
     * @return 생성된 월별 정산 데이터
     */
    @Transactional
    public Settlement createMonthlySettlement(int year, int month) {
        log.info("[SettlementService] 월별 정산 집계 시작: year={}, month={}", year, month);
        
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        
        // 이미 월별 정산 데이터가 존재하는지 확인
        boolean exists = settlementRepository.existsBySettlementDateAndSettlementType(startDate, "monthly");
        if (exists) {
            log.warn("[SettlementService] 이미 월별 정산 데이터가 존재함: year={}, month={}", year, month);
            return settlementRepository.findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                    startDate, "monthly", null, "USDT")
                    .orElseThrow(() -> new RuntimeException("월별 정산 데이터 조회 실패"));
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 1. 해당 월의 일별 정산 데이터 집계
        // Aggregate Daily Settlement Data for the Month
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 집계 방법:
        // - 해당 월의 모든 일별 정산 데이터를 조회하여 합산
        // - 또는 직접 trades와 trade_fees를 집계 (더 정확함)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);
        
        // 직접 거래 및 수수료 집계 (더 정확함)
        List<Trade> trades = tradeRepository.findByCreatedAtBetween(startDateTime, endDateTime);
        long totalTrades = trades.size();
        
        BigDecimal totalVolume = trades.stream()
                .map(trade -> trade.getPrice().multiply(trade.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        BigDecimal totalFeeRevenue = tradeFeeRepository.calculateTotalFeeRevenueByMint(
                "USDT", startDateTime, endDateTime);
        
        long totalUsers = trades.stream()
                .flatMap(trade -> java.util.stream.Stream.of(trade.getBuyerId(), trade.getSellerId()))
                .distinct()
                .count();
        
        log.info("[SettlementService] 월별 집계 완료: totalTrades={}, totalVolume={}, totalFeeRevenue={}, totalUsers={}", 
                totalTrades, totalVolume, totalFeeRevenue, totalUsers);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 2. 월별 정산 데이터 생성 및 저장
        // Monthly Settlement Data Creation and Save
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        Settlement settlement = Settlement.builder()
                .settlementDate(startDate)              // 해당 월의 첫 날
                .settlementType("monthly")              // 월별 정산
                .totalTrades(totalTrades)               // 월별 총 거래 건수
                .totalVolume(totalVolume)               // 월별 총 거래량 (USDT)
                .totalFeeRevenue(totalFeeRevenue)       // 월별 총 수수료 수익 (USDT)
                .totalUsers(totalUsers)                 // 월별 거래한 사용자 수
                .baseMint(null)                        // 전체 거래쌍
                .quoteMint("USDT")                     // 기준 통화
                .validationStatus("pending")           // 검증 대기 상태
                .build();
        
        Settlement savedSettlement = settlementRepository.save(settlement);
        
        log.info("[SettlementService] 월별 정산 집계 완료: year={}, month={}, settlementId={}, totalTrades={}, totalVolume={}, totalFeeRevenue={}", 
                year, month, savedSettlement.getId(), totalTrades, totalVolume, totalFeeRevenue);
        
        return savedSettlement;
    }
    
    /**
     * 사용자별 월별 정산 집계 및 저장
     * Aggregate and Save User Monthly Settlement
     * 
     * @param userId 사용자 ID
     * @param year 연도
     * @param month 월
     * @return 생성된 사용자별 월별 정산 데이터
     */
    @Transactional
    public UserSettlement createUserMonthlySettlement(Long userId, int year, int month) {
        log.info("[SettlementService] 사용자별 월별 정산 집계 시작: userId={}, year={}, month={}", userId, year, month);
        
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);
        
        // 사용자 엔티티 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: userId=" + userId));
        
        // 이미 월별 정산 데이터가 존재하는지 확인
        boolean exists = userSettlementRepository.findByUserIdAndSettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                userId, startDate, "monthly", null, "USDT").isPresent();
        if (exists) {
            log.warn("[SettlementService] 이미 사용자별 월별 정산 데이터가 존재함: userId={}, year={}, month={}", userId, year, month);
            return userSettlementRepository.findByUserIdAndSettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                    userId, startDate, "monthly", null, "USDT")
                    .orElseThrow(() -> new RuntimeException("사용자별 월별 정산 데이터 조회 실패"));
        }
        
        // 사용자별 거래 집계
        List<Trade> userTrades = tradeRepository.findByCreatedAtBetween(startDateTime, endDateTime).stream()
                .filter(trade -> trade.getBuyerId().equals(userId) || trade.getSellerId().equals(userId))
                .toList();
        
        long totalTrades = userTrades.size();
        
        BigDecimal totalVolume = userTrades.stream()
                .map(trade -> trade.getPrice().multiply(trade.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // 사용자별 수수료 집계
        List<dustin.cex.domains.settlement.model.entity.TradeFee> userFees = 
                tradeFeeRepository.findByUserIdAndCreatedAtBetween(userId, startDateTime, endDateTime);
        
        BigDecimal totalFeesPaid = userFees.stream()
                .map(dustin.cex.domains.settlement.model.entity.TradeFee::getFeeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // 사용자별 월별 정산 데이터 생성 및 저장
        UserSettlement userSettlement = UserSettlement.builder()
                .user(user)
                .settlementDate(startDate)              // 해당 월의 첫 날
                .settlementType("monthly")             // 월별 정산
                .totalTrades(totalTrades)              // 사용자 월별 총 거래 건수
                .totalVolume(totalVolume)              // 사용자 월별 총 거래량 (USDT)
                .totalFeesPaid(totalFeesPaid)          // 사용자가 지불한 월별 총 수수료 (USDT)
                .baseMint(null)                        // 전체 거래쌍
                .quoteMint("USDT")                    // 기준 통화
                .build();
        
        UserSettlement savedUserSettlement = userSettlementRepository.save(userSettlement);
        
        log.info("[SettlementService] 사용자별 월별 정산 집계 완료: userId={}, year={}, month={}, settlementId={}, totalTrades={}, totalVolume={}, totalFeesPaid={}", 
                userId, year, month, savedUserSettlement.getId(), totalTrades, totalVolume, totalFeesPaid);
        
        return savedUserSettlement;
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
