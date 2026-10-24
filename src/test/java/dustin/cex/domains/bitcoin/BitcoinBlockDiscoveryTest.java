package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 실제 정규 블록/수신 조건을 대조하고 읽기 실패를 빈 결과로 숨기지 않는 HTTP 경계를 검증한다. */
class BitcoinBlockDiscoveryTest {
    static final String TIP="aa".repeat(32),BLOCK="bb".repeat(32),TX="11".repeat(32),SCRIPT="0014"+"ab".repeat(20);
    static final JsonMapper JSON=JsonMapper.builder().build();
    HttpServer server;ExecutorService executor;BitcoinRpcClient rpc;BitcoinBlockDiscovery discovery;
    String mode="NORMAL";AtomicInteger calls=new AtomicInteger(),scans=new AtomicInteger(),hashes=new AtomicInteger(),tips=new AtomicInteger();Runnable hook;
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/wallet/watch",exchange->{
            try{
                calls.incrementAndGet();var request=JSON.readTree(exchange.getRequestBody().readAllBytes());String method=request.path("method").asString();
                Object result=switch(method){
                    case "getblockchaininfo"->{if(hook!=null){var action=hook;hook=null;action.run();}yield Map.of("chain","regtest","initialblockdownload",false,"blocks",10,"bestblockhash",mode.equals("TIP_CHANGED")&&tips.incrementAndGet()>1?BLOCK:TIP);}
                    case "getwalletinfo"->Map.of("walletname","watch","private_keys_enabled",false,"descriptors",true,"scanning",false);
                    case "getblockhash"->mode.equals("HASH_CHANGED")&&hashes.incrementAndGet()>1?TIP:BLOCK;
                    case "getblockheader"->Map.of("hash",BLOCK,"height",8,"confirmations",3,"previousblockhash",mode.equals("BAD_PARENT")?"invalid":"cc".repeat(32));
                    case "getdescriptoractivity"->{
                        scans.incrementAndGet();assertFalse(request.path("params").get(2).booleanValue());
                        assertEquals("raw("+SCRIPT+")",request.path("params").get(1).get(0).asString());
                        if(mode.equals("UNREADABLE"))throw new IllegalStateException("시험용 블록 읽기 실패");
                        if(mode.equals("MISSING_ACTIVITY"))yield Map.of();
                        if(mode.equals("BAD_ACTIVITY"))yield Map.of("activity","invalid");
                        var event=new HashMap<String,Object>(Map.of("type",mode.equals("SPEND_ONLY")?"spend":mode.equals("UNKNOWN_KIND")?"unknown":"receive",
                            "blockhash",mode.equals("EVENT_BLOCK")?TIP:BLOCK,"height",8,"txid",TX,"vout",mode.equals("VOUT_OVERFLOW")?2147483648L:0,
                            "amount",new BigDecimal(mode.equals("PRECISION")?"0.000000001":"0.12345678"),
                            "output_spk",Map.of("hex",mode.equals("FOREIGN_SCRIPT")?"0014"+"cd".repeat(20):SCRIPT)));
                        yield Map.of("activity",mode.equals("EMPTY")?List.of():mode.equals("DUPLICATE")?List.of(event,event):List.of(event));
                    }
                    default->throw new AssertionError(method);
                };
                byte[] bytes=JSON.writeValueAsBytes(Map.of("jsonrpc","2.0","id",request.path("id").asString(),"result",result));
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception|AssertionError error){try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}
            finally{exchange.close();}
        });server.start();var settings=new BitcoinSettings("http://127.0.0.1:"+server.getAddress().getPort(),"addresses","isolated-bitcoin-discovery-password-at-least-32-bytes","watch","REGTEST");
        rpc=new BitcoinRpcClient(settings,JSON);discovery=new BitcoinBlockDiscovery(rpc,settings);
    }
    @AfterEach void close(){rpc.close();server.stop(0);executor.shutdownNow();}
    @Test void verifiedBlockActivityReturnsExplicitOutpoint(){var result=scan();assertEquals(BLOCK,result.hash());assertEquals(8,result.height());assertEquals("cc".repeat(32),result.previousHash());assertEquals(TX,result.received().getFirst().txid());assertEquals(new BigDecimal("0.12345678"),result.received().getFirst().amount());assertThrows(UnsupportedOperationException.class,()->result.received().clear());}
    @Test void unreadableBlockCannotPretendEmptyActivity(){mode="UNREADABLE";unavailable();assertEquals(1,scans.get());}
    @Test void missingOrMalformedActivityIsRejected(){for(String failure:List.of("MISSING_ACTIVITY","BAD_ACTIVITY")){mode=failure;unavailable();}}
    @Test void activityMustBeSameBlockUniqueRequestedScriptAndExactAmount(){for(String failure:List.of("FOREIGN_SCRIPT","EVENT_BLOCK","DUPLICATE","VOUT_OVERFLOW","PRECISION","UNKNOWN_KIND")){mode=failure;unavailable();}}
    @Test void movingTipOrCanonicalBlockInvalidatesScan(){for(String failure:List.of("TIP_CHANGED","HASH_CHANGED","BAD_PARENT")){mode=failure;tips.set(0);hashes.set(0);unavailable();}}
    @Test void emptyActivityAndSpendsDoNotCreateReceives(){for(String state:List.of("EMPTY","SPEND_ONLY")){mode=state;assertTrue(scan().received().isEmpty());}}
    @Test void mutableCallerListCannotChangeValidatedScan(){var selected=new ArrayList<>(List.of(SCRIPT));hook=selected::clear;assertEquals(1,discovery.scan(8,selected).received().size());assertTrue(selected.isEmpty());}
    @Test void invalidBoundsScriptsDuplicatesAndOversizedPageNeverContactNode(){
        assertThrows(IllegalArgumentException.class,()->discovery.scan(-1,List.of(SCRIPT)));
        for(List<String> scripts:List.of(List.<String>of(),List.of("invalid"),List.of(SCRIPT,SCRIPT),Collections.nCopies(201,SCRIPT)))
            assertThrows(IllegalArgumentException.class,()->discovery.scan(8,scripts));
        assertThrows(IllegalArgumentException.class,()->discovery.scan(8,null));assertEquals(0,calls.get());
    }
    @Test void databaseTransactionCannotWaitForBlockScan(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,this::scan);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}assertEquals(0,calls.get());}
    BitcoinBlockDiscovery.Block scan(){return discovery.scan(8,List.of(SCRIPT));}
    void unavailable(){assertEquals(503,assertThrows(ResponseStatusException.class,this::scan).getStatusCode().value());}
}
