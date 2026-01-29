package dustin.cex.domains.settlement.trade.scheduler;

import java.time.LocalDate;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    @Scheduled(cron = "0 0 0 * * ?") // 매일 자정
    public void createDailySettlement() {
        log.info("[TradeSettlementScheduler] 일별 거래 정산 프로세스 배치 작업 시작");
        
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1); // 전일 데이터 정산
        
        try {
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 1. 스냅샷 생성 (trade) - 독립 트랜잭션
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            int snapshotCount = 0;
            try {
                log.info("[TradeSettlementScheduler] 1단계: 스냅샷 생성 시작 (거래 정산)");
                snapshotCount = tradeSnapshotOrchestrator.createDailySnapshots(yesterday);
                log.info("[TradeSettlementScheduler] 1단계 완료: 스냅샷 생성 완료, count={}", snapshotCount);
            } catch (Exception e) {
                log.error("[TradeSettlementScheduler] 스냅샷 생성 실패: date={}", yesterday, e);
                // 스냅샷 실패는 다른 정산을 중단하지 않음
            }
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 2. 거래 정산 집계 (trade) - 독립 트랜잭션
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Long tradeSettlementId = null;
            int userSettlementCount = 0;
            try {
                log.info("[TradeSettlementScheduler] 2단계: 거래 정산 집계 시작");
                var tradeSettlement = tradeSettlementService.createDailySettlement(yesterday);
                tradeSettlementId = tradeSettlement.getId();
                log.info("[TradeSettlementScheduler] 2단계 완료: 거래 정산 집계 완료, settlementId={}", tradeSettlementId);
                
                // 3. 사용자별 거래 정산 집계 (trade) - 독립 트랜잭션
                log.info("[TradeSettlementScheduler] 3단계: 사용자별 거래 정산 집계 시작");
                List<Long> userIds = tradeSettlementService.getUserIdsFromTrades(yesterday);
                log.info("[TradeSettlementScheduler] 사용자별 정산 대상 사용자 수: {}", userIds.size());
                
                for (Long userId : userIds) {
                    try {
                        tradeSettlementService.createUserDailySettlement(userId, yesterday);
                        userSettlementCount++;
                    } catch (Exception e) {
                        log.error("[TradeSettlementScheduler] 사용자별 정산 집계 실패: userId={}", userId, e);
                        // 개별 사용자 실패는 전체 프로세스를 중단하지 않음
                    }
                }
                log.info("[TradeSettlementScheduler] 3단계 완료: 사용자별 거래 정산 집계 완료, count={}", userSettlementCount);
            } catch (Exception e) {
                log.error("[TradeSettlementScheduler] 거래 정산 집계 실패: date={}", yesterday, e);
                // 거래 정산 실패는 다른 정산을 중단하지 않음
            }
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 4. 거래 정산 검증 (trade) - 독립 트랜잭션
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            if (tradeSettlementId != null) {
                try {
                    log.info("[TradeSettlementScheduler] 4단계: 거래 정산 검증 시작");
                    TradeSettlementValidator.ValidationResult validationResult = 
                            tradeSettlementValidator.validateDoubleEntryBookkeeping(yesterday);
                    
                    // 검증 결과를 DB에 저장
                    String errorMessage = validationResult.getErrors().isEmpty() 
                            ? null 
                            : String.join("; ", validationResult.getErrors());
                    tradeSettlementService.updateValidationStatus(yesterday, validationResult.getStatus(), errorMessage);
                    
                    log.info("[TradeSettlementScheduler] 4단계 완료: 거래 정산 검증 완료, status={}, errors={}", 
                            validationResult.getStatus(), validationResult.getErrors().size());
                } catch (Exception e) {
                    log.error("[TradeSettlementScheduler] 거래 정산 검증 실패: date={}", yesterday, e);
                    // 검증 실패는 다른 정산을 중단하지 않음
                }
            }
            
            log.info("[TradeSettlementScheduler] 일별 거래 정산 프로세스 배치 작업 완료: date={}, snapshotCount={}, tradeSettlementId={}, userSettlementCount={}", 
                    yesterday, snapshotCount, tradeSettlementId, userSettlementCount);
            
        } catch (Exception e) {
            log.error("[TradeSettlementScheduler] 일별 거래 정산 프로세스 배치 작업 실패: date={}", yesterday, e);
            throw e; // 배치 작업 실패는 재시도가 필요하므로 예외를 다시 던짐
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
}
