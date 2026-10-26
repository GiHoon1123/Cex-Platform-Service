package dustin.cex.domains.bitcoin;

import dustin.cex.shared.http.LimitedResponseBody;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;
import tools.jackson.databind.cfg.JsonNodeFeature;

/** 고정된 단일 Bitcoin Core 끝점에 크기·시간 제한이 있는 JSON-RPC를 전송한다. 업무별 허용 RPC는 호출 계정에서 제한한다. */
public final class BitcoinJsonRpcClient implements AutoCloseable {
    /** 업무 코드는 메시지 문자열 대신 Core의 안정적인 숫자 오류 코드만 제한적으로 판정한다. */
    public static final class RpcFailure extends RuntimeException {public final int code;RpcFailure(int code){super("노드 RPC가 요청을 거절했습니다");this.code=code;}}
    private final URI endpoint;
    private final String username,password;
    private final ObjectMapper json;
    private final ObjectReader tree;
    private final HttpClient http=HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public BitcoinJsonRpcClient(URI endpoint,String username,String password,ObjectMapper json){
        this.endpoint=Objects.requireNonNull(endpoint);this.username=username;this.password=password;this.json=json;
        tree=json.reader(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public JsonNode call(String method,List<?> params){return call(method,params,65536);}
    public JsonNode call(String method,List<?> params,long limit){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("노드 호출은 데이터베이스 트랜잭션 밖에서 실행하세요");
        String id=UUID.randomUUID().toString();
        try{
            String authorization=Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Authorization","Basic "+authorization).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params)))).build();
            var pending=http.sendAsync(request,info->new LimitedResponseBody(limit));
            try{
                var response=pending.get(5,TimeUnit.SECONDS);
                if(response.statusCode()!=200)throw new IllegalStateException("노드 응답을 확인할 수 없습니다");
                var body=tree.readTree(response.body());
                if(body==null||!body.isObject()||!"2.0".equals(text(body,"jsonrpc"))||!id.equals(text(body,"id")))throw new IllegalStateException("노드 응답을 확인할 수 없습니다");
                if(body.has("error")&&!body.path("error").isNull()){var code=body.path("error").path("code");if(!code.isIntegralNumber()||!code.canConvertToInt())throw new IllegalStateException("노드 응답을 확인할 수 없습니다");throw new RpcFailure(code.intValue());}
                if(!body.has("result")||body.path("result").isNull())throw new IllegalStateException("노드 응답을 확인할 수 없습니다");
                return body.path("result");
            }finally{if(!pending.isDone())pending.cancel(true);}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("노드 호출이 중단됐습니다");}
        catch(RpcFailure rejected){throw rejected;}
        catch(ExecutionException|TimeoutException|RuntimeException e){throw new IllegalStateException("노드 응답을 확인할 수 없습니다");}
    }
    private static String text(JsonNode node,String field){var value=node.path(field);return value.isString()?value.asString():"";}
    @Override public void close(){http.shutdownNow();}
}
