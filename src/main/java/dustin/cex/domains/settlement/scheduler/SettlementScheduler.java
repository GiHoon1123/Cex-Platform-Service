package dustin.cex.domains.settlement.scheduler;

import java.time.LocalDate;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dustin.cex.domains.settlement.service.SettlementService;
import dustin.cex.domains.settlement.service.SnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 정산 스케줄러
 * Settlement Scheduler
 * 
 * 역할:
 * - 매일 자정에 일별 정산 프로세스 실행
 * 
 * 일별 정산 프로세스:
 * ==================
 * 1. 스냅샷 생성: 잔고 및 포지션 스냅샷 생성
 * 2. 정산 집계: 거래, 수수료, 사용자 수 집계
 * 3. 사용자별 정산 집계: 각 사용자별 거래 및 수수료 집계
 * 4. 복식부기 검증: 정산 데이터의 정확성 검증
 * 
 * 실행 시점:
 * - 매일 00:00:00 (자정)
 * - cron: "0 0 0 * * ?" (매일 자정)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementScheduler {
    
    private final SnapshotService snapshotService;
    private final SettlementService settlementService;
    
    /**
     * 일별 정산 프로세스 배치 작업
     * Daily Settlement Process Batch Job
     * 
     * 실행 시점: 매일 자정 (00:00:00)
     * cron 표현식: "0 0 0 * * ?" = 매일 00:00:00
     * 
     * 처리 과정:
     * ==========
     * 1. 스냅샷 생성: 잔고 및 포지션 스냅샷 생성
     * 2. 정산 집계: 거래, 수수료, 사용자 수 집계
     * 3. 사용자별 정산 집계: 각 사용자별 거래 및 수수료 집계
     * 4. 복식부기 검증: 정산 데이터의 정확성 검증
     */
    @Scheduled(cron = "0 0 0 * * ?") // 매일 자정
    public void createDailySettlement() {
        log.info("[SettlementScheduler] 일별 정산 프로세스 배치 작업 시작");
        
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1); // 전일 데이터 정산
        
        try {
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 1. 스냅샷 생성
            // Snapshot Creation
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 
            // 목적:
            // - 전일 자정 시점의 잔고 및 포지션 상태를 스냅샷으로 저장
            // - 향후 정산 검증 및 리포트 생성에 활용
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            
            log.info("[SettlementScheduler] 1단계: 스냅샷 생성 시작");
            int snapshotCount = snapshotService.createDailySnapshots(yesterday);
            log.info("[SettlementScheduler] 1단계 완료: 스냅샷 생성 완료, count={}", snapshotCount);
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 2. 정산 집계 (전체)
            // Settlement Aggregation (Overall)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 
            // 목적:
            // - 전일 모든 거래, 수수료, 사용자 수를 집계하여 settlements 테이블에 저장
            // - 거래소의 일별 성과를 요약하여 기록
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            
            log.info("[SettlementScheduler] 2단계: 정산 집계 시작");
            dustin.cex.domains.settlement.model.entity.Settlement settlement = 
                    settlementService.createDailySettlement(today);
            log.info("[SettlementScheduler] 2단계 완료: 정산 집계 완료, settlementId={}, totalTrades={}, totalFeeRevenue={}", 
                    settlement.getId(), settlement.getTotalTrades(), settlement.getTotalFeeRevenue());
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 3. 사용자별 정산 집계
            // User-Specific Settlement Aggregation
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 
            // 목적:
            // - 전일 거래에 참여한 각 사용자별로 거래 및 수수료를 집계하여 user_settlements 테이블에 저장
            // - 사용자 리포트 생성의 기초 데이터 제공
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            
            log.info("[SettlementScheduler] 3단계: 사용자별 정산 집계 시작");
            // 전일 거래에 참여한 모든 사용자 ID 조회
            List<Long> userIds = settlementService.getUserIdsFromTrades(yesterday);
            log.info("[SettlementScheduler] 사용자별 정산 대상 사용자 수: {}", userIds.size());
            
            int userSettlementCount = 0;
            for (Long userId : userIds) {
                try {
                    settlementService.createUserDailySettlement(userId, today);
                    userSettlementCount++;
                } catch (Exception e) {
                    log.error("[SettlementScheduler] 사용자별 정산 집계 실패: userId={}", userId, e);
                    // 개별 사용자 실패는 전체 프로세스를 중단하지 않음
                }
            }
            log.info("[SettlementScheduler] 3단계 완료: 사용자별 정산 집계 완료, count={}", userSettlementCount);
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 4. 복식부기 검증
            // Double-Entry Bookkeeping Validation
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 
            // 목적:
            // - 정산 데이터의 정확성을 검증
            // - 거래 검증, 수수료 검증, 잔고 검증 수행
            // - 검증 결과를 settlements 테이블에 저장
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            
            log.info("[SettlementScheduler] 4단계: 복식부기 검증 시작");
            SettlementService.ValidationResult validationResult = 
                    settlementService.validateDoubleEntryBookkeeping(yesterday);
            
            // 검증 결과를 settlements 테이블에 업데이트
            settlement.setValidationStatus(validationResult.getStatus());
            if (!validationResult.getErrors().isEmpty()) {
                settlement.setValidationError(String.join("; ", validationResult.getErrors()));
            }
            settlementService.updateSettlement(settlement);
            
            log.info("[SettlementScheduler] 4단계 완료: 복식부기 검증 완료, status={}, errors={}", 
                    validationResult.getStatus(), validationResult.getErrors().size());
            
            log.info("[SettlementScheduler] 일별 정산 프로세스 배치 작업 완료: date={}, snapshotCount={}, userSettlementCount={}, validationStatus={}", 
                    yesterday, snapshotCount, userSettlementCount, validationResult.getStatus());
            
        } catch (Exception e) {
            log.error("[SettlementScheduler] 일별 정산 프로세스 배치 작업 실패: date={}", yesterday, e);
            throw e; // 배치 작업 실패는 재시도가 필요하므로 예외를 다시 던짐
        }
    }
    
    /**
     * 월별 정산 프로세스 배치 작업
     * Monthly Settlement Process Batch Job
     * 
     * 실행 시점: 매월 1일 자정 (00:00:00)
     * cron 표현식: "0 0 0 1 * ?" = 매월 1일 00:00:00
     * 
     * 처리 과정:
     * ==========
     * 1. 월별 정산 집계: 전월 모든 거래, 수수료, 사용자 수 집계
     * 2. 사용자별 월별 정산 집계: 각 사용자별 거래 및 수수료 집계
     * 3. 복식부기 검증: 월별 정산 데이터의 정확성 검증
     */
    @Scheduled(cron = "0 0 0 1 * ?") // 매월 1일 자정
    public void createMonthlySettlement() {
        log.info("[SettlementScheduler] 월별 정산 프로세스 배치 작업 시작");
        
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        int month = today.getMonthValue();
        
        // 전월 데이터 정산
        if (month == 1) {
            year--;
            month = 12;
        } else {
            month--;
        }
        
        try {
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 1. 월별 정산 집계 (전체)
            // Monthly Settlement Aggregation (Overall)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            
            log.info("[SettlementScheduler] 1단계: 월별 정산 집계 시작");
            dustin.cex.domains.settlement.model.entity.Settlement settlement = 
                    settlementService.createMonthlySettlement(year, month);
            log.info("[SettlementScheduler] 1단계 완료: 월별 정산 집계 완료, settlementId={}, totalTrades={}, totalFeeRevenue={}", 
                    settlement.getId(), settlement.getTotalTrades(), settlement.getTotalFeeRevenue());
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 2. 사용자별 월별 정산 집계
            // User-Specific Monthly Settlement Aggregation
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            
            log.info("[SettlementScheduler] 2단계: 사용자별 월별 정산 집계 시작");
            LocalDate startDate = LocalDate.of(year, month, 1);
            List<Long> userIds = settlementService.getUserIdsFromTrades(startDate);
            log.info("[SettlementScheduler] 사용자별 월별 정산 대상 사용자 수: {}", userIds.size());
            
            int userSettlementCount = 0;
            for (Long userId : userIds) {
                try {
                    settlementService.createUserMonthlySettlement(userId, year, month);
                    userSettlementCount++;
                } catch (Exception e) {
                    log.error("[SettlementScheduler] 사용자별 월별 정산 집계 실패: userId={}", userId, e);
                    // 개별 사용자 실패는 전체 프로세스를 중단하지 않음
                }
            }
            log.info("[SettlementScheduler] 2단계 완료: 사용자별 월별 정산 집계 완료, count={}", userSettlementCount);
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 3. 복식부기 검증
            // Double-Entry Bookkeeping Validation
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            
            log.info("[SettlementScheduler] 3단계: 복식부기 검증 시작");
            SettlementService.ValidationResult validationResult = 
                    settlementService.validateDoubleEntryBookkeeping(startDate);
            
            // 검증 결과를 settlements 테이블에 업데이트
            settlement.setValidationStatus(validationResult.getStatus());
            if (!validationResult.getErrors().isEmpty()) {
                settlement.setValidationError(String.join("; ", validationResult.getErrors()));
            }
            settlementService.updateSettlement(settlement);
            
            log.info("[SettlementScheduler] 3단계 완료: 복식부기 검증 완료, status={}, errors={}", 
                    validationResult.getStatus(), validationResult.getErrors().size());
            
            log.info("[SettlementScheduler] 월별 정산 프로세스 배치 작업 완료: year={}, month={}, userSettlementCount={}, validationStatus={}", 
                    year, month, userSettlementCount, validationResult.getStatus());
            
        } catch (Exception e) {
            log.error("[SettlementScheduler] 월별 정산 프로세스 배치 작업 실패: year={}, month={}", year, month, e);
            throw e; // 배치 작업 실패는 재시도가 필요하므로 예외를 다시 던짐
        }
    }
    
    /**
     * 수동 스냅샷 생성 (테스트용)
     * Manual snapshot creation for testing
     * 
     * @param snapshotDate 스냅샷 날짜 (null이면 오늘 날짜 사용)
     * @return 생성된 스냅샷 개수
     */
    public int createDailySnapshotsManually(LocalDate snapshotDate) {
        log.info("[SettlementScheduler] 수동 스냅샷 생성 시작: snapshotDate={}", snapshotDate);
        
        try {
            if (snapshotDate == null) {
                snapshotDate = LocalDate.now();
            }
            
            int count = snapshotService.createDailySnapshots(snapshotDate);
            
            log.info("[SettlementScheduler] 수동 스냅샷 생성 완료: snapshotDate={}, count={}", snapshotDate, count);
            return count;
        } catch (Exception e) {
            log.error("[SettlementScheduler] 수동 스냅샷 생성 실패: snapshotDate={}", snapshotDate, e);
            throw e;
        }
    }
    
    /**
     * 일별 정산 프로세스 수동 실행 (테스트용)
     * Manual daily settlement process execution for testing
     * 
     * @param today 정산 실행 날짜 (전일 데이터 정산)
     */
    public void createDailySettlementManually(LocalDate today) {
        log.info("[SettlementScheduler] 일별 정산 프로세스 수동 실행 시작: today={}", today);
        
        LocalDate yesterday = today.minusDays(1); // 전일 데이터 정산
        
        try {
            // 1. 스냅샷 생성
            log.info("[SettlementScheduler] 1단계: 스냅샷 생성 시작");
            int snapshotCount = snapshotService.createDailySnapshots(yesterday);
            log.info("[SettlementScheduler] 1단계 완료: 스냅샷 생성 완료, count={}", snapshotCount);
            
            // 2. 정산 집계 (전체)
            log.info("[SettlementScheduler] 2단계: 정산 집계 시작");
            dustin.cex.domains.settlement.model.entity.Settlement settlement = 
                    settlementService.createDailySettlement(today);
            log.info("[SettlementScheduler] 2단계 완료: 정산 집계 완료, settlementId={}, totalTrades={}, totalFeeRevenue={}", 
                    settlement.getId(), settlement.getTotalTrades(), settlement.getTotalFeeRevenue());
            
            // 3. 사용자별 정산 집계
            log.info("[SettlementScheduler] 3단계: 사용자별 정산 집계 시작");
            List<Long> userIds = settlementService.getUserIdsFromTrades(yesterday);
            log.info("[SettlementScheduler] 사용자별 정산 대상 사용자 수: {}", userIds.size());
            
            int userSettlementCount = 0;
            for (Long userId : userIds) {
                try {
                    settlementService.createUserDailySettlement(userId, today);
                    userSettlementCount++;
                } catch (Exception e) {
                    log.error("[SettlementScheduler] 사용자별 정산 집계 실패: userId={}", userId, e);
                    // 개별 사용자 실패는 전체 프로세스를 중단하지 않음
                }
            }
            log.info("[SettlementScheduler] 3단계 완료: 사용자별 정산 집계 완료, count={}", userSettlementCount);
            
            // 4. 복식부기 검증
            log.info("[SettlementScheduler] 4단계: 복식부기 검증 시작");
            SettlementService.ValidationResult validationResult = 
                    settlementService.validateDoubleEntryBookkeeping(yesterday);
            
            // 검증 결과를 settlements 테이블에 업데이트
            settlement.setValidationStatus(validationResult.getStatus());
            if (!validationResult.getErrors().isEmpty()) {
                settlement.setValidationError(String.join("; ", validationResult.getErrors()));
            }
            settlementService.updateSettlement(settlement);
            
            log.info("[SettlementScheduler] 4단계 완료: 복식부기 검증 완료, status={}, errors={}", 
                    validationResult.getStatus(), validationResult.getErrors().size());
            
            log.info("[SettlementScheduler] 일별 정산 프로세스 수동 실행 완료: date={}, snapshotCount={}, userSettlementCount={}, validationStatus={}", 
                    yesterday, snapshotCount, userSettlementCount, validationResult.getStatus());
            
        } catch (Exception e) {
            log.error("[SettlementScheduler] 일별 정산 프로세스 수동 실행 실패: date={}", yesterday, e);
            throw e;
        }
    }
}
