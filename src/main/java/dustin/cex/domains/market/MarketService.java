package dustin.cex.domains.market;

import tools.jackson.databind.ObjectMapper;
import dustin.cex.shared.kafka.EngineEventInbox;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Kafka 수신·영속 호가·공개 체결을 한 트랜잭션에 반영한다. 엔진을 조회하지 않는다. */
@Service @RequiredArgsConstructor
public class MarketService {
    private final ObjectMapper mapper;
    private final EngineEventInbox inbox;
    private final MarketStateRepository states;
    private final MarketPrintRepository prints;

    @KafkaListener(topics="market-events",groupId="${spring.kafka.consumer.group-id:cex-consumer-group}-market",concurrency="1")
    @Transactional
    public void consume(String payload) throws Exception {
        MarketSnapshot event=mapper.readValue(payload,MarketSnapshot.class);
        validate(event);
        // 동일 식별자의 다른 본문은 건너뛰지 않는다. 영속 수신 검사와 시세 반영이
        // 함께 롤백되므로 디비 오류 뒤 다시 수신해도 체결 이력을 잃지 않는다.
        if (!inbox.claim(event.eventId(),event.engineId(),payload)) return;
        String pair=pair(event.baseMint(),event.quoteMint());
        MarketState old=states.findById(pair).orElse(null);
        long sequence=Long.parseLong(event.sequence());
        if (old!=null) {
            if (!old.getEngineId().equals(event.engineId())) throw new IllegalStateException("Market engine identity changed");
            // WAL 출력 워커가 시작 기준점을 다시 발행할 수 있다. 과거 스냅샷이
            // 최신 호가를 덮어쓰지 않도록 영속 순번을 비교한다.
            if (sequence==old.getSequence()) throw new IllegalStateException("Market state is missing its receipt");
            if (sequence>old.getSequence() && Long.parseLong(event.previousSequence())!=old.getSequence()) {
                throw new IllegalStateException("Market sequence gap; restore Kafka history before continuing");
            }
        }
        for (var print:event.trades()) prints.save(new MarketPrint(pair,print,event.timestamp()));
        // 재시작의 이전 기준점은 최신 호가를 덮어쓰지 않는다. 처음 구독한
        // 플랫폼에 뒤늦게 온 과거 체결은 수신 키와 함께 한 번만 보완한다.
        if(old==null || sequence>old.getSequence()) states.save(new MarketState(pair,event.engineId(),sequence,payload));
    }

    static void validate(MarketSnapshot event) {
        if (!"market_snapshot".equals(event.eventType())) throw new IllegalArgumentException("Invalid market event type");
        UUID.fromString(event.engineId());
        long sequence=Long.parseLong(event.sequence());
        long previous=Long.parseLong(event.previousSequence());
        if (sequence<=0 || previous<0 || previous>=sequence || !event.eventId().equals(event.engineId()+":"+sequence+":market")) throw new IllegalArgumentException("Invalid market sequence");
        pair(event.baseMint(),event.quoteMint());
        Objects.requireNonNull(event.timestamp());
        validateLevels(event.bids(),true); validateLevels(event.asks(),false);
        Set<String> ids=new HashSet<>();
        for (var print:event.trades()) {
            if (!print.id().matches(java.util.regex.Pattern.quote(event.engineId()+":"+sequence+":")+"[0-9]+") || !ids.add(print.id()) || !(print.side().equals("buy")||print.side().equals("sell"))) throw new IllegalArgumentException("Invalid public trade");
            positive(print.price());positive(print.amount());
        }
    }
    private static void validateLevels(List<List<String>> levels,boolean buy) {
        if(levels==null || levels.size()>20) throw new IllegalArgumentException("Invalid book depth");
        BigDecimal previous=null;
        for(var level:levels) {
            if(level.size()!=2) throw new IllegalArgumentException("Invalid price level");
            BigDecimal price=positive(level.get(0));
            // 호가 수량은 여러 주문의 합이다. 개별 계정 컬럼의 정수부 상한으로
            // 자르면 유효한 큰 호가가 거부되므로 합계의 부호·소수 정밀도만 검사한다.
            BigDecimal quantity=new BigDecimal(level.get(1));
            if(quantity.signum()<=0 || quantity.scale()>9) throw new IllegalArgumentException("Invalid aggregate quantity");
            if(previous!=null && (buy?previous.compareTo(price)<=0:previous.compareTo(price)>=0)) throw new IllegalArgumentException("Unsorted book");
            previous=price;
        }
    }
    private static BigDecimal positive(String value) {
        BigDecimal number=new BigDecimal(value);
        if(number.signum()<=0 || number.scale()>9 || number.precision()-number.scale()>21) throw new IllegalArgumentException("Invalid market amount");
        return number;
    }
    static String pair(String base,String quote) {
        if(base==null || quote==null || !base.matches("[A-Z0-9]{1,32}") || !quote.matches("[A-Z0-9]{1,32}") || base.equals(quote)) throw new IllegalArgumentException("Invalid market pair");
        return base+"/"+quote;
    }
    public record PublicTrade(String id,String price,String amount,String side,Instant timestamp) {}
    public record View(MarketSnapshot book,List<PublicTrade> trades) {}
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public View view(String base,String quote) throws Exception {
        String pair=pair(base,quote);
        MarketState state=states.findById(pair).orElseThrow(()->new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Market snapshot not received"));
        return new View(mapper.readValue(state.getPayload(),MarketSnapshot.class),prints.findTop100ByPairOrderByTimestampDescSequenceDescTradeIndexDesc(pair).stream().map(p->new PublicTrade(p.getId(),p.getPrice().toPlainString(),p.getAmount().toPlainString(),p.getSide(),p.getTimestamp())).toList());
    }
    public record Candle(long time,String open,String high,String low,String close,String volume) {}
    @Transactional(readOnly=true)
    public List<Candle> candles(String base,String quote) {
        // 실제 체결만 1분 봉으로 묶는다. 거래 없는 구간에 바이낸스 가격이나
        // 임의 봉을 채우지 않는다. 가장 최근 5천 체결 범위를 명시한다.
        var history=new ArrayList<>(prints.findTop5000ByPairOrderByTimestampDescSequenceDescTradeIndexDesc(pair(base,quote)));
        Collections.reverse(history);
        Map<Long,BigDecimal[]> bars=new TreeMap<>();
        for(var trade:history) {
            long time=trade.getTimestamp().getEpochSecond()/60*60;
            BigDecimal price=trade.getPrice();
            BigDecimal[] bar=bars.computeIfAbsent(time,t->new BigDecimal[]{price,price,price,price,BigDecimal.ZERO});
            bar[1]=bar[1].max(price);bar[2]=bar[2].min(price);bar[3]=price;bar[4]=bar[4].add(trade.getAmount());
        }
        return bars.entrySet().stream().map(e->{var b=e.getValue();return new Candle(e.getKey(),b[0].toPlainString(),b[1].toPlainString(),b[2].toPlainString(),b[3].toPlainString(),b[4].toPlainString());}).toList();
    }
}
