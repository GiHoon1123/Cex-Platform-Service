package dustin.cex.domains.order.model.entity;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문·불변 원본·예약과 같은 트랜잭션에 저장하는 전송 대기 기록이다.
 * 원본을 복사하거나 새 주문 키를 만들지 않는다. 확인은 엔진 저널 접수 또는 검증한 거절 결과만 가능하다.
 */
@Entity
@Table(name="order_dispatches", indexes=@Index(name="idx_order_dispatch_due",columnList="confirmed_at,next_attempt_at,order_id"))
@Getter
@NoArgsConstructor(access=lombok.AccessLevel.PROTECTED)
public class OrderDispatch {
    @Id @Column(name="order_id")
    private Long orderId;
    // 주문과 같은 기본 키가 일대일 수량을 보장하며 외래 키가 고아 기록을 막는다.
    // 연관 관계는 읽기 전용 참조다. 이미 저장한 주문을 다시 영속화하지 않는다.
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="order_id",insertable=false,updatable=false)
    private Order order;
    @Column(nullable=false)
    private long attempts;
    @Column(name="next_attempt_at",nullable=false)
    private Instant nextAttemptAt;
    @Column(name="confirmed_at")
    private Instant confirmedAt;

    public OrderDispatch(Order order) {
        this.order=order;
        this.orderId=java.util.Objects.requireNonNull(order.getId(),"저장된 주문 식별자가 필요합니다");
        // 정상 요청의 즉시 전송과 복구 워커가 경쟁하지 않게 최초 시도 창을 둔다.
        // 플랫폼이 전송 전에 종료되면 이 창이 지난 뒤 저장 원본으로 자동 복구한다.
        this.nextAttemptAt=Instant.now().plusSeconds(30);
    }
    public boolean claim(Instant now,boolean immediate) {
        if(confirmedAt!=null || (!(immediate && attempts==0) && nextAttemptAt.isAfter(now))) return false;
        attempts=Math.incrementExact(attempts);
        // 네트워크 동안 디비 락을 유지하지 않는다. 만료 후 중복 전달 가능성은 엔진의 영속 중복 방지가 처리한다.
        nextAttemptAt=now.plusSeconds(30);
        return true;
    }
    public void finish(long attempt,boolean acknowledged,Instant now) {
        // 만료한 시도의 늦은 응답이 새 시도나 Kafka 거절 확정을 덮어쓰지 않게 한다.
        if(confirmedAt!=null || attempt!=attempts) return;
        if(acknowledged) confirm(now);
        else nextAttemptAt=now.plusSeconds(Math.min(60,1L<<Math.min(attempts,6)));
    }
    public void confirm(Instant now) { if(confirmedAt==null) confirmedAt=now; }
}
