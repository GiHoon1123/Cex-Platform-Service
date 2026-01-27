package dustin.cex.shared.kafka.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 주문 취소 이벤트
 * Order Cancelled Event
 * 
 * Rust 엔진에서 Kafka로 발행하는 주문 취소 이벤트
 * Java Consumer가 이를 받아서 DB에 주문 상태를 업데이트합니다.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelledEvent {
    /**
     * 취소된 주문 ID
     */
    @JsonProperty("order_id")
    private Long orderId;
    
    /**
     * 주문한 사용자 ID
     */
    @JsonProperty("user_id")
    private Long userId;
    
    /**
     * 기준 자산 (예: "SOL")
     */
    @JsonProperty("base_mint")
    private String baseMint;
    
    /**
     * 기준 통화 (예: "USDT")
     */
    @JsonProperty("quote_mint")
    private String quoteMint;
    
    /**
     * 취소 시간
     */
    @JsonProperty("timestamp")
    private LocalDateTime timestamp;
}
