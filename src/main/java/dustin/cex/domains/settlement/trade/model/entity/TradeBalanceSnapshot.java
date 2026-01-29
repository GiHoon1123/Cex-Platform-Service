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
 * 거래 정산용 잔고 스냅샷 엔티티
 * Trade Balance Snapshot Entity
 * 
 * 역할:
 * - 일별 잔고 스냅샷 저장 (거래 정산 시점 데이터 고정)
 * - user_balances 테이블의 일일 스냅샷
 * - 거래 정산 및 감사 목적
 * 
 * 하위 도메인 분리:
 * ================
 * 이 엔티티는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산에만 필요한 잔고 스냅샷입니다:
 * - 거래 정산: 거래 전후 잔고 비교 ✅
 * - 입출금 정산: 입출금 정산 시점의 잔고 스냅샷 필요 (별도 엔티티) ❌
 * - 이벤트 정산: 이벤트 정산 시점의 잔고 스냅샷 필요 (별도 엔티티) ❌
 * 
 * 생성 시점:
 * - 매일 자정 배치 작업으로 생성 (거래 정산 시점)
 * - snapshot_date = 생성 날짜 (YYYY-MM-DD)
 * 
 * 용도:
 * - 일별 거래 정산 리포트 생성
 * - 과거 잔고 추적 및 복원
 * - 감사 및 정산 검증
 */
@Entity
@Table(name = "trade_balance_snapshots",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "snapshot_date", "mint_address"}),
       indexes = {
           @Index(name = "idx_trade_balance_snapshots_user_date", columnList = "user_id,snapshot_date"),
           @Index(name = "idx_trade_balance_snapshots_date", columnList = "snapshot_date"),
           @Index(name = "idx_trade_balance_snapshots_mint", columnList = "mint_address")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeBalanceSnapshot {
    
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
     * 스냅샷 시점 (정확한 시간)
     * 스냅샷이 생성된 정확한 시각
     */
    @Column(name = "snapshot_time", nullable = false)
    private LocalDateTime snapshotTime;
    
    /**
     * 자산 종류 (예: USDT, SOL)
     */
    @Column(name = "mint_address", nullable = false, length = 50)
    private String mintAddress;
    
    /**
     * 사용 가능한 잔고 (스냅샷 시점)
     */
    @Column(name = "available", nullable = false, precision = 30, scale = 9)
    private BigDecimal available;
    
    /**
     * 잠금된 잔고 (스냅샷 시점)
     */
    @Column(name = "locked", nullable = false, precision = 30, scale = 9)
    private BigDecimal locked;
    
    /**
     * 총 잔고 (available + locked)
     */
    @Column(name = "total_balance", nullable = false, precision = 30, scale = 9)
    private BigDecimal totalBalance;
    
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
