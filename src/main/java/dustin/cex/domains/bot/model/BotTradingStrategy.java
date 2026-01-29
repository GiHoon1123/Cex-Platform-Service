package dustin.cex.domains.bot.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 봇 거래 전략
 * Bot Trading Strategy
 * 
 * 봇의 거래 전략을 정의합니다.
 */
@Getter
@RequiredArgsConstructor
public enum BotTradingStrategy {
    /**
     * 시장가 주문
     * Market order
     * 
     * 즉시 체결되는 주문
     */
    MARKET(30),
    
    /**
     * 지정가 주문
     * Limit order
     * 
     * 특정 가격에 지정가 주문
     */
    LIMIT(50),
    
    /**
     * 스프레드 거래
     * Spread trading
     * 
     * 매수/매도 동시 주문으로 스프레드 수익 추구
     */
    SPREAD(20);
    
    /**
     * 선택 가중치 (%)
     * Selection weight (%)
     */
    private final int weight;
    
    /**
     * 랜덤 전략 선택
     * Select random strategy based on weights
     * 
     * @return 선택된 전략
     */
    public static BotTradingStrategy selectRandom() {
        int random = (int)(Math.random() * 100);
        
        if (random < MARKET.weight) {
            return MARKET;
        } else if (random < MARKET.weight + LIMIT.weight) {
            return LIMIT;
        } else {
            return SPREAD;
        }
    }
}
