package dustin.cex.domains.settlement.trade.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.settlement.trade.model.entity.TradeBalanceSnapshot;
import dustin.cex.domains.settlement.trade.repository.TradeBalanceSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래 정산용 잔고 스냅샷 서비스
 * Trade Balance Snapshot Service
 * 
 * 역할:
 * - 일별 잔고 스냅샷 생성 (거래 정산 시점)
 * - 거래 정산에만 필요
 * 
 * 하위 도메인 분리:
 * ================
 * 이 서비스는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산에만 필요한 잔고 스냅샷입니다:
 * - 거래 정산: 거래 전후 잔고 비교 ✅
 * - 입출금 정산: 입출금 정산 시점의 잔고 스냅샷 필요 (별도 서비스) ❌
 * - 이벤트 정산: 이벤트 정산 시점의 잔고 스냅샷 필요 (별도 서비스) ❌
 * 
 * 트랜잭션:
 * - 모든 잔고 스냅샷 생성을 하나의 트랜잭션으로 처리
 * - 실패 시 롤백
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeBalanceSnapshotService {
    
    private final UserBalanceRepository userBalanceRepository;
    private final TradeBalanceSnapshotRepository tradeBalanceSnapshotRepository;
    
    /**
     * 일별 잔고 스냅샷 생성 (거래 정산 시점)
     * Create daily balance snapshots for trade settlement
     * 
     * @param snapshotDate 스냅샷 날짜 (null이면 오늘 날짜 사용)
     * @return 생성된 스냅샷 개수
     */
    @Transactional
    public int createDailySnapshots(LocalDate snapshotDate) {
        if (snapshotDate == null) {
            snapshotDate = LocalDate.now();
        }
        
        LocalDateTime snapshotTime = LocalDateTime.now();
        log.info("[TradeBalanceSnapshotService] 일별 잔고 스냅샷 생성 시작 (거래 정산): snapshotDate={}, snapshotTime={}", snapshotDate, snapshotTime);
        
        // 이미 스냅샷이 존재하는지 확인
        if (tradeBalanceSnapshotRepository.existsBySnapshotDate(snapshotDate)) {
            log.warn("[TradeBalanceSnapshotService] 이미 잔고 스냅샷이 존재함: snapshotDate={}", snapshotDate);
            return 0;
        }
        
        List<UserBalance> balances = userBalanceRepository.findAll();
        log.info("[TradeBalanceSnapshotService] 잔고 스냅샷 생성 시작: balanceCount={}", balances.size());
        
        int count = 0;
        for (UserBalance balance : balances) {
            // 총 잔고 계산
            BigDecimal totalBalance = balance.getAvailable().add(balance.getLocked());
            
            // 스냅샷 생성
            TradeBalanceSnapshot snapshot = TradeBalanceSnapshot.builder()
                    .userId(balance.getUserId())
                    .snapshotDate(snapshotDate)
                    .snapshotTime(snapshotTime)
                    .mintAddress(balance.getMintAddress())
                    .available(balance.getAvailable())
                    .locked(balance.getLocked())
                    .totalBalance(totalBalance)
                    .build();
            
            tradeBalanceSnapshotRepository.save(snapshot);
            count++;
        }
        
        log.info("[TradeBalanceSnapshotService] 일별 잔고 스냅샷 생성 완료 (거래 정산): snapshotDate={}, count={}", snapshotDate, count);
        return count;
    }
}
