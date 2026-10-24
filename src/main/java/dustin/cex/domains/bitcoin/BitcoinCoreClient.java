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

/** 주소 파생만 맡는다. 서명·전송·잔액 반영과 개인키 가져오기 기능은 제공하지 않는다. */
@Component
public class BitcoinCoreClient {
    public record Address(String network,String address,String scriptPubKey){}
    private final BitcoinSettings settings;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public BitcoinCoreClient(BitcoinSettings settings,ObjectMapper json){this.settings=settings;this.json=json;}
    public Address derive(UUID allocation){
        // 네트워크 대기 동안 고객 행 잠금·DB 연결을 붙잡지 않도록 호출 경계에서도 강제한다.
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("노드 호출은 데이터베이스 트랜잭션 밖에서 실행하세요");
        if(!settings.configured()||allocation==null)throw unavailable();
        var chain=call("getblockchaininfo",List.of());
        if(!text(chain,"chain").equals(settings.network.chain)||!isFalse(chain,"initialblockdownload"))throw unavailable();
        var wallet=call("getwalletinfo",List.of());
        if(!text(wallet,"walletname").equals(settings.wallet)||!isFalse(wallet,"private_keys_enabled")
                ||!isTrue(wallet,"descriptors")||!isFalse(wallet,"scanning"))throw unavailable();
        var generated=call("getnewaddress",List.of(allocation.toString(),"bech32"));
        if(!generated.isString()||generated.asString().length()>100)throw unavailable();
        String address=generated.asString();
        var info=call("getaddressinfo",List.of(address));
        // 인코딩·체크섬·키 파생을 직접 구현하지 않는다. 실제 노드가 소유한 외부 수신 주소인지 확인한다.
        String script=text(info,"scriptPubKey");
        if(!text(info,"address").equals(address)||!isTrue(info,"ismine")||!isTrue(info,"solvable")
                ||!isFalse(info,"ischange")||!isTrue(info,"iswitness")||!info.path("witness_version").isIntegralNumber()
                ||!info.path("witness_version").canConvertToInt()||info.path("witness_version").intValue()!=0||!script.matches("0014[0-9a-f]{40}"))throw unavailable();
        return new Address(settings.network.id,address,script);
    }
    private JsonNode call(String method,List<?> params){
        String id=UUID.randomUUID().toString();
        try{
            String authorization=Base64.getEncoder().encodeToString((settings.username+":"+settings.password).getBytes(StandardCharsets.UTF_8));
            var request=HttpRequest.newBuilder(settings.endpoint).timeout(Duration.ofSeconds(5))
                .header("Authorization","Basic "+authorization).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("jsonrpc","2.0","id",id,"method",method,"params",params)))).build();
            var pending=http.sendAsync(request,info->new LimitedResponseBody(65536));
            try{
                // 헤더 도착과 구분해 본문 전체에도 제한 시간을 적용한다.
                var response=pending.get(5,TimeUnit.SECONDS);
                if(response.statusCode()!=200)throw unavailable();
                var body=json.readTree(response.body());
                if(body==null||!body.isObject()||!text(body,"jsonrpc").equals("2.0")||!text(body,"id").equals(id)
                        ||body.has("error")||!body.has("result")||body.path("result").isNull())throw unavailable();
                return body.path("result");
            }finally{if(!pending.isDone())pending.cancel(true);}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException|TimeoutException|RuntimeException e){throw unavailable();}
    }
    private static String text(JsonNode node,String field){var value=node.path(field);return value.isString()?value.asString():"";}
    private static boolean isTrue(JsonNode node,String field){return node.path(field).isBoolean()&&node.path(field).booleanValue();}
    private static boolean isFalse(JsonNode node,String field){return node.path(field).isBoolean()&&!node.path(field).booleanValue();}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"비트코인 수신 주소를 확인할 수 없습니다");}
    @jakarta.annotation.PreDestroy public void close(){http.shutdownNow();}
}
