package dustin.cex.config;

import dustin.cex.domains.bot.model.BotConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;

/**
 * 테스트용 설정 클래스
 * Test Configuration
 * 
 * 역할:
 * - 테스트 환경에서 BotConfig를 명시적으로 생성하여 설정 바인딩 문제 방지
 */
@TestConfiguration
public class TestConfig {
    
    @Bean
    @Primary
    public BotConfig testBotConfig() {
        BotConfig config = new BotConfig();
        config.setBot1Email("bot1@bot.com");
        config.setBot1Password("botpassword");
        config.setBot2Email("bot2@bot.com");
        config.setBot2Password("botpassword");
        config.setBinanceWsUrl("wss://test");
        config.setBinanceSymbol("SOLUSDT");
        config.setOrderbookDepth(200);
        config.setOrderQuantity(BigDecimal.valueOf(1.0));
        return config;
    }
}
