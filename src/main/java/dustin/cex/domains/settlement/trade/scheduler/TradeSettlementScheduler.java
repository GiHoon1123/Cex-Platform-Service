package dustin.cex.domains.settlement.trade.scheduler;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dustin.cex.domains.settlement.trade.service.TradeSettlementRunService;
import dustin.cex.domains.settlement.trade.service.TradeSettlementService;
import dustin.cex.domains.settlement.trade.service.TradeSettlementValidator;
import dustin.cex.domains.settlement.trade.service.TradeSnapshotOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래 정산 스케줄러
 * Trade Settlement Scheduler
 * 
 * 역할:
 * - 매일 자정에 거래 정산 프로세스 실행
 * - 매월 1일 자정에 거래 정산 월별 집계 실행
 * 
 * 하위 도메인 분리:
 * ================
 * 이 스케줄러는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산만을 담당하며, 향후 입출금/이벤트 정산은 별도 스케줄러에서 처리됩니다.
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
public class TradeSettlementScheduler {
    
    private final TradeSnapshotOrchestrator tradeSnapshotOrchestrator;
    private final TradeSettlementService tradeSettlementService;
    private final TradeSettlementValidator tradeSettlementValidator;
    private final TradeSettlementRunService tradeSettlementRunService;
    
    /**
     * 일별 거래 정산 프로세스 배치 작업
     * Daily Trade Settlement Process Batch Job
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
    /**
     * 일별 거래 정산 프로세스 배치 작업 (재시도 지원)
     * Daily Trade Settlement Process Batch Job (with Retry)
     * 
     * 재시도 전략:
     * ===========
     * - 최대 3회 재시도
     * - 지수 백오프: 2초 → 4초 → 8초
     * - RuntimeException 발생 시에만 재시도
     * - 재시도 실패 시 recover 메서드 호출
     */
    @Scheduled(cron = "0 0 0 * * ?") // 매일 자정
    @Retryable(
            retryFor = {RuntimeException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void createDailySettlement() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        runDailySettlementForDate(yesterday, false);
    }

    /**
     * 지정 날짜에 대해 일별 정산 4단계 전체 실행 (수동 API 및 스케줄 공통 로직)
     * Run full 4-step daily settlement for a given date.
     *
     * @param date 정산할 날짜
     * @param forceRecreate true면 2단계에서 기존 정산 삭제 후 재생성, false면 이미 있으면 기존 반환(단계별 재개)
     */
    public void runDailySettlementForDate(LocalDate date, boolean forceRecreate) {
        log.info("[TradeSettlementScheduler] 일별 거래 정산 프로세스 시작: date={}, forceRecreate={}", date, forceRecreate);

        Long runId = null;
        List<Long> failedUserIds = new ArrayList<>();
        int snapshotCount = 0;
        Long tradeSettlementId = null;
        int userSettlementCount = 0;

        try {
            runId = tradeSettlementRunService.startRun(date).getId();

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 1. 스냅샷 생성 (trade) - 독립 트랜잭션
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            try {
                log.info("[TradeSettlementScheduler] 1단계: 스냅샷 생성 시작 (거래 정산)");
                snapshotCount = tradeSnapshotOrchestrator.createDailySnapshots(date);
                log.info("[TradeSettlementScheduler] 1단계 완료: 스냅샷 생성 완료, count={}", snapshotCount);
            } catch (Exception e) {
                log.error("[TradeSettlementScheduler] 스냅샷 생성 실패: date={}", date, e);
                tradeSettlementService.recordFailure(date, 1, null, e.getMessage());
            }
            tradeSettlementRunService.updateCompletedStep(runId, 1);

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 2. 거래 정산 집계 (trade) - 독립 트랜잭션
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            try {
                log.info("[TradeSettlementScheduler] 2단계: 거래 정산 집계 시작");
                var tradeSettlement = tradeSettlementService.createDailySettlement(date, forceRecreate);
                tradeSettlementId = tradeSettlement.getId();
                log.info("[TradeSettlementScheduler] 2단계 완료: 거래 정산 집계 완료, settlementId={}", tradeSettlementId);
            } catch (Exception e) {
                log.error("[TradeSettlementScheduler] 거래 정산 집계 실패: date={}", date, e);
                tradeSettlementService.recordFailure(date, 2, null, e.getMessage());
                throw e;
            }
            tradeSettlementRunService.updateCompletedStep(runId, 2);

            // 3. 사용자별 거래 정산 집계 (trade) - 독립 트랜잭션
            log.info("[TradeSettlementScheduler] 3단계: 사용자별 거래 정산 집계 시작");
            List<Long> userIds = tradeSettlementService.getUserIdsFromTrades(date);
            log.info("[TradeSettlementScheduler] 사용자별 정산 대상 사용자 수: {}", userIds.size());
            failedUserIds = new ArrayList<>();
            for (Long userId : userIds) {
                try {
                    tradeSettlementService.createUserDailySettlement(userId, date);
                    userSettlementCount++;
                } catch (Exception e) {
                    log.error("[TradeSettlementScheduler] 사용자별 정산 집계 실패: userId={}", userId, e);
                    tradeSettlementService.recordFailure(date, 3, userId, e.getMessage());
                    failedUserIds.add(userId);
                }
            }
            log.info("[TradeSettlementScheduler] 3단계 완료: 사용자별 거래 정산 집계 완료, count={}", userSettlementCount);
            if (userSettlementCount < userIds.size()) {
                throw new RuntimeException("사용자별 정산 미완료: date=" + date + ", done=" + userSettlementCount + ", total=" + userIds.size());
            }
            tradeSettlementRunService.updateCompletedStep(runId, 3);

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 4. 거래 정산 검증 (trade) - 독립 트랜잭션 (이미 VALIDATED면 스킵 → 단계별 재개)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            if (tradeSettlementId != null) {
                try {
                    var existing = tradeSettlementService.getSettlementByDate(date);
                    if ("VALIDATED".equals(existing.getValidationStatus())) {
                        log.info("[TradeSettlementScheduler] 4단계 스킵: 이미 검증 완료됨 (VALIDATED): date={}", date);
                    } else {
                        tradeSettlementService.updateValidationStatus(date, "VALIDATING", null);
                        log.info("[TradeSettlementScheduler] 4단계: 거래 정산 검증 시작");
                        TradeSettlementValidator.ValidationResult validationResult =
                                tradeSettlementValidator.validateDoubleEntryBookkeeping(date);
                        String errorMessage = validationResult.getErrors().isEmpty()
                                ? null
                                : String.join("; ", validationResult.getErrors());
                        String finalStatus = "validated".equals(validationResult.getStatus()) ? "VALIDATED" : "FAILED";
                        tradeSettlementService.updateValidationStatus(date, finalStatus, errorMessage);
                        log.info("[TradeSettlementScheduler] 4단계 완료: 거래 정산 검증 완료, status={}, errors={}",
                                finalStatus, validationResult.getErrors().size());
                    }
                } catch (Exception e) {
                    log.error("[TradeSettlementScheduler] 거래 정산 검증 실패: date={}", date, e);
                    tradeSettlementService.updateValidationStatus(date, "FAILED", e.getMessage());
                    tradeSettlementService.recordFailure(date, 4, null, e.getMessage());
                    throw e;
                }
            }
            tradeSettlementRunService.updateCompletedStep(runId, 4);
            tradeSettlementRunService.completeRun(runId);

            log.info("[TradeSettlementScheduler] 일별 거래 정산 프로세스 배치 작업 완료: date={}, snapshotCount={}, tradeSettlementId={}, userSettlementCount={}",
                    date, snapshotCount, tradeSettlementId, userSettlementCount);

        } catch (Exception e) {
            log.error("[TradeSettlementScheduler] 일별 거래 정산 프로세스 배치 작업 실패: date={}", date, e);
            if (runId != null) {
                tradeSettlementRunService.failRun(runId, e.getMessage(), failedUserIds);
            }
            throw e;
        }
    }
    
    /**
     * 월별 거래 정산 프로세스 배치 작업
     * Monthly Trade Settlement Process Batch Job
     * 
     * 실행 시점: 매월 1일 자정 (00:00:00)
     * cron 표현식: "0 0 0 1 * ?" = 매월 1일 00:00:00
     * 
     * 처리 과정:
     * ==========
     * 1. 월별 정산 집계: 전월 모든 거래, 수수료, 사용자 수 집계
     * 2. 사용자별 월별 정산 집계: 각 사용자별 거래 및 수수료 집계
     */
    @Scheduled(cron = "0 0 0 1 * ?") // 매월 1일 자정
    @Retryable(
            retryFor = {RuntimeException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2)
    )
    public void createMonthlySettlement() {
        log.info("[TradeSettlementScheduler] 월별 거래 정산 프로세스 배치 작업 시작");
        
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
            // 1. 거래 정산 집계 (trade) - 독립 트랜잭션
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Long tradeSettlementId = null;
            int userSettlementCount = 0;
            try {
                log.info("[TradeSettlementScheduler] 1단계: 월별 거래 정산 집계 시작");
                var tradeSettlement = tradeSettlementService.createMonthlySettlement(year, month);
                tradeSettlementId = tradeSettlement.getId();
                log.info("[TradeSettlementScheduler] 1단계 완료: 월별 거래 정산 집계 완료, settlementId={}", tradeSettlementId);
                
                // 2. 사용자별 월별 거래 정산 집계 (trade) - 독립 트랜잭션
                log.info("[TradeSettlementScheduler] 2단계: 사용자별 월별 거래 정산 집계 시작");
                LocalDate startDate = LocalDate.of(year, month, 1);
                List<Long> userIds = tradeSettlementService.getUserIdsFromTrades(startDate);
                log.info("[TradeSettlementScheduler] 사용자별 월별 정산 대상 사용자 수: {}", userIds.size());
                
                for (Long userId : userIds) {
                    try {
                        tradeSettlementService.createUserMonthlySettlement(userId, year, month);
                        userSettlementCount++;
                    } catch (Exception e) {
                        log.error("[TradeSettlementScheduler] 사용자별 월별 정산 집계 실패: userId={}", userId, e);
                        // 개별 사용자 실패는 전체 프로세스를 중단하지 않음
                    }
                }
                log.info("[TradeSettlementScheduler] 2단계 완료: 사용자별 월별 거래 정산 집계 완료, count={}", userSettlementCount);
            } catch (Exception e) {
                log.error("[TradeSettlementScheduler] 거래 정산 집계 실패: year={}, month={}", year, month, e);
                // 거래 정산 실패는 다른 정산을 중단하지 않음
            }
            
            log.info("[TradeSettlementScheduler] 월별 거래 정산 프로세스 배치 작업 완료: year={}, month={}, tradeSettlementId={}, userSettlementCount={}", 
                    year, month, tradeSettlementId, userSettlementCount);
            
        } catch (Exception e) {
            log.error("[TradeSettlementScheduler] 월별 거래 정산 프로세스 배치 작업 실패: year={}, month={}", year, month, e);
            throw e; // 배치 작업 실패는 재시도가 필요하므로 예외를 다시 던짐
        }
    }
    
    /**
     * 일별 정산 재시도 실패 시 복구 처리
     * Recover from Daily Settlement Retry Failure
     * 
     * 모든 재시도가 실패한 경우 호출됩니다.
     * 알림 발송, 상태 업데이트 등의 복구 작업을 수행합니다.
     */
    @Recover
    public void recoverDailySettlement(RuntimeException e) {
        log.error("[TradeSettlementScheduler] 일별 거래 정산 프로세스 재시도 실패 - 복구 처리 시작", e);
        
        // TODO: 알림 발송 (이메일, 슬랙 등)
        // TODO: 실패 상태를 DB에 기록
        // TODO: 관리자 대시보드에 알림 표시
        
        log.error("[TradeSettlementScheduler] 일별 거래 정산 프로세스 복구 처리 완료");
    }
    
    /**
     * 월별 정산 재시도 실패 시 복구 처리
     * Recover from Monthly Settlement Retry Failure
     */
    @Recover
    public void recoverMonthlySettlement(RuntimeException e) {
        log.error("[TradeSettlementScheduler] 월별 거래 정산 프로세스 재시도 실패 - 복구 처리 시작", e);
        
        // TODO: 알림 발송 (이메일, 슬랙 등)
        // TODO: 실패 상태를 DB에 기록
        // TODO: 관리자 대시보드에 알림 표시
        
        log.error("[TradeSettlementScheduler] 월별 거래 정산 프로세스 복구 처리 완료");
    }
}
