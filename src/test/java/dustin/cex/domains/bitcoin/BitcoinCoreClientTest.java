package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP 경계에서 잘못된 노드 응답·인증·리다이렉트·자원 제한을 검증한다. */
class BitcoinCoreClientTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String PASSWORD="isolated-bitcoin-password-at-least-32-bytes";
    HttpServer server;BitcoinCoreClient client;BitcoinRpcClient rpc;String mode="NORMAL";AtomicInteger allocations=new AtomicInteger();
    CountDownLatch release=new CountDownLatch(1);ExecutorService executor;
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/wallet/watch",exchange->{
            try{
                assertEquals("Basic "+Base64.getEncoder().encodeToString(("addresses:"+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)),exchange.getRequestHeaders().getFirst("Authorization"));
                var request=JSON.readTree(exchange.getRequestBody().readAllBytes());String method=request.path("method").asString();
                if(mode.equals("REDIRECT")){exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/secret");exchange.sendResponseHeaders(302,-1);return;}
                if(mode.equals("OVERSIZE")){exchange.sendResponseHeaders(200,65537);exchange.getResponseBody().write(new byte[65537]);return;}
                if(mode.equals("HANG")){exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();release.await(8,TimeUnit.SECONDS);return;}
                Object value=switch(method){
                    case "getblockchaininfo"->Map.of("chain",mode.equals("CHAIN")?"main":"regtest","initialblockdownload",mode.equals("IBD"));
                    case "getwalletinfo"->Map.of("walletname","watch","private_keys_enabled",mode.equals("PRIVATE"),"descriptors",!mode.equals("LEGACY"),"scanning",mode.equals("SCANNING"));
                    case "getnewaddress"->{allocations.incrementAndGet();assertEquals("bech32",request.path("params").get(1).asString());yield "bcrt1qexample";}
                    case "getaddressinfo"->Map.of("address","bcrt1qexample","scriptPubKey",mode.equals("SCRIPT")?"0014ffff":"0014"+"ab".repeat(20),"ismine",!mode.equals("FOREIGN"),"solvable",true,"ischange",false,"iswitness",true,"witness_version",mode.equals("VERSION_OVERFLOW")?4294967296L:0);
                    default->throw new AssertionError(method);
                };
                var response=new HashMap<String,Object>();response.put("jsonrpc","2.0");response.put("id",mode.equals("ID")?"different":request.path("id").asString());response.put("result",value);
                if(mode.equals("ERROR"))response.put("error",Map.of("code",-1,"message","private-node-detail"));
                byte[] bytes=JSON.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception|AssertionError failure){try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}
            finally{exchange.close();}
        });server.start();
        var setting=new BitcoinSettings("http://127.0.0.1:"+server.getAddress().getPort(),"addresses",PASSWORD,"watch","REGTEST");
        rpc=new BitcoinRpcClient(setting,JSON);client=new BitcoinCoreClient(setting,rpc);
    }
    @AfterEach void stop(){release.countDown();rpc.close();server.stop(0);executor.shutdownNow();}
    @Test void validResponsePreservesNetworkAndScript(){assertEquals("0014"+"ab".repeat(20),client.derive(UUID.randomUUID()).scriptPubKey());assertEquals(1,allocations.get());}
    @Test void readinessFailureDoesNotAllocate(){
        for(String failure:List.of("CHAIN","IBD","PRIVATE","LEGACY","SCANNING")){mode=failure;assertUnavailable();}
        assertEquals(0,allocations.get());
    }
    @Test void mismatchedReplyErrorAndUnownedScriptFailClosed(){for(String failure:List.of("ID","ERROR","SCRIPT","FOREIGN","VERSION_OVERFLOW")){mode=failure;assertUnavailable();}}
    @Test void oversizedAndRedirectResponsesAreNotFollowedOrRetried(){for(String failure:List.of("OVERSIZE","REDIRECT")){mode=failure;assertUnavailable();}assertEquals(0,allocations.get());}
    @Test void fullBodyHasDeadline(){mode="HANG";long start=System.nanoTime();assertUnavailable();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<7000);}
    @Test void nodeCallsCannotHoldDatabaseTransaction(){
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThrows(IllegalStateException.class,()->client.derive(UUID.randomUUID()));assertEquals(0,allocations.get());}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void emptyConfigurationDisablesCallsAndPartialOrRemotePlaintextConfigurationFails(){
        var absentSettings=new BitcoinSettings("","","","","REGTEST");var absentRpc=new BitcoinRpcClient(absentSettings,JSON);var absent=new BitcoinCoreClient(absentSettings,absentRpc);
        try{assertEquals(503,assertThrows(ResponseStatusException.class,()->absent.derive(UUID.randomUUID())).getStatusCode().value());}finally{absentRpc.close();}
        for(String url:List.of("http://example.test","http://127.0.0.1:1/wallet/other","http://user:password@127.0.0.1:1","http://127.0.0.1:1/?secret=x"))
            assertThrows(IllegalArgumentException.class,()->new BitcoinSettings(url,"addresses",PASSWORD,"watch","REGTEST"));
        assertFalse(assertThrows(IllegalArgumentException.class,()->new BitcoinSettings("http://invalid secret", "addresses",PASSWORD,"watch","REGTEST")).getMessage().contains("invalid secret"));
        assertFalse(new BitcoinSettings("https://node.example.test","addresses",PASSWORD,"watch","MAINNET").toString().contains(PASSWORD));
    }
    private void assertUnavailable(){var error=assertThrows(ResponseStatusException.class,()->client.derive(UUID.randomUUID()));assertEquals(503,error.getStatusCode().value());assertFalse(error.getMessage().contains("private-node-detail"));}
}
