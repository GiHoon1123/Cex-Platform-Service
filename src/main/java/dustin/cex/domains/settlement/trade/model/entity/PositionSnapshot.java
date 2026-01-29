package dustin.cex.domains.settlement.trade.model.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래 포지션 스냅샷 엔티티
 * Trade Position Snapshot Entity
 * 
 * 역할:
 * - 일별 포지션 스냅샷 저장 (정산 시점 데이터 고정)
 * - user_positions 테이블의 일일 스냅샷
 * - 정산 및 감사 목적
 * 
 * 하위 도메인 분리:
 * ================
 * 이 엔티티는 settlement.trade 하위 도메인에 속합니다.
 * 포지션 스냅샷은 거래 정산에만 필요합니다:
 * - 거래 정산: 거래로 인한 포지션 변화 추적 ✅
 * - 입출금 정산: 입출금은 잔고만 영향, 포지션 불필요 ❌
 * - 이벤트 정산: 이벤트는 잔고만 영향, 포지션 불필요 ❌
 * 
 * 생성 시점:
 * - 매일 자정 배치 작업으로 생성
 * - snapshot_date = 생성 날짜 (YYYY-MM-DD)
 * - 시장 가격 포함 (스냅샷 시점의 가격)
 * 
 * 용도:
 * - 일별 정산 리포트 생성
 * - 과거 포지션 및 손익 추적
 * - 감사 및 정산 검증
 */
@Entity
@Table(name = "trade_position_snapshots",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "snapshot_date", "base_mint", "quote_mint"}),
       indexes = {
           @Index(name = "idx_trade_position_snapshots_user_date", columnList = "user_id,snapshot_date"),
           @Index(name = "idx_trade_position_snapshots_date", columnList = "snapshot_date"),
           @Index(name = "idx_trade_position_snapshots_base_quote", columnList = "base_mint,quote_mint")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PositionSnapshot {
    
    /**
     * 스냅샷 고유 ID (DB에서 자동 생성)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 사용자 ID
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    /**
     * 스냅샷 날짜 (YYYY-MM-DD)
     * 정산일 기준으로 설정
     */
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;
    
    /**
     * 기준 자산 (예: SOL)
     */
    @Column(name = "base_mint", nullable = false, length = 50)
    private String baseMint;
    
    /**
     * 기준 통화 (예: USDT)
     */
    @Column(name = "quote_mint", nullable = false, length = 50)
    private String quoteMint;
    
    /**
     * 포지션 수량 (스냅샷 시점)
     */
    @Column(name = "position_amount", nullable = false, precision = 30, scale = 9)
    private BigDecimal positionAmount;
    
    /**
     * 평균 진입 가격 (스냅샷 시점)
     */
    @Column(name = "avg_entry_price", precision = 30, scale = 9)
    private BigDecimal avgEntryPrice;
    
    /**
     * 시장 가격 (스냅샷 시점)
     * 스냅샷 생성 시점의 시장 가격
     */
    @Column(name = "market_price", precision = 30, scale = 9)
    private BigDecimal marketPrice;
    
    /**
     * 평가액 (스냅샷 시점)
     * 계산식: market_price * position_amount
     */
    @Column(name = "current_value", precision = 30, scale = 9)
    private BigDecimal currentValue;
    
    /**
     * 미실현 손익 (스냅샷 시점)
     * 계산식: (market_price - avg_entry_price) * position_amount
     */
    @Column(name = "unrealized_pnl", precision = 30, scale = 9)
    private BigDecimal unrealizedPnl;
    
    /**
     * 미실현 수익률 (%)
     * 계산식: (unrealized_pnl / (avg_entry_price * position_amount)) * 100
     */
    @Column(name = "unrealized_pnl_percent", precision = 10, scale = 4)
    private BigDecimal unrealizedPnlPercent;
    
    /**
     * 실현 손익 (스냅샷 시점까지)
     * 스냅샷 시점까지의 누적 실현 손익
     */
    @Column(name = "realized_pnl", nullable = false, precision = 30, scale = 9)
    @Builder.Default
    private BigDecimal realizedPnl = BigDecimal.ZERO;
    
    /**
     * 생성 시간
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    /**
     * 엔티티 저장 전 자동 실행
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
