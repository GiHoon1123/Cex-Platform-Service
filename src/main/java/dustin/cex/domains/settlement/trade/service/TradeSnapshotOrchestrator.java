package dustin.cex.domains.settlement.trade.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래 정산 스냅샷 오케스트레이터
 * Trade Settlement Snapshot Orchestrator
 * 
 * 역할:
 * - 거래 정산에 필요한 모든 스냅샷 생성 오케스트레이션
 * - 잔고 스냅샷 + 포지션 스냅샷 생성
 * 
 * 하위 도메인 분리:
 * ================
 * 이 서비스는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산에만 필요한 스냅샷들을 통합 관리합니다:
 * - 잔고 스냅샷: 거래 전후 잔고 비교
 * - 포지션 스냅샷: 거래로 인한 포지션 변화 추적
 * 
 * 트랜잭션 관리:
 * ==============
 * 이 서비스는 트랜잭션을 관리하지 않습니다.
 * 각 스냅샷 서비스가 자체적으로 트랜잭션을 관리하므로,
 * 하나의 스냅샷 생성이 실패해도 다른 스냅샷은 계속 생성됩니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeSnapshotOrchestrator {
    
    private final TradeBalanceSnapshotService tradeBalanceSnapshotService;
    private final TradeSnapshotService tradeSnapshotService;
    
    /**
     * 일별 스냅샷 생성 (거래 정산용)
     * Create daily snapshots for trade settlement
     * 
     * 처리 과정:
     * ==========
     * 1. 잔고 스냅샷 생성 (trade) - 독립 트랜잭션
     * 2. 포지션 스냅샷 생성 (trade) - 독립 트랜잭션
     * 
     * @param snapshotDate 스냅샷 날짜 (null이면 오늘 날짜 사용)
     * @return 생성된 스냅샷 개수 (balance + position)
     */
    public int createDailySnapshots(LocalDate snapshotDate) {
        if (snapshotDate == null) {
            snapshotDate = LocalDate.now();
        }
        
        log.info("[TradeSnapshotOrchestrator] 일별 스냅샷 생성 시작 (거래 정산): snapshotDate={}", snapshotDate);
        
        int totalCount = 0;
        
        // 1. 잔고 스냅샷 생성 (trade) - 독립 트랜잭션
        int balanceCount = 0;
        try {
            balanceCount = tradeBalanceSnapshotService.createDailySnapshots(snapshotDate);
            log.info("[TradeSnapshotOrchestrator] 잔고 스냅샷 생성 완료: count={}", balanceCount);
        } catch (Exception e) {
            log.error("[TradeSnapshotOrchestrator] 잔고 스냅샷 생성 실패: date={}", snapshotDate, e);
            // 잔고 스냅샷 실패는 다른 스냅샷을 중단하지 않음
        }
        totalCount += balanceCount;
        
        // 2. 포지션 스냅샷 생성 (trade) - 독립 트랜잭션
        int positionCount = 0;
        try {
            positionCount = tradeSnapshotService.createDailySnapshots(snapshotDate);
            log.info("[TradeSnapshotOrchestrator] 포지션 스냅샷 생성 완료: count={}", positionCount);
        } catch (Exception e) {
            log.error("[TradeSnapshotOrchestrator] 포지션 스냅샷 생성 실패: date={}", snapshotDate, e);
            // 포지션 스냅샷 실패는 다른 스냅샷을 중단하지 않음
        }
        totalCount += positionCount;
        
        log.info("[TradeSnapshotOrchestrator] 일별 스냅샷 생성 완료 (거래 정산): snapshotDate={}, totalCount={}, balanceCount={}, positionCount={}", 
                snapshotDate, totalCount, balanceCount, positionCount);
        
        return totalCount;
    }
}
