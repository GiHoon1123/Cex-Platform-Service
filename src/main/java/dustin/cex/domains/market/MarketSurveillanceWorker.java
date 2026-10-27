package dustin.cex.domains.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 짧은 실행마다 제한된 입력만 처리해 주문·정산용 디비 자원을 장시간 점유하지 않는다. */
@Component
public class MarketSurveillanceWorker {
    private final MarketSurveillanceService service;private final boolean enabled;
    public MarketSurveillanceWorker(MarketSurveillanceService service,@Value("${market.surveillance.enabled:true}") boolean enabled){this.service=service;this.enabled=enabled;}
    @Scheduled(fixedDelayString="${market.surveillance.interval-ms:1000}")
    public void drain(){if(!enabled)return;for(int count=0;count<100&&service.processNext().isPresent();count++);}
}
