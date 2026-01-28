package dustin.cex.domains.bot.model.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import lombok.Data;

/**
 * 봇 주문 생성 요청 DTO
 * Create Bot Order Request DTO
 * 
 * 역할:
 * - 봇이 주문을 생성할 때 사용하는 요청 데이터
 * - OrderService의 CreateOrderRequest와 유사하지만 봇 전용
 */
@Data
@Schema(description = "봇 주문 생성 요청")
public class CreateBotOrderRequest {
    
    /**
     * 주문 유형
     * Order Type
     * 
     * - 'buy': 매수 주문
     * - 'sell': 매도 주문
     */
    @NotBlank(message = "주문 유형은 필수입니다 (buy 또는 sell)")
    @Pattern(regexp = "^(buy|sell)$", message = "주문 유형은 'buy' 또는 'sell'이어야 합니다")
    @Schema(description = "주문 유형: buy(매수) 또는 sell(매도)", example = "buy", required = true)
    private String orderType;
    
    /**
     * 주문 방식
     * Order Side
     * 
     * - 'limit': 지정가 주문
     * - 'market': 시장가 주문
     */
    @NotBlank(message = "주문 방식은 필수입니다 (limit 또는 market)")
    @Pattern(regexp = "^(limit|market)$", message = "주문 방식은 'limit' 또는 'market'이어야 합니다")
    @Schema(description = "주문 방식: limit(지정가) 또는 market(시장가)", example = "limit", required = true)
    private String orderSide;
    
    /**
     * 기준 자산
     * Base Asset
     */
    @NotBlank(message = "기준 자산은 필수입니다")
    @Schema(description = "기준 자산 (예: SOL)", example = "SOL", required = true)
    private String baseMint;
    
    /**
     * 기준 통화
     * Quote Currency
     */
    @Schema(description = "기준 통화 (기본값: USDT)", example = "USDT", defaultValue = "USDT")
    private String quoteMint;
    
    /**
     * 지정가 가격
     * Limit Price
     * 
     * 지정가 주문 시 필수
     */
    @Schema(description = "지정가 가격 (USDT 기준, 지정가 주문만 필수)", example = "100.0")
    private BigDecimal price;
    
    /**
     * 주문 수량
     * Order Amount
     */
    @Schema(description = "주문 수량 (기준 자산 기준, 시장가 매수는 불필요)", example = "1.0")
    private BigDecimal amount;
    
    /**
     * 금액 기반 주문
     * Quote Amount
     * 
     * 시장가 매수 주문 시 필수
     */
    @Schema(description = "금액 기반 주문 (USDT 기준, 시장가 매수만 필수)", example = "1000.0")
    private BigDecimal quoteAmount;
}
