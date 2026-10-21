package dustin.cex.domains.order.model.dto;

/** HTTP 계층이 신규 생성(201)과 기존 기록 조회(200)를 구분하는 서비스 결과다. */
public record OrderCreationResult(OrderResponse response, boolean created) {}
