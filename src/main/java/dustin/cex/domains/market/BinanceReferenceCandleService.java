package dustin.cex.domains.market;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/** 바이낸스 공개 봉을 외부 기준 시세로만 제공하며 자체 호가·체결 상태에는 반영하지 않는다. */
@Service
public class BinanceReferenceCandleService {
    private static final Duration CACHE_TTL=Duration.ofSeconds(10);
    private static final Set<String> INTERVALS=Set.of("1m","5m","15m","1h","4h","1d");
    private final ObjectMapper json;
    private final HttpClient http;
    private final URI endpoint;
    private final Map<String,CacheSlot> slots=new ConcurrentHashMap<>();

    public BinanceReferenceCandleService(ObjectMapper json,@Value("${market.reference.binance-url:https://data-api.binance.vision}") String baseUrl) {
        this.json=json;
        this.endpoint=validateEndpoint(baseUrl);
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public record Candle(long time,String open,String high,String low,String close,String volume) {}
    public record ReferenceCandles(String source,String market,String interval,Instant fetchedAt,List<Candle> candles) {}
    private record Cached(Instant storedAt,ReferenceCandles value) {}
    private static final class CacheSlot {private Cached cached;}

    /**
     * 같은 거래쌍과 봉 간격의 동시 요청을 한 번의 외부 호출로 합친다. 만료 뒤 호출이 실패하면 오래된
     * 봉을 정상값처럼 돌려주지 않고 503으로 실패해 화면이 외부 기준 시세 장애를 표시하게 한다.
     */
    public ReferenceCandles candles(String base,String quote,String interval) {
        String symbol=symbol(base,quote),selectedInterval=interval(interval);
        // 캐시는 외부 조회 조건 전체로 나눈다. 같은 거래쌍이라도 1분 봉과 1일 봉을
        // 공유하면 서로 다른 기간의 응답이 섞이므로 심볼과 간격을 함께 키로 사용한다.
        CacheSlot slot=slots.computeIfAbsent(symbol+":"+selectedInterval,ignored->new CacheSlot());Instant now=Instant.now();
        synchronized(slot) {
            if(slot.cached!=null&&slot.cached.storedAt().plus(CACHE_TTL).isAfter(now))return slot.cached.value();
            ReferenceCandles fresh=fetch(symbol,selectedInterval,now);slot.cached=new Cached(now,fresh);return fresh;
        }
    }

    private ReferenceCandles fetch(String symbol,String interval,Instant now) {
        URI uri=URI.create(endpoint+"/api/v3/klines?symbol="+symbol+"&interval="+interval+"&limit=500");
        try {
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).header("Accept","application/json").GET().build();
            HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw unavailable();
            JsonNode root=json.readTree(response.body());
            if(!root.isArray()||root.size()==0||root.size()>500)throw unavailable();
            List<Candle> candles=new ArrayList<>(root.size());long previous=-1;
            for(JsonNode row:root) {
                if(!row.isArray()||row.size()<6||!row.get(0).isIntegralNumber())throw unavailable();
                long time=row.get(0).longValue()/1000;
                if(time<=previous)throw unavailable();
                String open=decimal(row.get(1),true),high=decimal(row.get(2),true),low=decimal(row.get(3),true);
                String close=decimal(row.get(4),true),volume=decimal(row.get(5),false);
                if(new BigDecimal(high).compareTo(new BigDecimal(low))<0)throw unavailable();
                candles.add(new Candle(time,open,high,low,close,volume));previous=time;
            }
            return new ReferenceCandles("BINANCE",symbol.substring(0,symbol.length()-4)+"/USDT",interval,now,List.copyOf(candles));
        } catch(InterruptedException error) {
            Thread.currentThread().interrupt();throw unavailable();
        } catch(Exception error) {
            if(error instanceof ResponseStatusException status)throw status;
            throw unavailable();
        }
    }

    private static String decimal(JsonNode node,boolean positive) {
        if(node==null||!node.isString())throw unavailable();
        try {
            BigDecimal value=new BigDecimal(node.stringValue());
            if((positive&&value.signum()<=0)||(!positive&&value.signum()<0)||value.scale()>12||value.precision()>30)throw unavailable();
            return value.toPlainString();
        } catch(NumberFormatException error) {throw unavailable();}
    }

    private static String symbol(String base,String quote) {
        if(base==null||quote==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"지원하지 않는 외부 기준 시장입니다");
        String symbol=(base+quote).toUpperCase(Locale.ROOT);
        if(!quote.equalsIgnoreCase("USDT")||!(symbol.equals("BTCUSDT")||symbol.equals("ETHUSDT")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"지원하지 않는 외부 기준 시장입니다");
        return symbol;
    }

    private static String interval(String value) {
        if(value==null||!INTERVALS.contains(value))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"지원하지 않는 봉 간격입니다");
        return value;
    }

    private static URI validateEndpoint(String value) {
        URI uri=URI.create(value);
        boolean loopback="http".equals(uri.getScheme())&&("127.0.0.1".equals(uri.getHost())||"localhost".equals(uri.getHost()));
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                ||(uri.getPath()!=null&&!uri.getPath().isEmpty()&&!"/".equals(uri.getPath()))
                ||!("https".equals(uri.getScheme())||loopback))throw new IllegalArgumentException("바이낸스 기준 시세 주소를 확인하세요");
        return URI.create(value.replaceAll("/+$",""));
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"외부 기준 시세를 확인할 수 없습니다");
    }
}
