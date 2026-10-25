package dustin.cex.domains.valuation;

import dustin.cex.shared.http.LimitedResponseBody;
import java.math.*;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 고정된 공식 BTC/KRW 공개 참고가격만 읽는다. 고객/수탁 데이터·주문을 보내거나 법정 환산 면제를 판정하지 않는다. */
@Component
public class BitcoinKrwPriceClient implements AutoCloseable {
    public record Quote(BigDecimal price,Instant tradeAt,Instant providerAt,Instant readStartedAt,Instant readFinishedAt,String payload,String payloadHash){
        @Override public String toString(){return "BitcoinKrwQuote[원문 생략]";}
    }
    private final URI endpoint;
    private final HttpClient http;
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(1024).maxNumberLength(128).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    public BitcoinKrwPriceClient(){this(URI.create("https://api.upbit.com"));}
    // 제품 주소는 고정한다. 이 생성자는 같은 패키지의 소유 HTTP 계약 시험에서만 루프백을 지정한다.
    BitcoinKrwPriceClient(URI origin){
        if(origin==null||(!URI.create("https://api.upbit.com").equals(origin)&&!("http".equals(origin.getScheme())&&Set.of("127.0.0.1","[::1]").contains(origin.getHost())
            &&origin.getUserInfo()==null&&origin.getQuery()==null&&origin.getFragment()==null&&(origin.getPath().isEmpty()||origin.getPath().equals("/")))))throw new IllegalArgumentException("공식 또는 격리 가격 조회 주소만 허용합니다");
        endpoint=origin.resolve("/v1/ticker?markets=KRW-BTC");
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public Quote read(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("가격 공급사 조회는 DB 트랜잭션 밖에서 실행하세요");
        Instant start=Instant.now();
        try{
            var pending=http.sendAsync(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).header("Accept","application/json").GET().build(),info->new LimitedResponseBody(65536));
            try{
                var response=pending.get(5,TimeUnit.SECONDS);if(response.statusCode()!=200)throw unavailable();
                // 잘못된 UTF-8을 대체 문자로 정상화하면 저장한 지문이 실제 응답 바이트와 달라질 수 있으므로 거절한다.
                String payload=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
                var values=json.readTree(payload);if(values==null||!values.isArray()||values.size()!=1)throw unavailable();var ticker=values.get(0);
                if(!ticker.isObject()||!"KRW-BTC".equals(ticker.path("market").asString())||!ticker.path("trade_price").isNumber())throw unavailable();
                // 문서의 숫자 표기를 이진 실수로 바꾸지 않는다. 소수 8자리 밖의 값을 반올림해 다른 가격으로 받아들이지 않는다.
                BigDecimal price=ticker.path("trade_price").decimalValue();if(price.signum()<=0||price.scale()>8||price.precision()-(long)price.scale()>30)throw unavailable();price=price.setScale(8,RoundingMode.UNNECESSARY);
                Instant trade=time(ticker,"trade_timestamp"),provider=time(ticker,"timestamp"),finish=Instant.now();
                // 두 시각을 구분한다. 최근 스냅샷이라는 주장만으로 오래된 체결가를 새 가격으로 보지 않는다.
                if(trade.isAfter(provider)||provider.isAfter(finish.plusSeconds(10))||trade.isBefore(start.minusSeconds(120))||finish.isBefore(start))throw unavailable();
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body()));
                return new Quote(price,trade,provider,start,finish,payload,hash);
            }finally{if(!pending.isDone())pending.cancel(true);}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception failed){throw unavailable();}
    }
    private static Instant time(JsonNode object,String field){var value=object.path(field);if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<=0)throw unavailable();return Instant.ofEpochMilli(value.longValue());}
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"현재 BTC 원화 참고가격을 확인할 수 없습니다");}
    @jakarta.annotation.PreDestroy @Override public void close(){http.shutdownNow();}
}
