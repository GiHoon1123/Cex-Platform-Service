package dustin.cex.domains.order.model.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import dustin.cex.domains.order.model.OrderCommand;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 접수 의도를 보존하는 불변 기록이다. orders는 체결에 따라 수량·상태가 바뀔 수
 * 있으므로 원본 요청 비교 근거로 쓰지 않는다. 신규 접수만 이 별도 테이블에 기록해
 * 과거 금융 이력에 가짜 요청 키를 채우거나 nullable 호환 경로를 만들지 않는다.
 *
 * 고객별 요청 키와 주문 ID를 각각 유일하게 유지한다. 모든 필드는 updatable=false이며
 * 제품 경로에는 변경·삭제 API가 없다. DB 관리자까지 통제하는 감사 보장은 별도 과제다.
 */
@Entity
@Table(name = "order_submissions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_submission_user_request", columnNames = {"user_id", "request_id"}),
        @UniqueConstraint(name = "uq_submission_order", columnNames = "order_id")
})
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class OrderSubmission {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(nullable = false, updatable = false, length = 20)
    private String orderType;
    @Column(nullable = false, updatable = false, length = 20)
    private String orderSide;
    @Column(nullable = false, updatable = false, length = 255)
    private String baseMint;
    @Column(nullable = false, updatable = false, length = 255)
    private String quoteMint;
    @Column(updatable = false, precision = 30, scale = 9)
    private BigDecimal price;
    @Column(updatable = false, precision = 30, scale = 9)
    private BigDecimal amount;
    @Column(updatable = false, precision = 30, scale = 9)
    private BigDecimal quoteAmount;

    /** 접수 당시 적용률이다. 정책 변경이나 재요청이 기존 주문의 비용을 바꾸지 않는다. */
    @Column(nullable = false, updatable = false, precision = 10, scale = 6)
    private BigDecimal feeRate;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public OrderSubmission(Long userId, UUID requestId, Long orderId, OrderCommand command, BigDecimal feeRate) {
        this.feeRate = dustin.cex.domains.fee.service.FeeConfigService.requireRate(feeRate);
        this.userId = userId;
        this.requestId = requestId;
        this.orderId = orderId;
        this.orderType = command.orderType();
        this.orderSide = command.orderSide();
        this.baseMint = command.baseMint();
        this.quoteMint = command.quoteMint();
        this.price = command.price();
        this.amount = command.amount();
        this.quoteAmount = command.quoteAmount();
    }

    /** DB의 고정 scale을 다시 정규화해 100과 100.00이 같은 요청인지 비교할 수 있다. */
    public OrderCommand command() {
        return new OrderCommand(orderType, orderSide, baseMint, quoteMint, price, amount, quoteAmount);
    }
}
