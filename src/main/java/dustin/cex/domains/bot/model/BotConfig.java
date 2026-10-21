package dustin.cex.domains.bot.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 봇은 기본 0명이다. 미리 준비된 계정만 사용하고 거래 시장은 BTC/USDT로 고정한다. */
@Data @Configuration @ConfigurationProperties(prefix="bot")
public class BotConfig {
    private Integer botCount=0;
    private String binanceWsUrl="wss://stream.binance.com:9443/ws/btcusdt@depth20@100ms";
    public String getBotBaseMint(Long botId) {return "BTC";}
    public BotTradingFrequency getBotFrequency(Long botId) {
        return botId<=4?BotTradingFrequency.HIGH:botId<=16?BotTradingFrequency.MEDIUM:BotTradingFrequency.LOW;
    }
    public boolean isBuyBot(Long botId) {return botId%2==1;}
}
