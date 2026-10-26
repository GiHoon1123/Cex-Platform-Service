package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 소유 HTTP 노드 대역으로 주소/스크립트 범위 결합과 실패 전파를 검증한다. 실제 체크섬은 별도 Core 시험이다. */
@org.junit.jupiter.api.parallel.Isolated
class BitcoinDestinationClientTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String ADDRESS="bcrt1qfixture",SCRIPT="0014"+"ab".repeat(20),PASSWORD="isolated-destination-node-password-32-bytes";
    HttpServer server;ExecutorService threads;BitcoinRpcClient rpc;BitcoinDestinationClient client;
    String mode="NORMAL",type="witness_v0_keyhash";List<String> calls=new CopyOnWriteArrayList<>();
    @BeforeEach void setup()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);threads=Executors.newCachedThreadPool();server.setExecutor(threads);
        server.createContext("/wallet/watch",exchange->{
            try{
                var input=JSON.readTree(exchange.getRequestBody().readAllBytes());String method=input.path("method").stringValue();calls.add(method);
                assertEquals("Basic "+Base64.getEncoder().encodeToString(("addresses:"+PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8)),exchange.getRequestHeaders().getFirst("Authorization"));
                Object result=switch(method){
                    case "getblockchaininfo"->Map.of("chain",mode.equals("CHAIN")?"main":"regtest","initialblockdownload",mode.equals("IBD"));
                    case "getwalletinfo"->Map.of("walletname","watch","private_keys_enabled",mode.equals("PRIVATE"),"descriptors",true,"scanning",false);
                    case "validateaddress"->{assertEquals(1,input.path("params").size());var value=new HashMap<String,Object>();value.put("isvalid",!mode.equals("INVALID"));value.put("address",mode.equals("ANOTHER")?"bcrt1qanothertarget":ADDRESS);value.put("scriptPubKey",mode.equals("SCRIPT")?"not-hex":SCRIPT);if(mode.equals("VALID_TYPE"))value.put("isvalid","true");if(mode.equals("VALID_MISSING"))value.remove("isvalid");yield value;}
                    case "decodescript"->{assertEquals(SCRIPT,input.path("params").get(0).stringValue());var value=new HashMap<String,Object>();value.put("type",mode.equals("UNSUPPORTED")?"witness_unknown":type);value.put("address",mode.equals("ANOTHER")?"bcrt1qanothertarget":mode.equals("DECODE_ADDRESS")?"bcrt1qother":ADDRESS);if(mode.equals("DECODE_TYPE"))value.put("type",1);if(mode.equals("DECODE_MISSING"))value.remove("address");yield value;}
                    default->throw new AssertionError("예상하지 않은 변경 RPC: "+method);
                };
                var body=new HashMap<String,Object>();body.put("jsonrpc","2.0");body.put("id",mode.equals("ID")?"different":input.path("id").stringValue());body.put("result",result);if(mode.equals("ERROR"))body.put("error",Map.of("code",-1,"message","private-destination-source-detail"));byte[] bytes=JSON.writeValueAsBytes(body);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Exception|AssertionError failure){try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}
            finally{exchange.close();}
        });server.start();var settings=new BitcoinSettings("http://127.0.0.1:"+server.getAddress().getPort(),"addresses",PASSWORD,"watch","REGTEST");rpc=new BitcoinRpcClient(settings,JSON);client=new BitcoinDestinationClient(settings,rpc);
    }
    @AfterEach void stop(){rpc.close();server.stop(0);threads.shutdownNow();}
    void unavailable(){assertEquals(503,assertThrows(ResponseStatusException.class,()->client.validate(ADDRESS)).getStatusCode().value());}
    @Test void nodeValidatedDestinationKeepsNetworkCanonicalAddressScriptAndType(){var result=client.validate(ADDRESS);assertEquals("bitcoin-regtest",result.network());assertEquals(ADDRESS,result.address());assertEquals(SCRIPT,result.scriptPubKey());assertEquals(type,result.scriptType());assertEquals(List.of("getblockchaininfo","getwalletinfo","validateaddress","decodescript"),calls);assertFalse(result.toString().contains(ADDRESS));}
    @Test void eachSupportedStandardOutputIsAcceptedFromTheNode(){for(String supported:List.of("pubkeyhash","scripthash","witness_v0_keyhash","witness_v0_scripthash","witness_v1_taproot")){type=supported;assertEquals(type,client.validate(ADDRESS).scriptType());}}
    @Test void entirelyUppercaseWitnessAddressUsesTheSameCanonicalDestination(){assertEquals(ADDRESS,client.validate(ADDRESS.toUpperCase(Locale.ROOT)).address());}
    @Test void legacyCaseChangesCannotBeSilentlyNormalized(){type="pubkeyhash";assertEquals(503,assertThrows(ResponseStatusException.class,()->client.validate(ADDRESS.toUpperCase(Locale.ROOT))).getStatusCode().value());}
    @Test void malformedInputNeverCallsTheNode(){for(String value:List.of(""," ",ADDRESS+"\n"," bitcoin:"+ADDRESS,"A".repeat(101),"비트코인주소"))assertEquals(400,assertThrows(ResponseStatusException.class,()->client.validate(value)).getStatusCode().value());assertEquals(400,assertThrows(ResponseStatusException.class,()->client.validate(null)).getStatusCode().value());assertTrue(calls.isEmpty());}
    @Test void activeDatabaseTransactionIsRejectedBeforeTheNode(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->client.validate(ADDRESS));}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}assertTrue(calls.isEmpty());}
    @Test void wrongNetworkOrPrivateWalletCannotReachAddressValidation(){for(String failure:List.of("CHAIN","IBD","PRIVATE")){mode=failure;unavailable();}assertFalse(calls.contains("validateaddress"));}
    @Test void invalidAddressIsAUserRejectionAndDoesNotDecodeAnyScript(){mode="INVALID";assertEquals(400,assertThrows(ResponseStatusException.class,()->client.validate(ADDRESS)).getStatusCode().value());assertFalse(calls.contains("decodescript"));}
    @Test void malformedValidityAndScriptFailAsSourceErrors(){for(String failure:List.of("VALID_TYPE","VALID_MISSING","SCRIPT")){mode=failure;unavailable();}}
    @Test void differentAddressRepliesCannotRedirectTheOriginalDestination(){for(String failure:List.of("ANOTHER","DECODE_ADDRESS","DECODE_MISSING","DECODE_TYPE")){mode=failure;unavailable();}}
    @Test void unknownOutputTypeIsNotACompatibilityFallback(){mode="UNSUPPORTED";assertEquals(400,assertThrows(ResponseStatusException.class,()->client.validate(ADDRESS)).getStatusCode().value());}
    @Test void nodeFailureAndResponseIdMismatchDoNotExposeTheSource(){for(String failure:List.of("ERROR","ID")){mode=failure;var error=assertThrows(ResponseStatusException.class,()->client.validate(ADDRESS));assertEquals(503,error.getStatusCode().value());assertFalse(error.getMessage().contains("private-destination-source-detail"));}}
    @Test void globalProxyCannotReceiveTheNodeAuthorization(){
        var previous=java.net.ProxySelector.getDefault();var selected=new java.util.concurrent.atomic.AtomicInteger();rpc.close();
        try{
            java.net.ProxySelector.setDefault(new java.net.ProxySelector(){
                @Override public List<java.net.Proxy> select(URI uri){selected.incrementAndGet();return List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",1)));}
                @Override public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}
            });
            var settings=new BitcoinSettings("http://127.0.0.1:"+server.getAddress().getPort(),"addresses",PASSWORD,"watch","REGTEST");rpc=new BitcoinRpcClient(settings,JSON);client=new BitcoinDestinationClient(settings,rpc);assertEquals(ADDRESS,client.validate(ADDRESS).address());assertEquals(0,selected.get());
        }finally{java.net.ProxySelector.setDefault(previous);}
    }

}
