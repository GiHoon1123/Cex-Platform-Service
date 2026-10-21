package dustin.cex.domains.order.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 결과 불명 응답은 생성 실패·체결 완료와 구분한다. 기존 주문을 찾을 수 있는 정보만
 * 제공하고 예외 원인·내부 엔진 주소는 공개하지 않는다. Java record는 불변 DTO이며
 * Jackson이 각 구성 요소를 JSON 필드로 직렬화한다. JS 정밀도 보존을 위해 ID는 문자열이다.
 */
@Schema(description = "플랫폼 저장 완료, 엔진 제출 결과 불명. 새 주문 제출 대신 기존 주문 조회")
public record OrderSubmissionUncertainResponse(
        String code,
        String orderId,
        String statusUrl,
        String message
) {}
