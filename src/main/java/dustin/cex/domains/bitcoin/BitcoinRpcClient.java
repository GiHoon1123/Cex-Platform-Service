package dustin.cex.domains.bitcoin;

import dustin.cex.shared.http.LimitedResponseBody;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import tools.jackson.databind.cfg.JsonNodeFeature;

/** 고정 노드의 전송·기본 신원 확인만 맡는다. 주소 파생과 입금 판독이 장수 연결을 공유한다. */
@Component
final class BitcoinRpcClient implements AutoCloseable {
    record Ready(JsonNode chain,JsonNode wallet){}
    private final BitcoinSettings settings;
    private final ObjectMapper json;
    private final ObjectReader tree;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    BitcoinRpcClient(BitcoinSettings settings,ObjectMapper json){
        this.settings=settings;this.json=json;
        // 노드는 금액을 JSON 숫자로 보낸다. 이 전용 판독기의 소수 토큰은 이진 부동소수점을 거치지 않는다.
        tree=json.reader(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    Ready ready(){
        var chain=call("getblockchaininfo",List.of());
        if(!text(chain,"chain").equals(settings.network.chain)||!isFalse(chain,"initialblockdownload"))throw unavailable();
        var wallet=call("getwalletinfo",List.of());
        if(!text(wallet,"walletname").equals(settings.wallet)||!isFalse(wallet,"private_keys_enabled")
                ||!isTrue(wallet,"descriptors")||!isFalse(wallet,"scanning"))throw unavailable();
        return new Ready(chain,wallet);
    }
    JsonNode call(String method,List<?> params){return call(method,params,65536);}
    JsonNode call(String method,List<?> params,long limit){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("노드 호출은 데이터베이스 트랜잭션 밖에서 실행하세요");
        if(!settings.configured())throw unavailable();
        String id=UUID.randomUUID().toString();
        try{
            String authorization=Base64.getEncoder().encodeToString((settings.username+":"+settings.password).getBytes(StandardCharsets.UTF_8));
            var request=HttpRequest.newBuilder(settings.endpoint).timeout(Duration.ofSeconds(5))
                .header("Authorization","Basic "+authorization).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params)))).build();
            var pending=http.sendAsync(request,info->new LimitedResponseBody(limit));
            try{
                // 헤더 도착과 구분해 본문 전체에도 제한 시간을 적용한다.
                var response=pending.get(5,TimeUnit.SECONDS);
                if(response.statusCode()!=200)throw unavailable();
                var body=tree.readTree(response.body());
                if(body==null||!body.isObject()||!text(body,"jsonrpc").equals("2.0")||!text(body,"id").equals(id)
                        ||body.has("error")||!body.has("result")||body.path("result").isNull())throw unavailable();
                return body.path("result");
            }finally{if(!pending.isDone())pending.cancel(true);}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException|TimeoutException|RuntimeException e){throw unavailable();}
    }
    static String text(JsonNode node,String field){var value=node.path(field);return value.isString()?value.asString():"";}
    static boolean isTrue(JsonNode node,String field){return node.path(field).isBoolean()&&node.path(field).booleanValue();}
    static boolean isFalse(JsonNode node,String field){return node.path(field).isBoolean()&&!node.path(field).booleanValue();}
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"비트코인 노드 결과를 확인할 수 없습니다");}
    @jakarta.annotation.PreDestroy @Override public void close(){http.shutdownNow();}
}
