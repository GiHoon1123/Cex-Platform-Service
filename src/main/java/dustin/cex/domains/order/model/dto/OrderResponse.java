package dustin.cex.domains.order.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 주문 응답 DTO
 * Order Response DTO
 * 
 * 역할:
 * - 주문 생성/조회 API의 응답 데이터
 * - 주문 정보와 성공 메시지를 포함
 * 
 * 사용 예시:
 * - 주문 생성 성공 시: order 정보 + "Order created successfully" 메시지
 * - 주문 조회 시: order 정보만 반환 (메시지는 선택적)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "주문 응답")
public class OrderResponse {
    
    /**
     * 주문 정보
     * Order Information
     * 
     * 생성된 또는 조회된 주문의 상세 정보
     */
    @Schema(description = "주문 정보")
    private OrderDto order;
    
    /**
     * 성공 메시지
     * Success Message
     * 
     * 주문 생성 성공 시: "Order created successfully"
     * 주문 취소 성공 시: "Order cancelled successfully"
     * 주문 조회 시: null 또는 생략 가능
     */
    @Schema(description = "성공 메시지", example = "Order created successfully")
    private String message;
    
    /**
     * 주문 정보 DTO
     * Order Information DTO
     * 
     * 주문의 모든 필드를 포함하는 내부 클래스
     * API 응답 시 사용
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "주문 상세 정보")
    public static class OrderDto {
        
        /**
         * 주문 고유 ID
         * Order ID
         * 
         * 데이터베이스에서 자동 생성되는 값
         * JavaScript 정밀도 손실 방지를 위해 문자열로 직렬화
         */
        @Schema(description = "주문 ID", example = "1850278129743992082")
        private String id;
        
        /**
         * 주문한 사용자 ID
         * User ID
         */
        @Schema(description = "사용자 ID", example = "1")
        private Long userId;
        
        /**
         * 주문 유형
         * Order Type
         * 
         * - 'buy': 매수
         * - 'sell': 매도
         */
        @Schema(description = "주문 유형: buy(매수) 또는 sell(매도)", example = "buy")
        private String orderType;
        
        /**
         * 주문 방식
         * Order Side
         * 
         * - 'limit': 지정가
         * - 'market': 시장가
         */
        @Schema(description = "주문 방식: limit(지정가) 또는 market(시장가)", example = "limit")
        private String orderSide;
        
        /**
         * 기준 자산
         * Base Asset
         */
        @Schema(description = "기준 자산", example = "SOL")
        private String baseMint;
        
        /**
         * 기준 통화
         * Quote Currency
         */
        @Schema(description = "기준 통화", example = "USDT")
        private String quoteMint;
        
        /**
         * 지정가 가격
         * Limit Price
         * 
         * 지정가 주문만 값이 있음, 시장가 주문은 null
         */
        @Schema(description = "지정가 가격 (USDT 기준, 시장가 주문은 null)", example = "100.0")
        private BigDecimal price;
        
        /**
         * 주문 수량
         * Order Amount
         */
        @Schema(description = "주문 수량", example = "1.0")
        private BigDecimal amount;
        
        /**
         * 체결된 수량
         * Filled Amount
         */
        @Schema(description = "체결된 수량", example = "0.0")
        private BigDecimal filledAmount;
        
        /**
         * 체결된 금액 (USDT 기준)
         * Filled Quote Amount
         */
        @Schema(description = "체결된 금액 (USDT 기준)", example = "0.0")
        private BigDecimal filledQuoteAmount;
        
        /**
         * 주문 상태
         * Order Status
         * 
         * - 'pending': 대기 중
         * - 'partial': 부분 체결
         * - 'filled': 전량 체결
         * - 'cancelled': 취소됨
         */
        @Schema(description = "주문 상태", example = "pending")
        private String status;
        
        /**
         * 주문 생성 시간
         * Created Timestamp
         */
        @Schema(description = "주문 생성 시간")
        private LocalDateTime createdAt;
        
        /**
         * 주문 정보 마지막 업데이트 시간
         * Updated Timestamp
         */
        @Schema(description = "주문 정보 마지막 업데이트 시간")
        private LocalDateTime updatedAt;
    }
}
