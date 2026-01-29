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
 * 거래 단위 정산 레코드 엔티티
 * Trade Settlement Item Entity
 * 
 * 역할:
 * - 각 거래(Trade)별 정산 상세 정보 저장
 * - 정산 재현성 및 감사 추적을 위한 거래 단위 레코드
 * 
 * 하위 도메인 분리:
 * ================
 * 이 엔티티는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산의 상세 내역을 담당합니다.
 * 
 * 정산에서의 중요성:
 * ==================
 * 1. 재현성:
 *    - 어떤 거래가 정산에 포함되었는지 추적 가능
 *    - 정산 결과를 거래 단위로 재검증 가능
 * 
 * 2. 감사 추적:
 *    - 외부 감사 시 상세 내역 제공
 *    - 특정 거래가 정산에 포함되었는지 확인 가능
 * 
 * 3. 디버깅:
 *    - 정산 오류 발생 시 원인 거래 추적 용이
 *    - 정산 금액 불일치 시 원인 파악 가능
 * 
 * 데이터 구조:
 * ===========
 * - settlement_id: 정산 요약 정보 참조 (TradeSettlement)
 * - trade_id: 거래 참조 (Trade)
 * - trade_volume: 거래 금액 (price × amount)
 * - buyer_fee: 매수자 수수료
 * - seller_fee: 매도자 수수료
 * - total_fee: 총 수수료 (buyer_fee + seller_fee)
 * - settlement_date: 정산일
 */
@Entity
@Table(name = "trade_settlement_items",
       indexes = {
           @Index(name = "idx_trade_settlement_items_settlement_id", columnList = "settlement_id"),
           @Index(name = "idx_trade_settlement_items_trade_id", columnList = "trade_id"),
           @Index(name = "idx_trade_settlement_items_date", columnList = "settlement_date"),
           @Index(name = "idx_trade_settlement_items_settlement_trade", columnList = "settlement_id,trade_id")
       },
       uniqueConstraints = {
           @jakarta.persistence.UniqueConstraint(
               name = "uk_trade_settlement_items_settlement_trade",
               columnNames = {"settlement_id", "trade_id"}
           )
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSettlementItem {
    
    /**
     * 정산 아이템 고유 ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 정산 요약 정보 참조
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id", nullable = false)
    private TradeSettlement settlement;
    
    /**
     * 거래 ID (Trade 참조)
     */
    @Column(name = "trade_id", nullable = false)
    private Long tradeId;
    
    /**
     * 거래 금액 (price × amount)
     */
    @Column(name = "trade_volume", nullable = false, precision = 30, scale = 9)
    private BigDecimal tradeVolume;
    
    /**
     * 매수자 수수료
     */
    @Column(name = "buyer_fee", nullable = false, precision = 30, scale = 9)
    private BigDecimal buyerFee;
    
    /**
     * 매도자 수수료
     */
    @Column(name = "seller_fee", nullable = false, precision = 30, scale = 9)
    private BigDecimal sellerFee;
    
    /**
     * 총 수수료 (buyer_fee + seller_fee)
     */
    @Column(name = "total_fee", nullable = false, precision = 30, scale = 9)
    private BigDecimal totalFee;
    
    /**
     * 정산일
     */
    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;
    
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
