package dustin.cex.domains.bot.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 봇 거래 빈도
 * Bot Trading Frequency
 * 
 * 봇의 거래 빈도를 정의합니다.
 */
@Getter
@RequiredArgsConstructor
public enum BotTradingFrequency {
    /**
     * 고빈도: 1-2초 간격
     * High frequency: 1-2 seconds interval
     */
    HIGH(1000, 2000),
    
    /**
     * 중빈도: 5-10초 간격
     * Medium frequency: 5-10 seconds interval
     */
    MEDIUM(5000, 10000),
    
    /**
     * 저빈도: 30-60초 간격
     * Low frequency: 30-60 seconds interval
     */
    LOW(30000, 60000);
    
    /**
     * 최소 간격 (밀리초)
     * Minimum interval (milliseconds)
     */
    private final int minIntervalMs;
    
    /**
     * 최대 간격 (밀리초)
     * Maximum interval (milliseconds)
     */
    private final int maxIntervalMs;
    
    /**
     * 랜덤 간격 생성
     * Generate random interval
     * 
     * @return minIntervalMs ~ maxIntervalMs 사이의 랜덤 값
     */
    public int getRandomInterval() {
        return minIntervalMs + (int)(Math.random() * (maxIntervalMs - minIntervalMs));
    }
}
