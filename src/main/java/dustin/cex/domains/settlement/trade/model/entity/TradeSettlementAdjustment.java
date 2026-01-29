package dustin.cex.domains.settlement.trade.model.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 거래 정산 보정 레코드 엔티티
 * Trade Settlement Adjustment Entity
 * 
 * 역할:
 * - 기존 정산 데이터의 오류나 변경사항을 보정하기 위한 레코드
 * - 원본 정산 데이터는 불변(immutable)으로 유지하고, 보정 내역만 별도 기록
 * 
 * 하위 도메인 분리:
 * ================
 * 이 엔티티는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산의 보정만을 담당합니다.
 * 
 * 정산에서의 중요성:
 * ==================
 * 1. 불변성 보장:
 *    - 원본 정산 데이터는 변경하지 않음
 *    - 감사 추적을 위해 원본과 보정 내역을 분리
 * 
 * 2. 보정 추적:
 *    - 언제, 누가, 왜 보정했는지 기록
 *    - 보정 전후 값을 모두 저장하여 비교 가능
 * 
 * 3. 재현성:
 *    - 보정 사유와 근거를 명확히 기록
 *    - 향후 동일한 오류 방지를 위한 학습 자료
 * 
 * 데이터 구조:
 * ===========
 * - settlement_id: 보정 대상 정산 참조 (TradeSettlement)
 * - adjustment_type: 보정 유형 ('CORRECTION', 'REFUND', 'ADDITION')
 * - reason: 보정 사유
 * - adjusted_by: 보정 수행자 (사용자 ID 또는 시스템)
 * - volume_adjustment: 거래량 보정 금액
 * - fee_adjustment: 수수료 보정 금액
 * - trades_adjustment: 거래 건수 보정
 * - before_value: 보정 전 값 (JSON 또는 별도 필드)
 * - after_value: 보정 후 값 (JSON 또는 별도 필드)
 */
@Entity
@Table(name = "trade_settlement_adjustments",
       indexes = {
           @Index(name = "idx_trade_settlement_adjustments_settlement_id", columnList = "settlement_id"),
           @Index(name = "idx_trade_settlement_adjustments_date", columnList = "settlement_date"),
           @Index(name = "idx_trade_settlement_adjustments_type", columnList = "adjustment_type")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSettlementAdjustment {
    
    /**
     * 보정 레코드 고유 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 보정 대상 정산 참조
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id", nullable = false)
    private TradeSettlement settlement;
    
    /**
     * 보정 유형: 'CORRECTION', 'REFUND', 'ADDITION'
     * 
     * 의미:
     * - 'CORRECTION': 계산 오류 수정
     * - 'REFUND': 수수료 환불
     * - 'ADDITION': 누락된 거래 추가
     */
    @Column(name = "adjustment_type", nullable = false, length = 20)
    private String adjustmentType;
    
    /**
     * 보정 사유
     */
    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;
    
    /**
     * 보정 수행자 (사용자 ID 또는 'SYSTEM')
     */
    @Column(name = "adjusted_by", nullable = false, length = 255)
    private String adjustedBy;
    
    /**
     * 거래량 보정 금액 (양수: 증가, 음수: 감소)
     */
    @Column(name = "volume_adjustment", nullable = false, precision = 30, scale = 9)
    private BigDecimal volumeAdjustment;
    
    /**
     * 수수료 보정 금액 (양수: 증가, 음수: 감소)
     */
    @Column(name = "fee_adjustment", nullable = false, precision = 30, scale = 9)
    private BigDecimal feeAdjustment;
    
    /**
     * 거래 건수 보정 (양수: 증가, 음수: 감소)
     */
    @Column(name = "trades_adjustment", nullable = false)
    private Long tradesAdjustment;
    
    /**
     * 정산일
     */
    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;
    
    /**
     * 보정 전 값 (JSON 형식으로 저장)
     * 예: {"totalVolume": 1000, "totalFeeRevenue": 10, "totalTrades": 100}
     */
    @Column(name = "before_value", columnDefinition = "TEXT")
    private String beforeValue;
    
    /**
     * 보정 후 값 (JSON 형식으로 저장)
     * 예: {"totalVolume": 1100, "totalFeeRevenue": 11, "totalTrades": 110}
     */
    @Column(name = "after_value", columnDefinition = "TEXT")
    private String afterValue;
    
    /**
     * 생성 시간
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
