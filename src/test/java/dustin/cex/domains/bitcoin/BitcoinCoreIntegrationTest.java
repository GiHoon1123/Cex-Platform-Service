package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 테스트 실행기가 소유한 실제 노드만 사용한다. 일반 개발 노드나 공개 체인으로 대체하지 않는다. */
@EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class BitcoinCoreIntegrationTest {
    private BitcoinSettings settings(String wallet,String network){return new BitcoinSettings(System.getenv("CEX_BITCOIN_TEST_URL"),System.getenv("CEX_BITCOIN_TEST_USER"),System.getenv("CEX_BITCOIN_TEST_PASSWORD"),wallet,network);}
    @Test void watchOnlyWalletDerivesDistinctNativeAddresses(){
        var setting=settings("cex_watch","REGTEST");var rpc=new BitcoinRpcClient(setting,JsonMapper.builder().build());var client=new BitcoinCoreClient(setting,rpc);
        try{
            var first=client.derive(UUID.randomUUID());var second=client.derive(UUID.randomUUID());
            assertEquals("bitcoin-regtest",first.network());assertTrue(first.address().startsWith("bcrt1q"));
            assertNotEquals(first.address(),second.address());assertNotEquals(first.scriptPubKey(),second.scriptPubKey());
            assertTrue(first.scriptPubKey().matches("0014[0-9a-f]{40}"));
        }finally{rpc.close();}
    }
    @Test void wrongNetworkAndPrivateKeyWalletAreRejected(){
        for(var setting:List.of(settings("cex_watch","MAINNET"),settings("fixture_keys","REGTEST"))){
            var rpc=new BitcoinRpcClient(setting,JsonMapper.builder().build());var client=new BitcoinCoreClient(setting,rpc);
            try{assertEquals(503,assertThrows(ResponseStatusException.class,()->client.derive(UUID.randomUUID())).getStatusCode().value());}
            finally{rpc.close();}
        }
    }
    @Test void watchOnlyWalletExposesTrustedAndTransitionalBalanceBuckets(){
        var setting=settings("cex_watch","REGTEST");var rpc=new BitcoinRpcClient(setting,JsonMapper.builder().build());
        try{
            var ready=rpc.ready();var balances=rpc.call("getbalances",List.of());var mine=balances.path("mine");var block=balances.path("lastprocessedblock");
            assertEquals(ready.chain().path("bestblockhash").asString(),block.path("hash").asString());
            assertEquals(ready.chain().path("blocks").asLong(),block.path("height").asLong());
            for(String field:List.of("trusted","untrusted_pending","immature"))assertTrue(mine.path(field).isNumber(),field);
        }finally{rpc.close();}
    }
    @Test void nodeCredentialsCannotSignExportImportOrTransfer()throws Exception{
        try(var http=HttpClient.newHttpClient()){
            String auth=System.getenv("CEX_BITCOIN_TEST_USER")+":"+System.getenv("CEX_BITCOIN_TEST_PASSWORD");
            for(String method:List.of("sendtoaddress","sendrawtransaction","bumpfee","invalidateblock","signrawtransactionwithwallet","listdescriptors","importdescriptors","dumpprivkey","createwallet")){
                var request=HttpRequest.newBuilder(URI.create(System.getenv("CEX_BITCOIN_TEST_URL")+"/wallet/cex_watch"))
                    .header("Authorization","Basic "+Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""+method+"\",\"params\":[]}")).build();
                assertEquals(403,http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode(),method);
            }
        }
    }
}
