package dustin.cex.domains.settlement.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.settlement.model.entity.Settlement;
import dustin.cex.domains.settlement.model.entity.UserSettlement;
import dustin.cex.domains.settlement.repository.SettlementRepository;
import dustin.cex.domains.settlement.repository.UserSettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 정산 리포트 서비스
 * Settlement Report Service
 * 
 * 역할:
 * - 일별/월별 정산 리포트 생성
 * - 사용자별 정산 리포트 생성
 * - 수수료 수익 리포트 생성
 * 
 * 리포트의 목적:
 * ==============
 * 1. 거래소 운영자 리포트:
 *    - 일별/월별 총 거래량, 수수료 수익 파악
 *    - 거래쌍별 성과 분석
 *    - 검증 상태 확인
 * 
 * 2. 사용자 리포트:
 *    - 사용자가 얼마나 거래했는지, 얼마나 수수료를 납부했는지 파악
 *    - 세금 신고용 데이터 제공
 * 
 * 3. 비즈니스 분석:
 *    - 거래량 추이 분석
 *    - 수수료 수익 추이 분석
 *    - 사용자 활동 분석
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementReportService {
    
    private final SettlementRepository settlementRepository;
    private final UserSettlementRepository userSettlementRepository;
    private final SettlementService settlementService;
    
    /**
     * 일별 정산 리포트 생성
     * Generate Daily Settlement Report
     * 
     * 리포트 내용:
     * ===========
     * - 정산일
     * - 총 거래 건수
     * - 총 거래량 (USDT)
     * - 총 수수료 수익 (USDT)
     * - 거래한 사용자 수
     * - 검증 상태
     * - 검증 에러 (있는 경우)
     * 
     * @param date 날짜
     * @return 일별 정산 리포트 데이터
     */
    @Transactional(readOnly = true)
    public Map<String, Object> generateDailyReport(LocalDate date) {
        log.info("[SettlementReportService] 일별 정산 리포트 생성 시작: date={}", date);
        
        Settlement settlement = settlementRepository
                .findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(date, "daily", null, "USDT")
                .orElseThrow(() -> new RuntimeException("일별 정산 데이터를 찾을 수 없습니다: date=" + date));
        
        Map<String, Object> report = new HashMap<>();
        report.put("date", settlement.getSettlementDate());
        report.put("settlementType", settlement.getSettlementType());
        report.put("totalTrades", settlement.getTotalTrades());
        report.put("totalVolume", settlement.getTotalVolume());
        report.put("totalFeeRevenue", settlement.getTotalFeeRevenue());
        report.put("totalUsers", settlement.getTotalUsers());
        report.put("validationStatus", settlement.getValidationStatus());
        report.put("validationError", settlement.getValidationError());
        report.put("createdAt", settlement.getCreatedAt());
        report.put("validatedAt", settlement.getValidatedAt());
        
        log.info("[SettlementReportService] 일별 정산 리포트 생성 완료: date={}, totalTrades={}, totalFeeRevenue={}", 
                date, settlement.getTotalTrades(), settlement.getTotalFeeRevenue());
        
        return report;
    }
    
    /**
     * 월별 정산 리포트 생성
     * Generate Monthly Settlement Report
     * 
     * 리포트 내용:
     * ===========
     * - 정산 월 (연도/월)
     * - 월별 총 거래 건수
     * - 월별 총 거래량 (USDT)
     * - 월별 총 수수료 수익 (USDT)
     * - 월별 거래한 사용자 수
     * - 검증 상태
     * - 일별 상세 내역 (선택적)
     * 
     * @param year 연도
     * @param month 월
     * @param includeDailyDetails 일별 상세 내역 포함 여부
     * @return 월별 정산 리포트 데이터
     */
    @Transactional(readOnly = true)
    public Map<String, Object> generateMonthlyReport(int year, int month, boolean includeDailyDetails) {
        log.info("[SettlementReportService] 월별 정산 리포트 생성 시작: year={}, month={}", year, month);
        
        LocalDate startDate = LocalDate.of(year, month, 1);
        
        Settlement monthlySettlement = settlementRepository
                .findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(startDate, "monthly", null, "USDT")
                .orElseThrow(() -> new RuntimeException("월별 정산 데이터를 찾을 수 없습니다: year=" + year + ", month=" + month));
        
        Map<String, Object> report = new HashMap<>();
        report.put("year", year);
        report.put("month", month);
        report.put("settlementDate", monthlySettlement.getSettlementDate());
        report.put("settlementType", monthlySettlement.getSettlementType());
        report.put("totalTrades", monthlySettlement.getTotalTrades());
        report.put("totalVolume", monthlySettlement.getTotalVolume());
        report.put("totalFeeRevenue", monthlySettlement.getTotalFeeRevenue());
        report.put("totalUsers", monthlySettlement.getTotalUsers());
        report.put("validationStatus", monthlySettlement.getValidationStatus());
        report.put("validationError", monthlySettlement.getValidationError());
        report.put("createdAt", monthlySettlement.getCreatedAt());
        report.put("validatedAt", monthlySettlement.getValidatedAt());
        
        // 일별 상세 내역 포함 여부
        if (includeDailyDetails) {
            LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
            List<Settlement> dailySettlements = settlementRepository
                    .findBySettlementDateBetweenAndSettlementType(startDate, endDate, "daily");
            
            List<Map<String, Object>> dailyDetails = dailySettlements.stream()
                    .map(daily -> {
                        Map<String, Object> dailyMap = new HashMap<>();
                        dailyMap.put("date", daily.getSettlementDate());
                        dailyMap.put("totalTrades", daily.getTotalTrades());
                        dailyMap.put("totalVolume", daily.getTotalVolume());
                        dailyMap.put("totalFeeRevenue", daily.getTotalFeeRevenue());
                        dailyMap.put("totalUsers", daily.getTotalUsers());
                        dailyMap.put("validationStatus", daily.getValidationStatus());
                        return dailyMap;
                    })
                    .collect(Collectors.toList());
            
            report.put("dailyDetails", dailyDetails);
        }
        
        log.info("[SettlementReportService] 월별 정산 리포트 생성 완료: year={}, month={}, totalTrades={}, totalFeeRevenue={}", 
                year, month, monthlySettlement.getTotalTrades(), monthlySettlement.getTotalFeeRevenue());
        
        return report;
    }
    
    /**
     * 사용자별 일별 정산 리포트 생성
     * Generate User Daily Settlement Report
     * 
     * 리포트 내용:
     * ===========
     * - 사용자 ID
     * - 정산일
     * - 사용자 총 거래 건수
     * - 사용자 총 거래량 (USDT)
     * - 사용자가 지불한 총 수수료 (USDT)
     * 
     * @param userId 사용자 ID
     * @param date 날짜
     * @return 사용자별 일별 정산 리포트 데이터
     */
    @Transactional(readOnly = true)
    public Map<String, Object> generateUserDailyReport(Long userId, LocalDate date) {
        log.info("[SettlementReportService] 사용자별 일별 정산 리포트 생성 시작: userId={}, date={}", userId, date);
        
        UserSettlement userSettlement = userSettlementRepository
                .findByUserIdAndSettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
                        userId, date, "daily", null, "USDT")
                .orElseThrow(() -> new RuntimeException("사용자별 일별 정산 데이터를 찾을 수 없습니다: userId=" + userId + ", date=" + date));
        
        Map<String, Object> report = new HashMap<>();
        report.put("userId", userId);
        report.put("date", userSettlement.getSettlementDate());
        report.put("settlementType", userSettlement.getSettlementType());
        report.put("totalTrades", userSettlement.getTotalTrades());
        report.put("totalVolume", userSettlement.getTotalVolume());
        report.put("totalFeesPaid", userSettlement.getTotalFeesPaid());
        report.put("createdAt", userSettlement.getCreatedAt());
        
        log.info("[SettlementReportService] 사용자별 일별 정산 리포트 생성 완료: userId={}, date={}, totalTrades={}, totalFeesPaid={}", 
                userId, date, userSettlement.getTotalTrades(), userSettlement.getTotalFeesPaid());
        
        return report;
    }
    
    /**
     * 일별 수수료 수익 조회
     * Get Daily Fee Revenue
     * 
     * 정산 데이터가 있으면 settlements 테이블에서 조회하고,
     * 없으면 trade_fees 테이블에서 직접 계산합니다.
     * 
     * @param date 날짜
     * @return 일별 총 수수료 수익 (USDT)
     */
    @Transactional(readOnly = true)
    public BigDecimal getDailyFeeRevenue(LocalDate date) {
        // 먼저 정산 데이터가 있는지 확인
        Settlement settlement = settlementRepository
                .findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(date, "daily", null, "USDT")
                .orElse(null);
        
        if (settlement != null) {
            return settlement.getTotalFeeRevenue();
        }
        
        // 정산 데이터가 없으면 trade_fees 테이블에서 직접 계산
        LocalDateTime startDateTime = date.atStartOfDay();
        LocalDateTime endDateTime = date.atTime(LocalTime.MAX);
        
        BigDecimal totalRevenue = settlementService.calculateDailyFeeRevenue(date);
        
        log.info("[SettlementReportService] 일별 수수료 수익 조회 (직접 계산): date={}, revenue={}", date, totalRevenue);
        return totalRevenue;
    }
    
    /**
     * 월별 수수료 수익 조회
     * Get Monthly Fee Revenue
     * 
     * 정산 데이터가 있으면 settlements 테이블에서 조회하고,
     * 없으면 trade_fees 테이블에서 직접 계산합니다.
     * 
     * @param year 연도
     * @param month 월
     * @return 월별 총 수수료 수익 (USDT)
     */
    @Transactional(readOnly = true)
    public BigDecimal getMonthlyFeeRevenue(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        
        // 먼저 정산 데이터가 있는지 확인
        Settlement settlement = settlementRepository
                .findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(startDate, "monthly", null, "USDT")
                .orElse(null);
        
        if (settlement != null) {
            return settlement.getTotalFeeRevenue();
        }
        
        // 정산 데이터가 없으면 trade_fees 테이블에서 직접 계산
        BigDecimal totalRevenue = settlementService.calculateMonthlyFeeRevenue(year, month);
        
        log.info("[SettlementReportService] 월별 수수료 수익 조회 (직접 계산): year={}, month={}, revenue={}", 
                year, month, totalRevenue);
        return totalRevenue;
    }
    
    /**
     * 거래쌍별 수수료 수익 조회
     * Get Fee Revenue by Trading Pair
     * 
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @return 거래쌍별 수수료 수익 맵 (baseMint -> totalFeeRevenue)
     */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> getFeeRevenueByPair(LocalDate startDate, LocalDate endDate) {
        log.info("[SettlementReportService] 거래쌍별 수수료 수익 조회 시작: startDate={}, endDate={}", startDate, endDate);
        
        List<Settlement> settlements = settlementRepository
                .findBySettlementDateBetweenAndSettlementType(startDate, endDate, "daily");
        
        Map<String, BigDecimal> revenueByPair = new HashMap<>();
        
        for (Settlement settlement : settlements) {
            String baseMint = settlement.getBaseMint() != null ? settlement.getBaseMint() : "ALL";
            BigDecimal currentRevenue = revenueByPair.getOrDefault(baseMint, BigDecimal.ZERO);
            revenueByPair.put(baseMint, currentRevenue.add(settlement.getTotalFeeRevenue()));
        }
        
        log.info("[SettlementReportService] 거래쌍별 수수료 수익 조회 완료: pairCount={}", revenueByPair.size());
        
        return revenueByPair;
    }
}
