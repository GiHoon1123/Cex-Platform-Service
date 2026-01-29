package dustin.cex.domains.order.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 오더북 응답 DTO
 * Orderbook Response DTO
 * 
 * 역할:
 * - 오더북 조회 API의 응답 데이터
 * - 매수 호가(bids)와 매도 호가(asks) 목록을 포함
 * 
 * 사용 예시:
 * - GET /api/cex/orderbook?baseMint=SOL&quoteMint=USDT&depth=20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "오더북 응답")
public class OrderbookResponse {
    
    /**
     * 매수 호가 목록 (가격 내림차순, 높은 가격부터)
     * Buy orders (price descending, highest first)
     * 
     * 예: [100.5, 100.0, 99.5, ...]
     */
    @Schema(
        description = "매수 호가 목록 (가격 내림차순, 높은 가격부터)",
        example = "[\n" +
                  "  {\n" +
                  "    \"id\": \"1850278129743992082\",\n" +
                  "    \"userId\": 1,\n" +
                  "    \"orderType\": \"buy\",\n" +
                  "    \"orderSide\": \"limit\",\n" +
                  "    \"baseMint\": \"SOL\",\n" +
                  "    \"quoteMint\": \"USDT\",\n" +
                  "    \"price\": 100.5,\n" +
                  "    \"amount\": 1.0,\n" +
                  "    \"filledAmount\": 0.0,\n" +
                  "    \"filledQuoteAmount\": 0.0,\n" +
                  "    \"status\": \"pending\",\n" +
                  "    \"createdAt\": \"2026-01-29T10:30:00\",\n" +
                  "    \"updatedAt\": \"2026-01-29T10:30:00\"\n" +
                  "  }\n" +
                  "]",
        required = true
    )
    private List<OrderResponse.OrderDto> bids;
    
    /**
     * 매도 호가 목록 (가격 오름차순, 낮은 가격부터)
     * Sell orders (price ascending, lowest first)
     * 
     * 예: [101.0, 101.5, 102.0, ...]
     */
    @Schema(
        description = "매도 호가 목록 (가격 오름차순, 낮은 가격부터)",
        example = "[\n" +
                  "  {\n" +
                  "    \"id\": \"1850278129743992083\",\n" +
                  "    \"userId\": 2,\n" +
                  "    \"orderType\": \"sell\",\n" +
                  "    \"orderSide\": \"limit\",\n" +
                  "    \"baseMint\": \"SOL\",\n" +
                  "    \"quoteMint\": \"USDT\",\n" +
                  "    \"price\": 101.0,\n" +
                  "    \"amount\": 1.0,\n" +
                  "    \"filledAmount\": 0.0,\n" +
                  "    \"filledQuoteAmount\": 0.0,\n" +
                  "    \"status\": \"pending\",\n" +
                  "    \"createdAt\": \"2026-01-29T10:31:00\",\n" +
                  "    \"updatedAt\": \"2026-01-29T10:31:00\"\n" +
                  "  }\n" +
                  "]",
        required = true
    )
    private List<OrderResponse.OrderDto> asks;
}
