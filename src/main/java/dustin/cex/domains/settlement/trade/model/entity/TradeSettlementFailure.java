package dustin.cex.domains.settlement.trade.model.entity;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래 정산 실패 기록 엔티티
 * Trade Settlement Failure Entity
 *
 * 역할: 단계별 실패 시 실패 지점·유저 명시적 기록 (우아한 처리)
 * 테이블: trade_settlement_failures (거래 정산 도메인)
 */
@Entity
@Table(name = "trade_settlement_failures",
       indexes = {
           @Index(name = "idx_trade_settlement_failures_date", columnList = "settlement_date"),
           @Index(name = "idx_trade_settlement_failures_step", columnList = "step"),
           @Index(name = "idx_trade_settlement_failures_user_id", columnList = "user_id")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSettlementFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    /** 1=스냅샷, 2=정산집계, 3=사용자별정산, 4=검증 */
    @Column(name = "step", nullable = false)
    private Integer step;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
