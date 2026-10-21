package dustin.cex.domains.order.exception;

/** 같은 고객이 같은 요청 키를 다른 주문에 재사용했다. 원본을 수정하거나 재전송하지 않는다. */
public final class OrderRequestConflictException extends RuntimeException {
    public OrderRequestConflictException() {
        super("같은 Idempotency-Key에 다른 주문 내용을 사용할 수 없습니다");
    }
}
