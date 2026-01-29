package dustin.cex.domains.balance.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 잔고 응답 DTO
 * Balance Response DTO
 * 
 * 역할:
 * - 잔고 조회 API의 응답 데이터
 * - 사용자의 자산별 잔고 정보
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "잔고 정보")
public class BalanceResponse {
    
    /**
     * 사용자 ID
     */
    @Schema(
        description = "사용자 ID",
        example = "1",
        required = true
    )
    private Long userId;
    
    /**
     * 자산 종류
     */
    @Schema(
        description = "자산 종류 (예: SOL, USDT)",
        example = "SOL",
        required = true
    )
    private String mintAddress;
    
    /**
     * 사용 가능 잔고
     */
    @Schema(
        description = "사용 가능 잔고 (주문에 사용 가능한 잔고)",
        example = "1000.0",
        required = true
    )
    private BigDecimal available;
    
    /**
     * 잠긴 잔고
     */
    @Schema(
        description = "잠긴 잔고 (주문에 사용 중인 잔고)",
        example = "100.0",
        required = true
    )
    private BigDecimal locked;
    
    /**
     * 총 잔고 (available + locked)
     */
    @Schema(
        description = "총 잔고 (사용 가능 + 잠긴 잔고)",
        example = "1100.0",
        required = true
    )
    private BigDecimal total;
}
