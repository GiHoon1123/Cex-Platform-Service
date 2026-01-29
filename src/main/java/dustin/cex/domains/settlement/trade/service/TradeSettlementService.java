package dustin.cex.domains.settlement.trade.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.settlement.trade.model.entity.TradeFee;
import dustin.cex.domains.settlement.trade.model.entity.TradeSettlement;
import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementAdjustment;
import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementAuditLog;
import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementItem;
import dustin.cex.domains.settlement.trade.model.entity.TradeUserSettlement;
import dustin.cex.domains.settlement.trade.repository.TradeFeeRepository;
import dustin.cex.domains.settlement.trade.repository.TradeSettlementAdjustmentRepository;
import dustin.cex.domains.settlement.trade.repository.TradeSettlementAuditLogRepository;
import dustin.cex.domains.settlement.trade.repository.TradeSettlementItemRepository;
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
    private final TradeSettlementItemRepository tradeSettlementItemRepository;
    private final TradeSettlementAdjustmentRepository tradeSettlementAdjustmentRepository;
    private final TradeSettlementAuditLogRepository tradeSettlementAuditLogRepository;
    private final TradeUserSettlementRepository tradeUserSettlementRepository;
    private final UserRepository userRepository;
    
    /**
     * 정산 코드 버전 (application.properties에서 주입)
     * 기본값: "unknown" (버전 정보가 없는 경우)
     */
    @Value("${settlement.code.version:unknown}")
    private String defaultCodeVersion;
    
    /**
     * 정산 정책 버전 (application.properties에서 주입)
     * 기본값: "default" (정책 버전이 없는 경우)
     */
    @Value("${settlement.policy.version:default}")
    private String defaultPolicyVersion;
    
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
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 정산 기준 시점 명확화
        // Settlement Time Window Definition
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 
        // 비즈니스 기준 시간대: KST (Asia/Seoul)
        // 정산일 정의: KST 기준 00:00:00 ~ 23:59:59.999999999
        // 
        // 중요:
        // - Trade.createdAt은 UTC로 저장됨 (parseTimestamp에서 UTC → KST 변환)
        // - 정산 시에는 KST 기준으로 하루를 정의
        // - 예: 2026-01-29 정산 = KST 2026-01-29 00:00:00 ~ 23:59:59
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        ZoneId businessTimeZone = ZoneId.of("Asia/Seoul");
        LocalDate targetDate = settlementDate;
        
        // KST 기준 하루의 시작과 끝
        ZonedDateTime startZonedDateTime = targetDate.atStartOfDay().atZone(businessTimeZone);
        ZonedDateTime endZonedDateTime = targetDate.atTime(LocalTime.MAX).atZone(businessTimeZone);
        
        // DB 조회를 위한 LocalDateTime (DB의 createdAt은 이미 KST로 저장됨)
        LocalDateTime startDateTime = startZonedDateTime.toLocalDateTime();
        LocalDateTime endDateTime = endZonedDateTime.toLocalDateTime();
        
        log.info("[TradeSettlementService] 정산 시간 범위: {} ~ {} (KST)", startDateTime, endDateTime);
        
        // 이미 정산 데이터가 존재하는지 확인 (테스트를 위해 기존 데이터 삭제 후 재생성)
        boolean exists = tradeSettlementRepository.existsBySettlementDateAndSettlementType(targetDate, "daily");
        if (exists) {
            log.warn("[TradeSettlementService] 이미 정산 데이터가 존재함. 기존 데이터 삭제 후 재생성: date={}", targetDate);
            // 기존 정산 데이터와 관련 데이터 삭제
            java.util.Optional<TradeSettlement> existingSettlement = tradeSettlementRepository.findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                    targetDate, "daily", null, "USDT");
            if (existingSettlement.isPresent()) {
                TradeSettlement settlement = existingSettlement.get();
                Long settlementId = settlement.getId();
                
                // 관련 아이템 삭제
                tradeSettlementItemRepository.findBySettlementId(settlementId).forEach(item -> 
                    tradeSettlementItemRepository.delete(item));
                // 관련 감사 로그 삭제
                tradeSettlementAuditLogRepository.findBySettlementIdOrderByCreatedAtDesc(settlementId).forEach(auditLog -> 
                    tradeSettlementAuditLogRepository.delete(auditLog));
                // 관련 보정 삭제
                tradeSettlementAdjustmentRepository.findBySettlementId(settlementId).forEach(adj -> 
                    tradeSettlementAdjustmentRepository.delete(adj));
                // 정산 데이터 삭제
                tradeSettlementRepository.delete(settlement);
                log.info("[TradeSettlementService] 기존 정산 데이터 삭제 완료: date={}, settlementId={}", targetDate, settlementId);
            }
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
                .settlementDate(targetDate)           // 정산일
                .settlementType("daily")               // 일별 정산
                .totalTrades(totalTrades)              // 총 거래 건수
                .totalVolume(totalVolume)               // 총 거래량 (USDT)
                .totalFeeRevenue(totalFeeRevenue)      // 총 수수료 수익 (USDT)
                .totalUsers(totalUsers)                 // 거래한 사용자 수
                .baseMint(null)                        // 전체 거래쌍
                .quoteMint("USDT")                     // 기준 통화
                .validationStatus("CALCULATING")       // 계산 중 상태
                .codeVersion(defaultCodeVersion)        // 정산 코드 버전
                .policyVersion(defaultPolicyVersion)    // 정산 정책 버전
                .build();
        
        TradeSettlement savedSettlement = tradeSettlementRepository.save(settlement);
        
        // 감사 로그 기록
        logAuditAction(savedSettlement, "CREATE", "SYSTEM", null, null, null, targetDate);
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 5. 거래 단위 정산 레코드 생성 (재현성 및 감사 추적)
        // Trade-Level Settlement Item Creation
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // 성능 최적화: 모든 수수료를 한 번에 조회하고 메모리에서 그룹핑
        List<TradeFee> allTradeFees = tradeFeeRepository.findByCreatedAtBetween(startDateTime, endDateTime);
        Map<Long, List<TradeFee>> feesByTradeId = allTradeFees.stream()
                .collect(Collectors.groupingBy(fee -> fee.getTrade().getId()));
        
        log.info("[TradeSettlementService] 수수료 조회 완료: totalFees={}, uniqueTrades={}", 
                allTradeFees.size(), feesByTradeId.size());
        
        // 배치 삽입을 위한 리스트 준비
        List<TradeSettlementItem> items = new ArrayList<>();
        int itemCount = 0;
        
        for (Trade trade : trades) {
            try {
                // 메모리에서 거래별 수수료 조회 (N+1 쿼리 문제 해결)
                List<TradeFee> tradeFees = feesByTradeId.getOrDefault(trade.getId(), new ArrayList<>());
                
                BigDecimal buyerFee = tradeFees.stream()
                        .filter(fee -> "buyer".equals(fee.getFeeType()))
                        .map(TradeFee::getFeeAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                BigDecimal sellerFee = tradeFees.stream()
                        .filter(fee -> "seller".equals(fee.getFeeType()))
                        .map(TradeFee::getFeeAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                BigDecimal totalFee = buyerFee.add(sellerFee);
                BigDecimal tradeVolume = trade.getPrice().multiply(trade.getAmount());
                
                // 거래 단위 정산 레코드 생성 (배치 삽입을 위해 리스트에 추가)
                TradeSettlementItem item = TradeSettlementItem.builder()
                        .settlement(savedSettlement)
                        .tradeId(trade.getId())
                        .tradeVolume(tradeVolume)
                        .buyerFee(buyerFee)
                        .sellerFee(sellerFee)
                        .totalFee(totalFee)
                        .settlementDate(targetDate)
                        .build();
                
                items.add(item);
                itemCount++;
            } catch (Exception e) {
                log.error("[TradeSettlementService] 거래 단위 정산 레코드 생성 실패: tradeId={}", trade.getId(), e);
                // 개별 거래 실패는 전체 정산을 중단하지 않음
            }
        }
        
        // 배치 삽입으로 성능 최적화
        if (!items.isEmpty()) {
            tradeSettlementItemRepository.saveAll(items);
            log.info("[TradeSettlementService] 거래 단위 정산 레코드 배치 삽입 완료: itemCount={}", itemCount);
        }
        
        // 계산 완료 상태로 업데이트
        savedSettlement.setValidationStatus("CALCULATED");
        TradeSettlement finalSettlement = tradeSettlementRepository.save(savedSettlement);
        
        log.info("[TradeSettlementService] 일별 거래 정산 집계 완료: date={}, settlementId={}, totalTrades={}, totalVolume={}, totalFeeRevenue={}, itemCount={}", 
                targetDate, finalSettlement.getId(), totalTrades, totalVolume, totalFeeRevenue, itemCount);
        
        return finalSettlement;
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
        
        // 정산 기준 시점 명확화 (KST 기준)
        ZoneId businessTimeZone = ZoneId.of("Asia/Seoul");
        LocalDate targetDate = settlementDate;
        ZonedDateTime startZonedDateTime = targetDate.atStartOfDay().atZone(businessTimeZone);
        ZonedDateTime endZonedDateTime = targetDate.atTime(LocalTime.MAX).atZone(businessTimeZone);
        
        LocalDateTime startDateTime = startZonedDateTime.toLocalDateTime();
        LocalDateTime endDateTime = endZonedDateTime.toLocalDateTime();
        
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
                .validationStatus("CALCULATING")        // 계산 중 상태
                .codeVersion(defaultCodeVersion)         // 정산 코드 버전
                .policyVersion(defaultPolicyVersion)     // 정산 정책 버전
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
        // 정산 기준 시점 명확화 (KST 기준)
        ZoneId businessTimeZone = ZoneId.of("Asia/Seoul");
        ZonedDateTime startZonedDateTime = date.atStartOfDay().atZone(businessTimeZone);
        ZonedDateTime endZonedDateTime = date.atTime(LocalTime.MAX).atZone(businessTimeZone);
        
        LocalDateTime startDateTime = startZonedDateTime.toLocalDateTime();
        LocalDateTime endDateTime = endZonedDateTime.toLocalDateTime();
        
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
     * @param validationStatus 검증 상태 ('VALIDATED' 또는 'FAILED')
     * @param validationError 검증 에러 메시지 (실패 시)
     */
    @Transactional
    public void updateValidationStatus(LocalDate date, String validationStatus, String validationError) {
        TradeSettlement settlement = getSettlementByDate(date);
        
        // 상태 머신 검증
        String currentStatus = settlement.getValidationStatus();
        if ("VALIDATED".equals(validationStatus) && !"CALCULATED".equals(currentStatus) && !"VALIDATING".equals(currentStatus)) {
            log.warn("[TradeSettlementService] 잘못된 상태 전환: {} → {}", currentStatus, validationStatus);
        }
        
        String beforeValue = String.format("{\"validationStatus\":\"%s\",\"validationError\":%s}", 
                currentStatus, settlement.getValidationError() != null ? "\"" + settlement.getValidationError() + "\"" : "null");
        
        settlement.setValidationStatus(validationStatus);
        settlement.setValidationError(validationError);
        tradeSettlementRepository.save(settlement);
        
        String afterValue = String.format("{\"validationStatus\":\"%s\",\"validationError\":%s}", 
                validationStatus, validationError != null ? "\"" + validationError + "\"" : "null");
        
        // 감사 로그 기록
        logAuditAction(settlement, "VALIDATE", "SYSTEM", 
                String.format("{\"before\":%s,\"after\":%s}", beforeValue, afterValue), 
                null, null, date);
        
        log.info("[TradeSettlementService] 정산 검증 상태 업데이트: date={}, status={}", date, validationStatus);
    }
    
    /**
     * 정산 보정 적용
     * Apply Settlement Adjustment
     * 
     * 원본 정산 데이터는 변경하지 않고, 보정 레코드만 생성합니다.
     * 실제 보정된 값은 조회 시 원본 + 보정 합계로 계산합니다.
     * 
     * @param date 정산일
     * @param adjustmentType 보정 유형 ('CORRECTION', 'REFUND', 'ADDITION')
     * @param reason 보정 사유
     * @param adjustedBy 보정 수행자
     * @param volumeAdjustment 거래량 보정 금액
     * @param feeAdjustment 수수료 보정 금액
     * @param tradesAdjustment 거래 건수 보정
     * @return 생성된 보정 레코드
     */
    @Transactional
    public TradeSettlementAdjustment applyAdjustment(
            LocalDate date,
            String adjustmentType,
            String reason,
            String adjustedBy,
            BigDecimal volumeAdjustment,
            BigDecimal feeAdjustment,
            Long tradesAdjustment) {
        
        TradeSettlement settlement = getSettlementByDate(date);
        
        // 보정 전 값 저장 (JSON 형식)
        String beforeValue = String.format(
                "{\"totalVolume\":%s,\"totalFeeRevenue\":%s,\"totalTrades\":%d}",
                settlement.getTotalVolume(),
                settlement.getTotalFeeRevenue(),
                settlement.getTotalTrades());
        
        // 보정 후 값 계산 (원본 + 보정)
        BigDecimal afterVolume = settlement.getTotalVolume().add(volumeAdjustment);
        BigDecimal afterFee = settlement.getTotalFeeRevenue().add(feeAdjustment);
        Long afterTrades = settlement.getTotalTrades() + tradesAdjustment;
        
        String afterValue = String.format(
                "{\"totalVolume\":%s,\"totalFeeRevenue\":%s,\"totalTrades\":%d}",
                afterVolume,
                afterFee,
                afterTrades);
        
        // 보정 레코드 생성
        TradeSettlementAdjustment adjustment = TradeSettlementAdjustment.builder()
                .settlement(settlement)
                .adjustmentType(adjustmentType)
                .reason(reason)
                .adjustedBy(adjustedBy)
                .volumeAdjustment(volumeAdjustment)
                .feeAdjustment(feeAdjustment)
                .tradesAdjustment(tradesAdjustment)
                .settlementDate(date)
                .beforeValue(beforeValue)
                .afterValue(afterValue)
                .build();
        
        TradeSettlementAdjustment savedAdjustment = tradeSettlementAdjustmentRepository.save(adjustment);
        
        // 정산 상태를 ADJUSTED로 변경
        settlement.setValidationStatus("ADJUSTED");
        tradeSettlementRepository.save(settlement);
        
        // 감사 로그 기록
        logAuditAction(settlement, "ADJUST", adjustedBy, 
                String.format("{\"before\":%s,\"after\":%s,\"reason\":\"%s\"}", beforeValue, afterValue, reason),
                null, null, date);
        
        log.info("[TradeSettlementService] 정산 보정 적용 완료: date={}, type={}, volumeAdjustment={}, feeAdjustment={}, tradesAdjustment={}",
                date, adjustmentType, volumeAdjustment, feeAdjustment, tradesAdjustment);
        
        return savedAdjustment;
    }
    
    /**
     * 감사 로그 기록 헬퍼 메서드
     * Log Audit Action Helper Method
     */
    private void logAuditAction(
            TradeSettlement settlement,
            String actionType,
            String actionBy,
            String actionDetails,
            String ipAddress,
            String userAgent,
            LocalDate settlementDate) {
        try {
            TradeSettlementAuditLog auditLog = TradeSettlementAuditLog.builder()
                    .settlement(settlement)
                    .actionType(actionType)
                    .actionBy(actionBy)
                    .actionDetails(actionDetails)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .settlementDate(settlementDate != null ? settlementDate : 
                            (settlement != null ? settlement.getSettlementDate() : null))
                    .build();
            
            tradeSettlementAuditLogRepository.save(auditLog);
        } catch (Exception e) {
            // 감사 로그 기록 실패는 정산 프로세스를 중단하지 않음
            log.error("[TradeSettlementService] 감사 로그 기록 실패: actionType={}, settlementId={}", 
                    actionType, settlement != null ? settlement.getId() : null, e);
        }
    }
    
    /**
     * 보정된 정산 값 조회 (원본 + 보정 합계)
     * Get Adjusted Settlement Value
     * 
     * @param date 정산일
     * @return 보정된 정산 값 (원본 + 모든 보정의 합계)
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> getAdjustedSettlement(LocalDate date) {
        TradeSettlement settlement = getSettlementByDate(date);
        List<TradeSettlementAdjustment> adjustments = tradeSettlementAdjustmentRepository.findBySettlementId(settlement.getId());
        
        BigDecimal adjustedVolume = settlement.getTotalVolume();
        BigDecimal adjustedFee = settlement.getTotalFeeRevenue();
        Long adjustedTrades = settlement.getTotalTrades();
        
        for (TradeSettlementAdjustment adj : adjustments) {
            adjustedVolume = adjustedVolume.add(adj.getVolumeAdjustment());
            adjustedFee = adjustedFee.add(adj.getFeeAdjustment());
            adjustedTrades = adjustedTrades + adj.getTradesAdjustment();
        }
        
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("originalVolume", settlement.getTotalVolume());
        result.put("originalFee", settlement.getTotalFeeRevenue());
        result.put("originalTrades", settlement.getTotalTrades());
        result.put("adjustedVolume", adjustedVolume);
        result.put("adjustedFee", adjustedFee);
        result.put("adjustedTrades", adjustedTrades);
        result.put("adjustmentCount", adjustments.size());
        
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
        // 정산 기준 시점 명확화 (KST 기준)
        ZoneId businessTimeZone = ZoneId.of("Asia/Seoul");
        ZonedDateTime startZonedDateTime = date.atStartOfDay().atZone(businessTimeZone);
        ZonedDateTime endZonedDateTime = date.atTime(LocalTime.MAX).atZone(businessTimeZone);
        
        LocalDateTime startDateTime = startZonedDateTime.toLocalDateTime();
        LocalDateTime endDateTime = endZonedDateTime.toLocalDateTime();
        
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
        // 정산 기준 시점 명확화 (KST 기준)
        ZoneId businessTimeZone = ZoneId.of("Asia/Seoul");
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        
        ZonedDateTime startZonedDateTime = startDate.atStartOfDay().atZone(businessTimeZone);
        ZonedDateTime endZonedDateTime = endDate.atTime(LocalTime.MAX).atZone(businessTimeZone);
        
        LocalDateTime startDateTime = startZonedDateTime.toLocalDateTime();
        LocalDateTime endDateTime = endZonedDateTime.toLocalDateTime();
        
        BigDecimal totalRevenue = tradeFeeRepository.calculateTotalFeeRevenueByMint(
                "USDT", startDateTime, endDateTime);
        
        log.info("[TradeSettlementService] 월별 수수료 수익 계산: year={}, month={}, revenue={}", 
                year, month, totalRevenue);
        return totalRevenue;
    }
}
