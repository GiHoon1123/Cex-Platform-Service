package dustin.cex.domains.settlement.service;

import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.position.model.entity.UserPosition;
import dustin.cex.domains.position.repository.UserPositionRepository;
import dustin.cex.domains.settlement.model.entity.BalanceSnapshot;
import dustin.cex.domains.settlement.model.entity.PositionSnapshot;
import dustin.cex.domains.settlement.repository.BalanceSnapshotRepository;
import dustin.cex.domains.settlement.repository.PositionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 스냅샷 생성 서비스
 * Snapshot Service
 * 
 * 역할:
 * - 일별 잔고 및 포지션 스냅샷 생성
 * - 매일 자정 배치 작업으로 실행
 * 
 * 처리 과정:
 * 1. user_balances → balance_snapshots 복사
 * 2. user_positions → position_snapshots 복사 (시장가 포함)
 * 
 * 트랜잭션:
 * - 모든 스냅샷 생성을 하나의 트랜잭션으로 처리
 * - 실패 시 롤백
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotService {
    
    private final UserBalanceRepository userBalanceRepository;
    private final UserPositionRepository userPositionRepository;
    private final BalanceSnapshotRepository balanceSnapshotRepository;
    private final PositionSnapshotRepository positionSnapshotRepository;
    
    /**
     * 일별 스냅샷 생성
     * Create daily snapshots for balances and positions
     * 
     * @param snapshotDate 스냅샷 날짜 (null이면 오늘 날짜 사용)
     * @return 생성된 스냅샷 개수 (balance + position)
     */
    @Transactional
    public int createDailySnapshots(LocalDate snapshotDate) {
        if (snapshotDate == null) {
            snapshotDate = LocalDate.now();
        }
        
        LocalDateTime snapshotTime = LocalDateTime.now();
        log.info("[SnapshotService] 일별 스냅샷 생성 시작: snapshotDate={}, snapshotTime={}", snapshotDate, snapshotTime);
        
        // 이미 스냅샷이 존재하는지 확인
        if (balanceSnapshotRepository.existsBySnapshotDate(snapshotDate)) {
            log.warn("[SnapshotService] 이미 스냅샷이 존재함: snapshotDate={}", snapshotDate);
            return 0;
        }
        
        int balanceCount = createBalanceSnapshots(snapshotDate, snapshotTime);
        int positionCount = createPositionSnapshots(snapshotDate, snapshotTime);
        
        log.info("[SnapshotService] 일별 스냅샷 생성 완료: snapshotDate={}, balanceCount={}, positionCount={}", 
                snapshotDate, balanceCount, positionCount);
        
        return balanceCount + positionCount;
    }
    
    /**
     * 잔고 스냅샷 생성
     * Create balance snapshots from user_balances
     * 
     * @param snapshotDate 스냅샷 날짜
     * @param snapshotTime 스냅샷 시점
     * @return 생성된 스냅샷 개수
     */
    private int createBalanceSnapshots(LocalDate snapshotDate, LocalDateTime snapshotTime) {
        List<UserBalance> balances = userBalanceRepository.findAll();
        log.info("[SnapshotService] 잔고 스냅샷 생성 시작: balanceCount={}", balances.size());
        
        int count = 0;
        for (UserBalance balance : balances) {
            // 총 잔고 계산
            BigDecimal totalBalance = balance.getAvailable().add(balance.getLocked());
            
            // 스냅샷 생성
            BalanceSnapshot snapshot = BalanceSnapshot.builder()
                    .userId(balance.getUserId())
                    .snapshotDate(snapshotDate)
                    .snapshotTime(snapshotTime)
                    .mintAddress(balance.getMintAddress())
                    .available(balance.getAvailable())
                    .locked(balance.getLocked())
                    .totalBalance(totalBalance)
                    .build();
            
            balanceSnapshotRepository.save(snapshot);
            count++;
        }
        
        log.info("[SnapshotService] 잔고 스냅샷 생성 완료: count={}", count);
        return count;
    }
    
    /**
     * 포지션 스냅샷 생성
     * Create position snapshots from user_positions
     * 
     * @param snapshotDate 스냅샷 날짜
     * @param snapshotTime 스냅샷 시점
     * @return 생성된 스냅샷 개수
     */
    private int createPositionSnapshots(LocalDate snapshotDate, LocalDateTime snapshotTime) {
        List<UserPosition> positions = userPositionRepository.findAll();
        log.info("[SnapshotService] 포지션 스냅샷 생성 시작: positionCount={}", positions.size());
        
        int count = 0;
        for (UserPosition position : positions) {
            // 포지션이 0이면 스냅샷 생성하지 않음
            if (position.getPositionAmount().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            
            // 시장 가격 조회 (현재는 current_price 사용, 향후 외부 API 연동 가능)
            BigDecimal marketPrice = position.getCurrentPrice();
            
            // 평가액 계산
            BigDecimal currentValue = marketPrice.multiply(position.getPositionAmount());
            
            // 미실현 손익 계산
            BigDecimal unrealizedPnl = position.getUnrealizedPnl();
            
            // 미실현 수익률 계산 (%)
            BigDecimal unrealizedPnlPercent = BigDecimal.ZERO;
            BigDecimal costBasis = position.getAvgEntryPrice().multiply(position.getPositionAmount());
            if (costBasis.compareTo(BigDecimal.ZERO) != 0) {
                unrealizedPnlPercent = unrealizedPnl
                        .divide(costBasis, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
            
            // 스냅샷 생성
            PositionSnapshot snapshot = PositionSnapshot.builder()
                    .userId(position.getUserId())
                    .snapshotDate(snapshotDate)
                    .baseMint(position.getBaseMint())
                    .quoteMint(position.getQuoteMint())
                    .positionAmount(position.getPositionAmount())
                    .avgEntryPrice(position.getAvgEntryPrice())
                    .marketPrice(marketPrice)
                    .currentValue(currentValue)
                    .unrealizedPnl(unrealizedPnl)
                    .unrealizedPnlPercent(unrealizedPnlPercent)
                    .realizedPnl(BigDecimal.ZERO) // TODO: 실현 손익 계산 로직 추가 필요
                    .build();
            
            positionSnapshotRepository.save(snapshot);
            count++;
        }
        
        log.info("[SnapshotService] 포지션 스냅샷 생성 완료: count={}", count);
        return count;
    }
}
