package dustin.cex.domains.bitcoin;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/** 고정 노드의 전송·기본 신원 확인만 맡는다. 주소 파생과 입금 판독이 장수 연결을 공유한다. */
@Component
final class BitcoinRpcClient implements AutoCloseable {
    record Ready(JsonNode chain,JsonNode wallet){}
    private final BitcoinSettings settings;
    private final BitcoinJsonRpcClient transport;
    BitcoinRpcClient(BitcoinSettings settings,ObjectMapper json){
        this.settings=settings;transport=settings.configured()?new BitcoinJsonRpcClient(settings.endpoint,settings.username,settings.password,json):null;
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
        try{return transport.call(method,params,limit);}catch(RuntimeException unavailable){throw unavailable();}
    }
    static String text(JsonNode node,String field){var value=node.path(field);return value.isString()?value.asString():"";}
    static boolean isTrue(JsonNode node,String field){return node.path(field).isBoolean()&&node.path(field).booleanValue();}
    static boolean isFalse(JsonNode node,String field){return node.path(field).isBoolean()&&!node.path(field).booleanValue();}
    static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"비트코인 노드 결과를 확인할 수 없습니다");}
    @jakarta.annotation.PreDestroy @Override public void close(){if(transport!=null)transport.close();}
}
