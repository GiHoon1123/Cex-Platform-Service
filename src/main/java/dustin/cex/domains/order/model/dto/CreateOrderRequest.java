package dustin.cex.domains.order.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 주문 생성 요청 DTO
 * Create Order Request DTO
 * 
 * 역할:
 * - 사용자가 주문 생성 API를 호출할 때 전달하는 요청 데이터
 * - 주문 유형, 가격, 수량 등의 정보를 포함
 * 
 * 주문 타입 조합:
 * 1. 지정가 매수: orderType='buy', orderSide='limit', price 필수, amount 필수
 * 2. 시장가 매수: orderType='buy', orderSide='market', quoteAmount 필수
 * 3. 지정가 매도: orderType='sell', orderSide='limit', price 필수, amount 필수
 * 4. 시장가 매도: orderType='sell', orderSide='market', amount 필수
 * 
 * 예시:
 * - 지정가 매수: "SOL을 100 USDT에 1개 구매"
 *   → orderType='buy', orderSide='limit', price=100.0, amount=1.0
 * - 시장가 매수: "1000 USDT어치 SOL 구매"
 *   → orderType='buy', orderSide='market', quoteAmount=1000.0
 * - 시장가 매도: "SOL 1개 즉시 판매"
 *   → orderType='sell', orderSide='market', amount=1.0
 */
@Data
@Schema(description = "주문 생성 요청")
public class CreateOrderRequest {
    
    /**
     * 주문 유형
     * Order Type
     * 
     * - 'buy': 매수 주문 (USDT로 다른 자산 구매)
     * - 'sell': 매도 주문 (보유 자산을 USDT로 판매)
     * 
     * 필수값: true
     */
    @NotBlank(message = "주문 유형은 필수입니다 (buy 또는 sell)")
    @Pattern(regexp = "^(buy|sell)$", message = "주문 유형은 'buy' 또는 'sell'이어야 합니다")
    @Schema(description = "주문 유형: buy(매수) 또는 sell(매도)", example = "buy", required = true)
    private String orderType;
    
    /**
     * 주문 방식
     * Order Side
     * 
     * - 'limit': 지정가 주문 (원하는 가격에 주문 등록, 매칭될 때까지 대기)
     * - 'market': 시장가 주문 (즉시 체결, 오더북의 최적 가격으로 매칭)
     * 
     * 필수값: true
     */
    @NotBlank(message = "주문 방식은 필수입니다 (limit 또는 market)")
    @Pattern(regexp = "^(limit|market)$", message = "주문 방식은 'limit' 또는 'market'이어야 합니다")
    @Schema(description = "주문 방식: limit(지정가) 또는 market(시장가)", example = "limit", required = true)
    private String orderSide;
    
    /**
     * 기준 자산
     * Base Asset
     * 
     * 구매/판매하려는 자산 (예: SOL, USDC, RAY 등)
     * 예: SOL/USDT 거래 → baseMint='SOL'
     * 
     * 필수값: true
     */
    @NotBlank(message = "기준 자산은 필수입니다")
    @Schema(description = "기준 자산 (예: SOL, USDC)", example = "SOL", required = true)
    private String baseMint;
    
    /**
     * 기준 통화
     * Quote Currency
     * 
     * 항상 'USDT'가 기준 통화
     * 선택값이지만, 제공하지 않으면 기본값 'USDT' 사용
     */
    @Schema(description = "기준 통화 (기본값: USDT)", example = "USDT", defaultValue = "USDT")
    private String quoteMint;
    
    /**
     * 지정가 가격
     * Limit Price
     * 
     * 지정가 주문 시 필수, 시장가 주문은 불필요
     * USDT 기준 가격 (1 SOL = 100 USDT 라면 price=100.0)
     * 
     * 유효성 검증:
     * - 지정가 주문: 필수, 양수여야 함
     * - 시장가 주문: 불필요 (null 허용)
     */
    @Schema(description = "지정가 가격 (USDT 기준, 지정가 주문만 필수)", example = "100.0")
    private BigDecimal price;
    
    /**
     * 주문 수량
     * Order Amount
     * 
     * 주문한 수량 (baseMint 기준)
     * 소수점 값 지원 (예: 0.1 SOL, 0.0001 SOL)
     * 
     * 규칙:
     * - 지정가 매수: 필수
     * - 시장가 매수: 불필요 (quoteAmount 사용)
     * - 모든 매도: 필수
     * 
     * 유효성 검증:
     * - 양수여야 함
     * - 소수점 9자리까지 지원
     */
    @Schema(description = "주문 수량 (기준 자산 기준, 시장가 매수는 불필요)", example = "1.0")
    private BigDecimal amount;
    
    /**
     * 금액 기반 주문 (시장가 매수만)
     * Quote Amount (for market buy orders only)
     * 
     * 시장가 매수 주문 시 사용
     * 예: "1000 USDT어치 SOL 사기"
     * 
     * 규칙:
     * - 시장가 매수: 필수 (amount 대신 사용)
     * - 지정가 매수: 불필요
     * - 모든 매도: 불필요
     * 
     * 유효성 검증:
     * - 양수여야 함
     * - 시장가 매수일 때만 필수
     */
    @Schema(description = "금액 기반 주문 (USDT 기준, 시장가 매수만 필수)", example = "1000.0")
    private BigDecimal quoteAmount;
}
