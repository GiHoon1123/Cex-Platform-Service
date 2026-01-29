package dustin.cex.domains.bot.model;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import lombok.Data;

/**
 * 봇 설정
 * Bot Configuration
 * 
 * 역할:
 * - 바이낸스 오더북을 동기화할 때 사용하는 설정값들
 * - 봇 계정 정보 (이메일, 비밀번호)
 * - 주문 수량, 오더북 깊이 등
 * - 봇별 자산 설정 (봇 ID -> baseMint 매핑)
 * 
 * 설정 방법:
 * - application.properties에서 설정
 * - 환경변수로 오버라이드 가능
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "bot")
public class BotConfig {
    
    /**
     * 봇 1 (매수 전용) 이메일
     * Bot 1 (Buy only) email
     */
    private String bot1Email = "bot1@bot.com";
    
    /**
     * 봇 1 비밀번호
     * Bot 1 password
     */
    private String bot1Password = "123123";
    
    /**
     * 봇 2 (매도 전용) 이메일
     * Bot 2 (Sell only) email
     */
    private String bot2Email = "bot2@bot.com";
    
    /**
     * 봇 2 비밀번호
     * Bot 2 password
     */
    private String bot2Password = "123123";
    
    /**
     * 생성할 봇 수
     * Number of bots to create
     * 
     * 기본값: 20명 (현재는 모두 SOL 사용)
     */
    private Integer botCount = 20;
    
    /**
     * 주문 수량 (고정)
     * Fixed order quantity
     * 
     * 바이낸스 오더북의 각 호가에 대해 이 수량으로 주문을 생성합니다.
     * 예: 1.0 SOL, 10.0 SOL 등
     */
    private BigDecimal orderQuantity = BigDecimal.valueOf(1.0);
    
    /**
     * 오더북 깊이 (상위 N개)
     * Orderbook depth (top N entries)
     * 
     * 바이낸스에서 받아올 오더북의 상위 N개 호가
     * 예: 50개면 상위 50개 매수/매도 호가만 동기화
     */
    private Integer orderbookDepth = 200;
    
    /**
     * 바이낸스 WebSocket URL
     * Binance WebSocket URL
     */
    private String binanceWsUrl = "wss://stream.binance.com:9443/ws/solusdt@depth20@100ms";
    
    /**
     * 바이낸스 심볼
     * Binance symbol (e.g., "SOLUSDT")
     */
    private String binanceSymbol = "SOLUSDT";
    
    /**
     * 봇별 자산 매핑 (봇 ID -> baseMint)
     * Bot asset mapping (bot ID -> baseMint)
     * 
     * 예: {1L: "SOL", 2L: "SOL", 3L: "ETH", ...}
     * 현재는 모든 봇이 SOL 사용 (동적으로 설정 가능)
     */
    private final Map<Long, String> botAssetMap = new HashMap<>();
    
    /**
     * 봇별 거래 빈도 매핑 (봇 ID -> 빈도)
     * Bot trading frequency mapping (bot ID -> frequency)
     * 
     * 예: {1L: HIGH, 2L: HIGH, 5L: MEDIUM, ...}
     */
    private final Map<Long, BotTradingFrequency> botFrequencyMap = new HashMap<>();
    
    /**
     * 봇별 자산 매핑 초기화
     * Initialize bot asset mapping
     * 
     * 서버 시작 시 모든 봇의 자산을 기본값(SOL)으로 설정
     */
    @PostConstruct
    public void initializeBotAssetMap() {
        // 모든 봇을 SOL로 초기화 (나중에 개별 설정 가능)
        for (long i = 1; i <= botCount; i++) {
            botAssetMap.put(i, "SOL");
        }
        
        // 봇별 거래 빈도 초기화
        initializeBotFrequencyMap();
    }
    
    /**
     * 봇별 거래 빈도 초기화
     * Initialize bot trading frequency mapping
     * 
     * 빈도 할당:
     * - 고빈도 4명: 1, 2, 3, 4
     * - 중빈도 12명: 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
     * - 저빈도 4명: 17, 18, 19, 20
     */
    private void initializeBotFrequencyMap() {
        // 고빈도 4명
        for (long i = 1; i <= 4; i++) {
            botFrequencyMap.put(i, BotTradingFrequency.HIGH);
        }
        
        // 중빈도 12명
        for (long i = 5; i <= 16; i++) {
            botFrequencyMap.put(i, BotTradingFrequency.MEDIUM);
        }
        
        // 저빈도 4명
        for (long i = 17; i <= 20; i++) {
            botFrequencyMap.put(i, BotTradingFrequency.LOW);
        }
    }
    
    /**
     * 봇의 baseMint 가져오기
     * Get bot's baseMint
     * 
     * @param botId 봇 ID (1부터 시작)
     * @return baseMint (예: "SOL", "ETH")
     */
    public String getBotBaseMint(Long botId) {
        return botAssetMap.getOrDefault(botId, "SOL"); // 기본값: SOL
    }
    
    /**
     * 봇의 baseMint 설정
     * Set bot's baseMint
     * 
     * @param botId 봇 ID
     * @param baseMint baseMint (예: "SOL", "ETH")
     */
    public void setBotBaseMint(Long botId, String baseMint) {
        botAssetMap.put(botId, baseMint);
    }
    
    /**
     * 봇의 거래 빈도 가져오기
     * Get bot's trading frequency
     * 
     * @param botId 봇 ID
     * @return 거래 빈도 (기본값: MEDIUM)
     */
    public BotTradingFrequency getBotFrequency(Long botId) {
        return botFrequencyMap.getOrDefault(botId, BotTradingFrequency.MEDIUM);
    }
    
    /**
     * 봇이 매수 봇인지 확인
     * Check if bot is a buy bot
     * 
     * 매수 봇: 홀수 ID (1, 3, 5, 7, 9, 11, 13, 15, 17, 19)
     * 
     * @param botId 봇 ID
     * @return 매수 봇이면 true
     */
    public boolean isBuyBot(Long botId) {
        return botId % 2 == 1;
    }
    
    /**
     * 봇이 매도 봇인지 확인
     * Check if bot is a sell bot
     * 
     * 매도 봇: 짝수 ID (2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
     * 
     * @param botId 봇 ID
     * @return 매도 봇이면 true
     */
    public boolean isSellBot(Long botId) {
        return botId % 2 == 0;
    }
}
