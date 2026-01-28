package dustin.cex.domains.settlement.scheduler;

import dustin.cex.domains.settlement.service.SnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 정산 스케줄러
 * Settlement Scheduler
 * 
 * 역할:
 * - 매일 자정에 일별 스냅샷 생성 배치 작업 실행
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
    
    /**
     * 일별 스냅샷 생성 배치 작업
     * Daily snapshot creation batch job
     * 
     * 실행 시점: 매일 자정 (00:00:00)
     * cron 표현식: "0 0 0 * * ?" = 매일 00:00:00
     */
    @Scheduled(cron = "0 0 0 * * ?") // 매일 자정
    public void createDailySnapshots() {
        log.info("[SettlementScheduler] 일별 스냅샷 생성 배치 작업 시작");
        
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1); // 전날 데이터 스냅샷 생성
            int count = snapshotService.createDailySnapshots(yesterday);
            
            log.info("[SettlementScheduler] 일별 스냅샷 생성 배치 작업 완료: count={}", count);
        } catch (Exception e) {
            log.error("[SettlementScheduler] 일별 스냅샷 생성 배치 작업 실패", e);
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
}
