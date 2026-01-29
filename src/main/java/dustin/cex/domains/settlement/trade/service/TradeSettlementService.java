package dustin.cex.domains.settlement.trade.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.settlement.trade.model.entity.TradeFee;
import dustin.cex.domains.settlement.trade.model.entity.TradeSettlement;
import dustin.cex.domains.settlement.trade.model.entity.TradeUserSettlement;
import dustin.cex.domains.settlement.trade.repository.TradeFeeRepository;
import dustin.cex.domains.settlement.trade.repository.TradeSettlementRepository;
import dustin.cex.domains.settlement.trade.repository.TradeUserSettlementRepository;
import dustin.cex.domains.trade.model.entity.Trade;
import dustin.cex.domains.trade.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래 정산 서비스
 * Trade Settlement Service
 * 
 * 역할:
 * - 거래(Trade) 관련 일별/월별 정산 집계
 * - 거래 수수료 수익 집계
 * - 사용자별 거래 정산 집계
 * 
 * 하위 도메인 분리:
 * ================
 * 이 서비스는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산만을 담당하며, 향후 입출금/이벤트/쿠폰 정산은 별도 하위 도메인에서 처리됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeSettlementService {
    
    private final TradeRepository tradeRepository;
    private final TradeFeeRepository tradeFeeRepository;
    private final TradeSettlementRepository tradeSettlementRepository;
    private final TradeUserSettlementRepository tradeUserSettlementRepository;
    private final UserRepository userRepository;
    
    /**
     * 일별 거래 정산 집계 및 저장
     * Aggregate and Save Daily Trade Settlement
     * 
     * 처리 과정:
     * ==========
     * 1. 거래 집계: 해당 날짜의 모든 거래 조회 및 집계
     * 2. 수수료 집계: 해당 날짜의 모든 수수료 조회 및 집계
     * 3. 사용자 수 집계: 해당 날짜 거래에 참여한 고유 사용자 수 계산
     * 4. 정산 데이터 생성: TradeSettlement 엔티티 생성
     * 5. 정산 데이터 저장: trade_settlements 테이블에 저장
     * 
     * @param settlementDate 정산할 날짜 (예: 2026-01-28을 정산하려면 2026-01-28 전달)
     * @return 생성된 정산 데이터
     */
    @Transactional
    public TradeSettlement createDailySettlement(LocalDate settlementDate) {
        log.info("[TradeSettlementService] 일별 거래 정산 집계 시작: settlementDate={}", settlementDate);
        
        // 정산일 데이터 집계 (settlementDate 그대로 사용)
        LocalDate targetDate = settlementDate;
        LocalDateTime startDateTime = targetDate.atStartOfDay();
        LocalDateTime endDateTime = targetDate.atTime(LocalTime.MAX);
        
        // 이미 정산 데이터가 존재하는지 확인
        boolean exists = tradeSettlementRepository.existsBySettlementDateAndSettlementType(targetDate, "daily");
        if (exists) {
            log.warn("[TradeSettlementService] 이미 정산 데이터가 존재함: date={}", targetDate);
            return tradeSettlementRepository.findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                    targetDate, "daily", null, "USDT")
                    .orElseThrow(() -> new RuntimeException("정산 데이터 조회 실패"));
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 1. 거래 집계
        // Trade Aggregation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        List<Trade> trades = tradeRepository.findByCreatedAtBetween(startDateTime, endDateTime);
        long totalTrades = trades.size();
        
        BigDecimal totalVolume = trades.stream()
                .map(trade -> trade.getPrice().multiply(trade.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        log.info("[TradeSettlementService] 거래 집계 완료: totalTrades={}, totalVolume={}", totalTrades, totalVolume);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 2. 수수료 집계
        // Fee Revenue Aggregation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        BigDecimal totalFeeRevenue = tradeFeeRepository.calculateTotalFeeRevenueByMint(
                "USDT", startDateTime, endDateTime);
        
        log.info("[TradeSettlementService] 수수료 집계 완료: totalFeeRevenue={}", totalFeeRevenue);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 3. 사용자 수 집계
        // User Count Aggregation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        long totalUsers = trades.stream()
                .flatMap(trade -> java.util.stream.Stream.of(trade.getBuyerId(), trade.getSellerId()))
                .distinct()
                .count();
        
        log.info("[TradeSettlementService] 사용자 수 집계 완료: totalUsers={}", totalUsers);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 4. 정산 데이터 생성 및 저장
        // Settlement Data Creation and Save
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        TradeSettlement settlement = TradeSettlement.builder()
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
        
        TradeSettlement savedSettlement = tradeSettlementRepository.save(settlement);
        
        log.info("[TradeSettlementService] 일별 거래 정산 집계 완료: date={}, settlementId={}, totalTrades={}, totalVolume={}, totalFeeRevenue={}", 
                targetDate, savedSettlement.getId(), totalTrades, totalVolume, totalFeeRevenue);
        
        return savedSettlement;
    }
    
    /**
     * 사용자별 일별 거래 정산 집계 및 저장
     * Aggregate and Save User Daily Trade Settlement
     * 
     * @param userId 사용자 ID
     * @param settlementDate 정산할 날짜 (예: 2026-01-28을 정산하려면 2026-01-28 전달)
     * @return 생성된 사용자별 정산 데이터
     */
    @Transactional
    public TradeUserSettlement createUserDailySettlement(Long userId, LocalDate settlementDate) {
        log.info("[TradeSettlementService] 사용자별 일별 거래 정산 집계 시작: userId={}, settlementDate={}", userId, settlementDate);
        
        // 정산일 데이터 집계 (settlementDate 그대로 사용)
        LocalDate targetDate = settlementDate;
        LocalDateTime startDateTime = targetDate.atStartOfDay();
        LocalDateTime endDateTime = targetDate.atTime(LocalTime.MAX);
        
        // 사용자 엔티티 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: userId=" + userId));
        
        // 이미 정산 데이터가 존재하는지 확인
        boolean exists = tradeUserSettlementRepository.findByUserIdAndSettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                userId, targetDate, "daily", null, "USDT").isPresent();
        if (exists) {
            log.warn("[TradeSettlementService] 이미 사용자별 정산 데이터가 존재함: userId={}, date={}", userId, targetDate);
            return tradeUserSettlementRepository.findByUserIdAndSettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                    userId, targetDate, "daily", null, "USDT")
                    .orElseThrow(() -> new RuntimeException("사용자별 정산 데이터 조회 실패"));
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 1. 사용자별 거래 집계
        // User Trade Aggregation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        List<Trade> userTrades = tradeRepository.findByCreatedAtBetween(startDateTime, endDateTime).stream()
                .filter(trade -> trade.getBuyerId().equals(userId) || trade.getSellerId().equals(userId))
                .toList();
        
        long totalTrades = userTrades.size();
        
        BigDecimal totalVolume = userTrades.stream()
                .map(trade -> trade.getPrice().multiply(trade.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        log.info("[TradeSettlementService] 사용자별 거래 집계 완료: userId={}, totalTrades={}, totalVolume={}", 
                userId, totalTrades, totalVolume);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 2. 사용자별 수수료 집계
        // User Fee Aggregation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        List<TradeFee> userFees = 
                tradeFeeRepository.findByUserIdAndCreatedAtBetween(userId, startDateTime, endDateTime);
        
        BigDecimal totalFeesPaid = userFees.stream()
                .map(TradeFee::getFeeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        log.info("[TradeSettlementService] 사용자별 수수료 집계 완료: userId={}, totalFeesPaid={}", userId, totalFeesPaid);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 3. 사용자별 정산 데이터 생성 및 저장
        // User Settlement Data Creation and Save
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        TradeUserSettlement userSettlement = TradeUserSettlement.builder()
                .user(user)                              // 사용자
                .settlementDate(targetDate)              // 정산일 (전일)
                .settlementType("daily")                  // 일별 정산
                .totalTrades(totalTrades)                 // 사용자 총 거래 건수
                .totalVolume(totalVolume)                 // 사용자 총 거래량 (USDT)
                .totalFeesPaid(totalFeesPaid)            // 사용자가 지불한 총 수수료 (USDT)
                .baseMint(null)                          // 전체 거래쌍
                .quoteMint("USDT")                       // 기준 통화
                .build();
        
        TradeUserSettlement savedUserSettlement = tradeUserSettlementRepository.save(userSettlement);
        
        log.info("[TradeSettlementService] 사용자별 일별 거래 정산 집계 완료: userId={}, date={}, settlementId={}, totalTrades={}, totalVolume={}, totalFeesPaid={}", 
                userId, targetDate, savedUserSettlement.getId(), totalTrades, totalVolume, totalFeesPaid);
        
        return savedUserSettlement;
    }
    
    /**
     * 월별 거래 정산 집계 및 저장
     * Aggregate and Save Monthly Trade Settlement
     * 
     * @param year 연도
     * @param month 월
     * @return 생성된 월별 정산 데이터
     */
    @Transactional
    public TradeSettlement createMonthlySettlement(int year, int month) {
        log.info("[TradeSettlementService] 월별 거래 정산 집계 시작: year={}, month={}", year, month);
        
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        
        // 이미 월별 정산 데이터가 존재하는지 확인
        boolean exists = tradeSettlementRepository.existsBySettlementDateAndSettlementType(startDate, "monthly");
        if (exists) {
            log.warn("[TradeSettlementService] 이미 월별 정산 데이터가 존재함: year={}, month={}", year, month);
            return tradeSettlementRepository.findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                    startDate, "monthly", null, "USDT")
                    .orElseThrow(() -> new RuntimeException("월별 정산 데이터 조회 실패"));
        }
        
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
        
        log.info("[TradeSettlementService] 월별 집계 완료: totalTrades={}, totalVolume={}, totalFeeRevenue={}, totalUsers={}", 
                totalTrades, totalVolume, totalFeeRevenue, totalUsers);
        
        // 월별 정산 데이터 생성 및 저장
        TradeSettlement settlement = TradeSettlement.builder()
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
        
        TradeSettlement savedSettlement = tradeSettlementRepository.save(settlement);
        
        log.info("[TradeSettlementService] 월별 거래 정산 집계 완료: year={}, month={}, settlementId={}, totalTrades={}, totalVolume={}, totalFeeRevenue={}", 
                year, month, savedSettlement.getId(), totalTrades, totalVolume, totalFeeRevenue);
        
        return savedSettlement;
    }
    
    /**
     * 사용자별 월별 거래 정산 집계 및 저장
     * Aggregate and Save User Monthly Trade Settlement
     * 
     * @param userId 사용자 ID
     * @param year 연도
     * @param month 월
     * @return 생성된 사용자별 월별 정산 데이터
     */
    @Transactional
    public TradeUserSettlement createUserMonthlySettlement(Long userId, int year, int month) {
        log.info("[TradeSettlementService] 사용자별 월별 거래 정산 집계 시작: userId={}, year={}, month={}", userId, year, month);
        
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.atTime(LocalTime.MAX);
        
        // 사용자 엔티티 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: userId=" + userId));
        
        // 이미 월별 정산 데이터가 존재하는지 확인
        boolean exists = tradeUserSettlementRepository.findByUserIdAndSettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                userId, startDate, "monthly", null, "USDT").isPresent();
        if (exists) {
            log.warn("[TradeSettlementService] 이미 사용자별 월별 정산 데이터가 존재함: userId={}, year={}, month={}", userId, year, month);
            return tradeUserSettlementRepository.findByUserIdAndSettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
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
        List<TradeFee> userFees = 
                tradeFeeRepository.findByUserIdAndCreatedAtBetween(userId, startDateTime, endDateTime);
        
        BigDecimal totalFeesPaid = userFees.stream()
                .map(TradeFee::getFeeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // 사용자별 월별 정산 데이터 생성 및 저장
        TradeUserSettlement userSettlement = TradeUserSettlement.builder()
                .user(user)
                .settlementDate(startDate)              // 해당 월의 첫 날
                .settlementType("monthly")             // 월별 정산
                .totalTrades(totalTrades)              // 사용자 월별 총 거래 건수
                .totalVolume(totalVolume)              // 사용자 월별 총 거래량 (USDT)
                .totalFeesPaid(totalFeesPaid)          // 사용자가 지불한 월별 총 수수료 (USDT)
                .baseMint(null)                        // 전체 거래쌍
                .quoteMint("USDT")                    // 기준 통화
                .build();
        
        TradeUserSettlement savedUserSettlement = tradeUserSettlementRepository.save(userSettlement);
        
        log.info("[TradeSettlementService] 사용자별 월별 거래 정산 집계 완료: userId={}, year={}, month={}, settlementId={}, totalTrades={}, totalVolume={}, totalFeesPaid={}", 
                userId, year, month, savedUserSettlement.getId(), totalTrades, totalVolume, totalFeesPaid);
        
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
    public TradeSettlement updateSettlement(TradeSettlement settlement) {
        return tradeSettlementRepository.save(settlement);
    }
    
    /**
     * 날짜로 정산 데이터 조회
     * Get Settlement by Date
     * 
     * @param date 날짜
     * @return 정산 데이터
     */
    @Transactional(readOnly = true)
    public TradeSettlement getSettlementByDate(LocalDate date) {
        return tradeSettlementRepository
                .findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(date, "daily", null, "USDT")
                .orElseThrow(() -> new RuntimeException("정산 데이터를 찾을 수 없습니다: date=" + date));
    }
    
    /**
     * 정산 검증 결과 업데이트
     * Update Settlement Validation Status
     * 
     * @param date 정산일
     * @param validationStatus 검증 상태 ('validated' 또는 'failed')
     * @param validationError 검증 에러 메시지 (실패 시)
     */
    @Transactional
    public void updateValidationStatus(LocalDate date, String validationStatus, String validationError) {
        TradeSettlement settlement = getSettlementByDate(date);
        settlement.setValidationStatus(validationStatus);
        settlement.setValidationError(validationError);
        tradeSettlementRepository.save(settlement);
        log.info("[TradeSettlementService] 정산 검증 상태 업데이트: date={}, status={}", date, validationStatus);
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
        
        BigDecimal totalRevenue = tradeFeeRepository.calculateTotalFeeRevenueByMint(
                "USDT", startDateTime, endDateTime);
        
        log.info("[TradeSettlementService] 일별 수수료 수익 계산: date={}, revenue={}", date, totalRevenue);
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
        
        BigDecimal totalRevenue = tradeFeeRepository.calculateTotalFeeRevenueByMint(
                "USDT", startDateTime, endDateTime);
        
        log.info("[TradeSettlementService] 월별 수수료 수익 계산: year={}, month={}, revenue={}", 
                year, month, totalRevenue);
        return totalRevenue;
    }
}
