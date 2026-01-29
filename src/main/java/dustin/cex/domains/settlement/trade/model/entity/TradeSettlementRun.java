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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래 정산 실행 런 엔티티
 * Trade Settlement Run Entity
 *
 * 역할: 같은 날짜 1차 실행 / 재시도 구분, 완료된 단계(completed_step) 기록
 * 테이블: trade_settlement_runs (거래 정산 도메인)
 */
@Entity
@Table(name = "trade_settlement_runs",
       indexes = {
           @Index(name = "idx_trade_settlement_runs_date", columnList = "settlement_date"),
           @Index(name = "idx_trade_settlement_runs_run_at", columnList = "run_at"),
           @Index(name = "idx_trade_settlement_runs_status", columnList = "status")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSettlementRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "run_at", nullable = false)
    private LocalDateTime runAt;

    /** 0=시작전, 1~4=해당 단계 완료 */
    @Column(name = "completed_step", nullable = false)
    @Builder.Default
    private Integer completedStep = 0;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "RUNNING";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "failed_user_ids_json", columnDefinition = "TEXT")
    private String failedUserIdsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (runAt == null) {
            runAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
