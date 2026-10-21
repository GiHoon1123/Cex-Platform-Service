package dustin.cex.domains.settlement.trade.model.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.trade.model.entity.Trade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 엔진이 실제 차감한 수수료를 자산별로 기록한다. 매수자는 기준 자산, 매도자는 결제 자산이다.
 * tradeValue는 체결 대금이며, 매수 수수료 계산의 기준 수량과 같은 단위가 아니다.
 * 수익 집계는 반드시 feeMint로 묶는다. 자산 간 환산 없는 합산은 금지한다.
 */
@Entity
@Table(name = "trade_fees",
       indexes = {
           @Index(name = "idx_trade_fees_trade_id", columnList = "trade_id"),
           @Index(name = "idx_trade_fees_user_id", columnList = "user_id"),
           @Index(name = "idx_trade_fees_created_at", columnList = "created_at"),
           @Index(name = "idx_trade_fees_fee_mint", columnList = "fee_mint")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeFee {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "trade_id", nullable = false)
    private Trade trade;
    
    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    
    @Column(name = "fee_type", nullable = false, length = 20)
    private String feeType;
    
    @Column(name = "fee_rate", nullable = false, precision = 10, scale = 6)
    private BigDecimal feeRate;
    
    @Column(name = "fee_amount", nullable = false, precision = 30, scale = 9)
    private BigDecimal feeAmount;
    
    @Column(name = "fee_mint", nullable = false, length = 255)
    private String feeMint;
    
    @Column(name = "trade_value", nullable = false, precision = 30, scale = 9)
    private BigDecimal tradeValue;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
