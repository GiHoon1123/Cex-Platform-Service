package dustin.cex.domains.order.exception;

/**
 * 플랫폼에는 주문과 예약이 커밋됐지만 엔진 제출 결과를 확인하지 못한 상황이다.
 * 일반적인 생성 실패와 구분해 호출자가 새 주문으로 자동 재시도하지 않도록 한다.
 * orderId는 이미 저장된 기록을 조회하는 키이며, cause는 내부 진단용으로만 유지한다.
 */
public final class OrderSubmissionUncertainException extends RuntimeException {
    private final Long orderId;

    public OrderSubmissionUncertainException(Long orderId, Throwable cause) {
        super("엔진 제출 결과를 확인하지 못했습니다. 새 주문을 제출하지 말고 기존 주문을 조회하세요.", cause);
        this.orderId = orderId;
    }

    public Long getOrderId() {
        return orderId;
    }
}
