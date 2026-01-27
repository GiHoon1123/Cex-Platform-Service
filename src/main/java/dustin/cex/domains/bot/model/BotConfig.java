package dustin.cex.domains.bot.model;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * 봇 설정
 * Bot Configuration
 * 
 * 역할:
 * - 바이낸스 오더북을 동기화할 때 사용하는 설정값들
 * - 봇 계정 정보 (이메일, 비밀번호)
 * - 주문 수량, 오더북 깊이 등
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
}
