package dustin.cex.domains.ethereum;

import dustin.cex.shared.http.LimitedResponseBody;
import java.net.http.*;import java.time.Duration;import java.util.*;import java.util.concurrent.*;
import org.springframework.stereotype.Component;import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;import tools.jackson.databind.*;import tools.jackson.databind.json.JsonMapper;

/** 고정한 관찰 노드의 표준 JSON-RPC만 호출하며 응답 크기·시간·식별자를 검증한다. */
@Component
public class EthereumJsonRpcClient implements AutoCloseable {
    private final EthereumSettings settings;private final ObjectMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpClient http=HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public EthereumJsonRpcClient(EthereumSettings settings){this.settings=settings;}
    public JsonNode call(String method,List<?> params){
        if(!settings.configured())throw new IllegalStateException("이더리움 노드가 설정되지 않았습니다");
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("노드 호출은 데이터베이스 트랜잭션 밖에서 실행하세요");
        String id=UUID.randomUUID().toString();
        try{
            var request=HttpRequest.newBuilder(settings.endpoint).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params)))).build();
            var pending=http.sendAsync(request,info->new LimitedResponseBody(4*1024*1024));
            try{var response=pending.get(5,TimeUnit.SECONDS);if(response.statusCode()!=200)throw unavailable();var body=json.readTree(response.body());
                if(body==null||!body.isObject()||!"2.0".equals(body.path("jsonrpc").asString())||!id.equals(body.path("id").asString())||body.hasNonNull("error")||!body.has("result"))throw unavailable();return body.path("result");
            }finally{if(!pending.isDone())pending.cancel(true);}
        }catch(InterruptedException stopped){Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException|TimeoutException|RuntimeException failure){throw unavailable();}
    }
    private static IllegalStateException unavailable(){return new IllegalStateException("이더리움 노드 응답을 확인할 수 없습니다");}
    @Override public void close(){http.shutdownNow();}
}
