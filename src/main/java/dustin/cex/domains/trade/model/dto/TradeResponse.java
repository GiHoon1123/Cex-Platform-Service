package dustin.cex.domains.trade.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 체결 내역 응답 DTO
 * Trade Response DTO
 * 
 * 역할:
 * - 체결 내역 조회 API의 응답 데이터
 * - 두 주문이 매칭되어 발생한 거래 내역 정보
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "체결 내역 정보")
public class TradeResponse {
    
    /**
     * 체결 내역 고유 ID
     */
    @Schema(
        description = "체결 내역 고유 ID",
        example = "456",
        required = true
    )
    private Long id;
    
    /**
     * 매수 주문 ID
     */
    @Schema(
        description = "매수 주문 ID (구매한 주문)",
        example = "123",
        required = true
    )
    private Long buyOrderId;
    
    /**
     * 매도 주문 ID
     */
    @Schema(
        description = "매도 주문 ID (판매한 주문)",
        example = "124",
        required = true
    )
    private Long sellOrderId;
    
    /**
     * 매수자 사용자 ID
     */
    @Schema(
        description = "매수자 사용자 ID",
        example = "1",
        required = true
    )
    private Long buyerId;
    
    /**
     * 매도자 사용자 ID
     */
    @Schema(
        description = "매도자 사용자 ID",
        example = "2",
        required = true
    )
    private Long sellerId;
    
    /**
     * 기준 자산
     */
    @Schema(
        description = "기준 자산 (거래된 자산, 예: SOL)",
        example = "SOL",
        required = true
    )
    private String baseMint;
    
    /**
     * 기준 통화
     */
    @Schema(
        description = "기준 통화 (항상 USDT)",
        example = "USDT",
        required = true
    )
    private String quoteMint;
    
    /**
     * 체결 가격 (USDT 기준)
     */
    @Schema(
        description = "체결 가격 (USDT 기준, 1 SOL = 100 USDT라면 100.0)",
        example = "100.5",
        required = true
    )
    private BigDecimal price;
    
    /**
     * 체결 수량 (기준 자산 기준)
     */
    @Schema(
        description = "체결 수량 (기준 자산 기준, 예: 1.5 SOL)",
        example = "1.0",
        required = true
    )
    private BigDecimal amount;
    
    /**
     * 체결 발생 시간
     */
    @Schema(
        description = "체결 발생 시간 (ISO 8601 형식)",
        example = "2026-01-29T10:30:00",
        required = true
    )
    private LocalDateTime createdAt;
}
