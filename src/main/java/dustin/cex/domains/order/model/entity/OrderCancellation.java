package dustin.cex.domains.order.model.entity;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 취소할 경제적 원본과 재확인 일정을 보존한다. 이 행의 응답 확인은 주문 종료나 예약 해제가 아니다. */
@Entity
@Table(name="order_cancellations",indexes=@Index(name="idx_order_cancel_due",columnList="acknowledged_at,next_attempt_at,order_id"))
@Getter
@NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class OrderCancellation {
    @Id @Column(name="order_id",updatable=false) private Long orderId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="order_id",insertable=false,updatable=false) private Order order;
    @Column(name="user_id",nullable=false,updatable=false) private Long userId;
    @Column(name="base_mint",nullable=false,updatable=false) private String baseMint;
    @Column(name="quote_mint",nullable=false,updatable=false) private String quoteMint;
    @Column(name="requested_at",nullable=false,updatable=false) private Instant requestedAt;
    // 실제 변경 시각은 제품 DB 트리거가 다시 정한다. 지연 감사 제약이 이번 변경 이후의 감사와 결합하는 기준이다.
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @Column(nullable=false) private long attempts;
    @Column(name="next_attempt_at",nullable=false) private Instant nextAttemptAt;
    @Column(name="acknowledged_at") private Instant acknowledgedAt;

    /** 주문 상태의 변경 가능한 필드 대신 불변 접수 원본에서 소유자와 거래쌍을 고정한다. */
    public OrderCancellation(Order order,OrderSubmission original,Instant now){
        this.order=order;orderId=java.util.Objects.requireNonNull(order.getId());userId=original.getUserId();
        if(!orderId.equals(original.getOrderId())||!userId.equals(order.getUserId()))throw new IllegalArgumentException("취소 원본과 주문이 다릅니다");
        baseMint=original.getBaseMint();quoteMint=original.getQuoteMint();requestedAt=now;updatedAt=now;nextAttemptAt=now.plusSeconds(30);
    }
    @PreUpdate void updated(){updatedAt=Instant.now();}
    /** 즉시 요청과 워커의 중복 경쟁은 같은 행 잠금에서 정한다. 만료 뒤 중복 전달은 엔진의 영속 취소 멱등성이 처리한다. */
    public boolean claim(Instant now,boolean immediate){
        if(acknowledgedAt!=null||(!(immediate&&attempts==0)&&nextAttemptAt.isAfter(now)))return false;
        attempts=Math.incrementExact(attempts);nextAttemptAt=now.plusSeconds(30);return true;
    }
    /** 오래 걸린 이전 시도가 새 시도의 결과를 덮어쓰지 않는다. 응답 유실 때 원본/예약을 보존하고 같은 주문을 재확인한다. */
    public void finish(long attempt,boolean acknowledged,Instant now){
        if(acknowledgedAt!=null||attempt!=attempts)return;
        if(acknowledged)acknowledgedAt=now;
        else nextAttemptAt=now.plusSeconds(Math.min(60,1L<<Math.min(attempts,6)));
    }
}
