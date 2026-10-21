package dustin.cex.domains.bot.service;

import dustin.cex.domains.bot.model.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicReference;

/** 외부 가격은 봇의 주문 가격 참고용이다. 거래소 호가·체결·차트 데이터로 내보내지 않는다. */
@Service @RequiredArgsConstructor
public class BinancePriceService {
    private final BotConfig config;
    private final BinanceWebSocketClient client;
    // 매수·매도 가격을 한 묶음으로 교체해 서로 다른 수신 시점의 호가가 섞이지 않게 한다.
    record Quote(BigDecimal bid,BigDecimal ask,long received,long sequence) {}
    private final AtomicReference<Quote> quote=new AtomicReference<>();
    @PostConstruct public void start() {
        if(config.getBotCount()==0) return;
        if(!java.net.URI.create(config.getBinanceWsUrl()).getPath().equals("/ws/btcusdt@depth20@100ms")) throw new IllegalArgumentException("봇 참조 스트림은 BTC 부분 호가여야 합니다");
        client.start(config.getBinanceWsUrl(),this::update);
    }
    @PreDestroy public void stop() {client.stop();}
    void update(BinanceOrderbookUpdate update) {
        var sides=BinanceWebSocketClient.parseOrderbookUpdate(update);
        if(sides[0].isEmpty() || sides[1].isEmpty()) return;
        BigDecimal bid=sides[0].getFirst().getPrice(),ask=sides[1].getFirst().getPrice();
        if(bid.signum()<=0 || ask.compareTo(bid)<0) return;
        long now=System.nanoTime();
        quote.updateAndGet(old->old!=null && old.sequence()>=update.getLastUpdateId()
            ?old:new Quote(bid,ask,now,update.getLastUpdateId()));
    }
    private Quote current() {
        Quote current=quote.get();
        // 임의의 150달러 기본값을 없앤다. 끊긴 외부 시세로 자동 주문을 계속하지 않는다.
        if(current==null || System.nanoTime()-current.received()>10_000_000_000L) throw new IllegalStateException("Fresh Binance reference quote unavailable");
        return current;
    }
    public BigDecimal getBestBid() {return current().bid();}
    public BigDecimal getBestAsk() {return current().ask();}
    public BigDecimal getMidPrice() {var q=current();return q.bid().add(q.ask()).divide(BigDecimal.valueOf(2),2,RoundingMode.HALF_UP);}
    public BigDecimal getMarketPrice(boolean buy) {var q=current();return buy?q.ask():q.bid();}
}
