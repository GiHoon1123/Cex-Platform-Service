package dustin.cex.domains.trade.model.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 체결 내역 엔티티
 * Trade Entity
 * 
 * 역할:
 * - 체결 내역을 나타내는 JPA 엔티티
 * - Rust 엔진에서 Kafka로 발행한 체결 이벤트를 DB에 저장
 * 
 * 체결 과정:
 * 1. 매수 주문과 매도 주문이 가격이 일치
 * 2. 매칭 엔진이 두 주문을 매칭
 * 3. 체결 발생 → Trade 레코드 생성
 * 4. 각 사용자의 잔고 업데이트
 */
@Entity
@Table(name = "trades")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trade {
    
    /**
     * 체결 내역 고유 ID (DB에서 자동 생성)
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 매수 주문 ID (누가 구매했는지)
     */
    @Column(name = "buy_order_id", nullable = false)
    private Long buyOrderId;
    
    /**
     * 매도 주문 ID (누가 판매했는지)
     */
    @Column(name = "sell_order_id", nullable = false)
    private Long sellOrderId;
    
    /**
     * 매수자 사용자 ID
     */
    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;
    
    /**
     * 매도자 사용자 ID
     */
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;
    
    /**
     * 기준 자산 (예: SOL)
     */
    @Column(name = "base_mint", nullable = false)
    private String baseMint;
    
    /**
     * 기준 통화 (예: USDT)
     */
    @Column(name = "quote_mint", nullable = false)
    private String quoteMint;
    
    /**
     * 체결 가격 (USDT 기준)
     * 예: 100.0은 1 SOL = 100 USDT를 의미
     */
    @Column(name = "price", nullable = false, precision = 30, scale = 9)
    private BigDecimal price;
    
    /**
     * 체결 수량 (기준 자산 기준)
     * 예: 1.5는 1.5 SOL이 거래되었음을 의미
     */
    @Column(name = "amount", nullable = false, precision = 30, scale = 9)
    private BigDecimal amount;
    
    /**
     * 체결 발생 시간
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
