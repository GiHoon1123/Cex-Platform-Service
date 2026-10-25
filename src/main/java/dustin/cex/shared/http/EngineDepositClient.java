package dustin.cex.shared.http;

import dustin.cex.shared.model.ExactAmount;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 안정 입금 원본만 전송한다. HTTP 오류를 원장 해제·미반영·고객 거래 허가로 바꾸지 않는다. */
@Component
public class EngineDepositClient implements AutoCloseable {
    public enum Outcome {DURABLY_CREDITED,DURABLY_REJECTED,NOT_ACCEPTED,OUTCOME_UNKNOWN}
    public record Credit(UUID depositId,long customerId,String asset,BigDecimal amount){
        // 디버그 출력에 금융 원본을 자동으로 복제하지 않는다. 전용 원장·업무 감사의 보존과 구분한다.
        @Override public String toString(){return "EngineDepositCredit[입금 원본 생략]";}
    }
    private final URI endpoint;
    private final String token;
    private final int timeout;
    private final HttpClient http;
    private final ObjectMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(128).maxNumberLength(32).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public EngineDepositClient(@Value("${engine.http.url:http://127.0.0.1:3002}") String origin,
        @Value("${engine.http.timeout:5000}") int timeout,@Value("${engine.service.token}") String token){
        if(origin==null)throw new IllegalArgumentException("엔진 명령 주소를 확인하세요");
        URI base=URI.create(origin);
        if(!("http".equals(base.getScheme())||"https".equals(base.getScheme()))||base.getHost()==null||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null
            ||!(base.getPath().isEmpty()||base.getPath().equals("/"))||timeout<=0||token==null||token.length()<32||token.length()>1024||token.chars().anyMatch(c->c<33||c>126))throw new IllegalArgumentException("엔진 명령 주소·대기 시간·서비스 키를 확인하세요");
        endpoint=base.resolve("/api/cex/deposits/credits");this.timeout=timeout;this.token=token;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout)).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }
    public Outcome credit(Credit credit){
        // 금융 의도는 먼저 영속화하고 잠금을 놓은 뒤 전송한다. 네트워크 대기를 금융 DB 트랜잭션에 포함하지 않는다.
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 명령은 DB 트랜잭션 밖에서 전송하세요");
        if(credit==null||credit.depositId()==null||credit.depositId().equals(new UUID(0,0))||credit.customerId()<=0||credit.asset()==null||!credit.asset().matches("[A-Z0-9]{1,128}"))throw new IllegalArgumentException("안정 입금 원본을 확인하세요");
        BigDecimal amount=ExactAmount.requirePositive(credit.amount(),"입금 금액");
        byte[] body=json.writeValueAsBytes(Map.of("deposit_id",credit.depositId(),"user_id",credit.customerId(),"asset",credit.asset(),"amount",amount.toPlainString()));
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeout)).header("x-engine-service-token",token).header("Content-Type","application/json").header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            pending=http.sendAsync(request,info->new LimitedResponseBody(4096));var response=pending.get(timeout,TimeUnit.MILLISECONDS);
            if(!response.headers().firstValue("Content-Type").orElse("").split(";",2)[0].strip().equalsIgnoreCase("application/json"))return Outcome.OUTCOME_UNKNOWN;
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body())).toString();
            var receipt=json.readTree(raw);
            if(receipt==null||!receipt.isObject()||receipt.size()!=2||!receipt.path("code").isTextual()||!receipt.path("deposit_id").isTextual()||!credit.depositId().toString().equals(receipt.path("deposit_id").textValue()))return Outcome.OUTCOME_UNKNOWN;
            // 상태 코드와 본문을 함께 검사한다. 2xx만으로 성공하거나 다른 입금의 응답을 사용하지 않는다.
            return switch(response.statusCode()){
                case 200 -> "DURABLY_CREDITED".equals(receipt.path("code").textValue())?Outcome.DURABLY_CREDITED:Outcome.OUTCOME_UNKNOWN;
                case 409 -> "DURABLY_REJECTED".equals(receipt.path("code").textValue())?Outcome.DURABLY_REJECTED:Outcome.OUTCOME_UNKNOWN;
                case 503 -> "NOT_ACCEPTED".equals(receipt.path("code").textValue())?Outcome.NOT_ACCEPTED:Outcome.OUTCOME_UNKNOWN;
                default -> Outcome.OUTCOME_UNKNOWN;
            };
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Outcome.OUTCOME_UNKNOWN;}
        catch(Exception uncertain){return Outcome.OUTCOME_UNKNOWN;}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    @jakarta.annotation.PreDestroy @Override public void close(){http.shutdownNow();}
}
