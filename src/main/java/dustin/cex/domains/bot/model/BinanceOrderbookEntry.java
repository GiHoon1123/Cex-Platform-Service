package dustin.cex.domains.bot.model;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 바이낸스 오더북 엔트리
 * Binance Orderbook Entry
 * 
 * 역할:
 * - 파싱된 오더북 항목 (가격, 수량)
 * - 바이낸스 오더북 업데이트에서 추출한 정보
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BinanceOrderbookEntry {
    
    /**
     * 가격
     * Price
     */
    private BigDecimal price;
    
    /**
     * 수량
     * Quantity
     */
    private BigDecimal quantity;
}
