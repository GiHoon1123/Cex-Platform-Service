package dustin.cex.domains.settlement.trade.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dustin.cex.domains.position.model.entity.UserPosition;
import dustin.cex.domains.position.repository.UserPositionRepository;
import dustin.cex.domains.settlement.trade.model.entity.PositionSnapshot;
import dustin.cex.domains.settlement.trade.repository.PositionSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 거래 포지션 스냅샷 서비스
 * Trade Position Snapshot Service
 * 
 * 역할:
 * - 일별 포지션 스냅샷 생성
 * - 거래 정산에만 필요 (거래로 인한 포지션 변화 추적)
 * 
 * 하위 도메인 분리:
 * ================
 * 이 서비스는 settlement.trade 하위 도메인에 속합니다.
 * 포지션 스냅샷은 거래 정산에만 필요합니다:
 * - 거래 정산: 거래로 인한 포지션 변화 추적 ✅
 * - 입출금 정산: 입출금은 잔고만 영향, 포지션 불필요 ❌
 * - 이벤트 정산: 이벤트는 잔고만 영향, 포지션 불필요 ❌
 * 
 * 트랜잭션:
 * - 모든 포지션 스냅샷 생성을 하나의 트랜잭션으로 처리
 * - 실패 시 롤백
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeSnapshotService {
    
    private final UserPositionRepository userPositionRepository;
    private final PositionSnapshotRepository positionSnapshotRepository;
    
    /**
     * 일별 포지션 스냅샷 생성
     * Create daily position snapshots
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
        log.info("[TradeSnapshotService] 일별 포지션 스냅샷 생성 시작: snapshotDate={}, snapshotTime={}", snapshotDate, snapshotTime);
        
        // 이미 스냅샷이 존재하는지 확인
        if (positionSnapshotRepository.existsBySnapshotDate(snapshotDate)) {
            log.warn("[TradeSnapshotService] 이미 포지션 스냅샷이 존재함: snapshotDate={}", snapshotDate);
            return 0;
        }
        
        List<UserPosition> positions = userPositionRepository.findAll();
        log.info("[TradeSnapshotService] 포지션 스냅샷 생성 시작: positionCount={}", positions.size());
        
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
        
        log.info("[TradeSnapshotService] 일별 포지션 스냅샷 생성 완료: snapshotDate={}, count={}", snapshotDate, count);
        return count;
    }
}
