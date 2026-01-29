package dustin.cex.domains.position.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 포지션 응답 DTO
 * Position Response DTO
 * 
 * 역할:
 * - 포지션 조회 API의 응답 데이터
 * - 사용자의 특정 자산에 대한 포지션 정보 (평균 매수가, 손익, 수익률 등)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "자산 포지션 정보")
public class PositionResponse {
    
    /**
     * 자산 식별자
     */
    @Schema(
        description = "자산 식별자 (예: SOL, USDC)",
        example = "SOL",
        required = true
    )
    private String mint;
    
    /**
     * 현재 보유 수량 (available + locked)
     */
    @Schema(
        description = "현재 보유 수량 (사용 가능 + 잠긴 잔고)",
        example = "11.0",
        required = true
    )
    private BigDecimal currentBalance;
    
    /**
     * 사용 가능 잔고
     */
    @Schema(
        description = "사용 가능 잔고",
        example = "10.0",
        required = true
    )
    private BigDecimal available;
    
    /**
     * 잠긴 잔고
     */
    @Schema(
        description = "잠긴 잔고 (주문에 사용 중)",
        example = "1.0",
        required = true
    )
    private BigDecimal locked;
    
    /**
     * 평균 매수가
     */
    @Schema(
        description = "평균 매수가 (모든 매수 체결의 가중 평균, USDT 기준)",
        example = "100.5",
        required = false
    )
    private BigDecimal averageEntryPrice;
    
    /**
     * 현재 시장 가격
     */
    @Schema(
        description = "현재 시장 가격 (최근 체결가, USDT 기준)",
        example = "110.0",
        required = false
    )
    private BigDecimal currentMarketPrice;
    
    /**
     * 현재 평가액
     */
    @Schema(
        description = "현재 평가액 (현재 시장 가격 × 현재 보유 수량, USDT)",
        example = "1210.0",
        required = false
    )
    private BigDecimal currentValue;
    
    /**
     * 미실현 손익
     */
    @Schema(
        description = "미실현 손익 (현재 평가액 - 총 매수 금액, USDT). 양수: 수익, 음수: 손실",
        example = "100.0",
        required = false
    )
    private BigDecimal unrealizedPnl;
    
    /**
     * 미실현 수익률 (%)
     */
    @Schema(
        description = "미실현 수익률 (%, (미실현 손익 / 총 매수 금액) × 100)",
        example = "10.0",
        required = false
    )
    private BigDecimal unrealizedPnlPercent;
    
    /**
     * 거래 요약 정보
     */
    @Schema(description = "거래 요약 정보")
    private TradeSummary tradeSummary;
    
    /**
     * 거래 요약 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "거래 요약 정보")
    public static class TradeSummary {
        
        /**
         * 총 매수 횟수
         */
        @Schema(
            description = "총 매수 횟수",
            example = "5",
            required = true
        )
        private Long totalBuyTrades;
        
        /**
         * 총 매도 횟수
         */
        @Schema(
            description = "총 매도 횟수",
            example = "2",
            required = true
        )
        private Long totalSellTrades;
        
        /**
         * 실현 손익
         */
        @Schema(
            description = "실현 손익 (매도로 인한 손익, USDT). 양수: 수익, 음수: 손실",
            example = "50.0",
            required = true
        )
        private BigDecimal realizedPnl;
    }
}
