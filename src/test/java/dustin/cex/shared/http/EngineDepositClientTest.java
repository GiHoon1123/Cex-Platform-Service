package dustin.cex.shared.http;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** 실소유 루프백 HTTP에서 요청 원본·응답 결합·크기/시간 제한을 검증한다. 실제 엔진의 내구성 시험과 구분한다. */
// 전역 프록시 선택기를 바꾸는 시험이 있어 다른 클래스와 병렬 실행하지 않고 원래 값을 반드시 복구한다.
@org.junit.jupiter.api.parallel.Isolated
class EngineDepositClientTest {
    final ObjectMapper json=new ObjectMapper();
    final UUID id=UUID.randomUUID();
    HttpServer server;
    ExecutorService executor;
    EngineDepositClient client;
    String origin;
    final AtomicInteger requests=new AtomicInteger(),redirected=new AtomicInteger();
    volatile int status=200;
    volatile byte[] response;
    volatile String type="application/json",location;
    volatile String method,path,token;
    volatile byte[] sent;
    final CountDownLatch release=new CountDownLatch(1);
    volatile boolean waitBeforeHeaders,waitInsideBody;
    @BeforeEach void start()throws Exception{
        response=reply("DURABLY_CREDITED").getBytes(StandardCharsets.UTF_8);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/",exchange->{
            requests.incrementAndGet();method=exchange.getRequestMethod();path=exchange.getRequestURI().getPath();token=exchange.getRequestHeaders().getFirst("x-engine-service-token");sent=exchange.getRequestBody().readAllBytes();
            try{
                if(waitBeforeHeaders)release.await(5,TimeUnit.SECONDS);
                if(type!=null)exchange.getResponseHeaders().set("Content-Type",type);
                if(location!=null)exchange.getResponseHeaders().set("Location",location);
                exchange.sendResponseHeaders(status,response.length);
                if(waitInsideBody){exchange.getResponseBody().write(response,0,1);exchange.getResponseBody().flush();release.await(5,TimeUnit.SECONDS);exchange.getResponseBody().write(response,1,response.length-1);}
                else exchange.getResponseBody().write(response);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}catch(java.io.IOException cancelled){}finally{exchange.close();}
        });
        server.createContext("/redirect-target",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.start();origin="http://127.0.0.1:"+server.getAddress().getPort();client=new EngineDepositClient(origin,2000,"isolated-engine-service-token-32-bytes");
    }
    @AfterEach void stop(){release.countDown();if(client!=null)client.close();if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();}
    String reply(String code){return json.writeValueAsString(Map.of("code",code,"deposit_id",id));}
    void reply(int status,String code){this.status=status;response=reply(code).getBytes(StandardCharsets.UTF_8);}
    EngineDepositClient.Credit credit(){return new EngineDepositClient.Credit(id,3,"BTC",new BigDecimal("0.123456780"));}
    EngineDepositClient.Outcome call(){return client.credit(credit());}
    void unknown(){assertEquals(EngineDepositClient.Outcome.OUTCOME_UNKNOWN,call());}
    @Test void sendsExactStableFinancialContentAndServiceIdentity(){assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,call());assertEquals("POST",method);assertEquals("/api/cex/deposits/credits",path);assertEquals("isolated-engine-service-token-32-bytes",token);var body=json.readTree(sent);assertEquals(id.toString(),body.path("deposit_id").textValue());assertEquals(3,body.path("user_id").longValue());assertEquals("BTC",body.path("asset").textValue());assertTrue(body.path("amount").isTextual());assertEquals("0.12345678",body.path("amount").textValue());assertEquals(4,body.size());assertEquals(1,requests.get());}
    @Test void financialInputIsNotCopiedIntoDebugOutput(){String debug=credit().toString();assertFalse(debug.contains(id.toString()));assertFalse(debug.contains("0.123456780"));assertFalse(debug.contains("BTC"));}
    @Test void explicitRetryKeepsTheSameIdentityAndBody(){assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,call());byte[] original=sent.clone();assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,call());assertArrayEquals(original,sent);assertEquals(2,requests.get());}
    @Test void onlyExactDurableRejectionIsAConfirmedRejection(){reply(409,"DURABLY_REJECTED");assertEquals(EngineDepositClient.Outcome.DURABLY_REJECTED,call());}
    @Test void onlyExplicitThisAttemptNotAcceptedHasThatMeaning(){reply(503,"NOT_ACCEPTED");assertEquals(EngineDepositClient.Outcome.NOT_ACCEPTED,call());}
    @Test void explicitUnknownDoesNotBecomeARejection(){reply(504,"OUTCOME_UNKNOWN");unknown();}
    @Test void mismatchedStatusAndCodeNeverConfirmsAnything(){for(int status:List.of(200,201,202,204,400,401,403,404,409,429,500,503,504)){reply(status,"OUTCOME_UNKNOWN");unknown();}reply(200,"NOT_ACCEPTED");unknown();reply(409,"DURABLY_CREDITED");unknown();reply(503,"DURABLY_REJECTED");unknown();}
    @Test void anotherDepositsReplyCannotConfirmThisIntent(){response=reply("DURABLY_CREDITED").replace(id.toString(),UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8);unknown();}
    @Test void missingIdentityOrCodeIsUnknown(){for(String raw:List.of("{}","{\"code\":\"DURABLY_CREDITED\"}","{\"deposit_id\":\""+id+"\"}")){response=raw.getBytes(StandardCharsets.UTF_8);unknown();}}
    @Test void unknownAndNullCodesAreNotCompatibilityFallbacks(){for(String raw:List.of(reply("accepted"),reply("DURABLY_CREDITED").replace("\"DURABLY_CREDITED\"","null"))){response=raw.getBytes(StandardCharsets.UTF_8);unknown();}}
    @Test void duplicateFieldsAndTrailingDocumentsAreUnknown(){for(String raw:List.of(reply("DURABLY_CREDITED")+"{}",reply("DURABLY_CREDITED").replace("\"code\":","\"code\":\"NOT_ACCEPTED\",\"code\":"))){response=raw.getBytes(StandardCharsets.UTF_8);unknown();}}
    @Test void unexpectedFieldsAndWrongJsonTypesAreUnknown(){for(String raw:List.of(reply("DURABLY_CREDITED").replace("}",",\"extra\":true}"),"[]","null","true",reply("DURABLY_CREDITED").replace("\""+id+"\"","3"))){response=raw.getBytes(StandardCharsets.UTF_8);unknown();}}
    @Test void malformedUtf8IsNotSilentlyReplaced(){response=new byte[]{(byte)0xc3,(byte)0x28};unknown();}
    @Test void nonJsonContentTypeCannotConfirmFinancialState(){for(String content:List.of("text/plain","text/html","application/jsonp")){type=content;unknown();}type=null;unknown();}
    @Test void declaredJsonWithCharsetIsSupported(){type="Application/JSON; charset=UTF-8";assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,call());}
    @Test void oversizedResponseIsCancelledAndRemainsUnknown(){response=new byte[5000];Arrays.fill(response,(byte)'x');unknown();assertEquals(1,requests.get());}
    @Test void globalProxyCannotReceiveTheInternalServiceKey(){
        var previous=java.net.ProxySelector.getDefault();var selections=new AtomicInteger();client.close();
        try{
            java.net.ProxySelector.setDefault(new java.net.ProxySelector(){
                @Override public List<java.net.Proxy> select(java.net.URI uri){selections.incrementAndGet();return List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",1)));}
                @Override public void connectFailed(java.net.URI uri,java.net.SocketAddress address,java.io.IOException failure){}
            });
            // 기본 선택기가 바뀐 뒤 새 클라이언트를 만들어 전역 설정을 실제로 상속할 위험을 재현한다.
            client=new EngineDepositClient(origin,2000,"isolated-engine-service-token-32-bytes");assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,call());assertEquals(0,selections.get());assertEquals(1,requests.get());
        }finally{java.net.ProxySelector.setDefault(previous);}
    }
    @Test void redirectDoesNotForwardTheServiceSecret(){status=307;location=origin+"/redirect-target";unknown();assertEquals(1,requests.get());assertEquals(0,redirected.get());}
    @Test void deadlineIncludesWaitingForResponseHeaders(){client.close();client=new EngineDepositClient(origin,500,"isolated-engine-service-token-32-bytes");waitBeforeHeaders=true;unknown();assertEquals(1,requests.get());}
    @Test void deadlineIncludesTheIncompleteBody(){client.close();client=new EngineDepositClient(origin,500,"isolated-engine-service-token-32-bytes");waitInsideBody=true;unknown();assertEquals(1,requests.get());}
    @Test void connectionFailureDoesNotProveNoPreviousExecution(){server.stop(0);unknown();assertEquals(0,requests.get());}
    @Test void interruptedCallerRetainsTheInterruptFlag(){Thread.currentThread().interrupt();try{unknown();assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}}
    @Test void databaseTransactionCannotHoldLocksAcrossTransport(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,this::call);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}assertEquals(0,requests.get());}
    @Test void invalidIdentityAndAssetsNeverReachTheWire(){for(var invalid:List.of(new EngineDepositClient.Credit(new UUID(0,0),3,"BTC",BigDecimal.ONE),new EngineDepositClient.Credit(id,0,"BTC",BigDecimal.ONE),new EngineDepositClient.Credit(id,3,"btc",BigDecimal.ONE),new EngineDepositClient.Credit(id,3," BTC",BigDecimal.ONE),new EngineDepositClient.Credit(id,3,"X".repeat(129),BigDecimal.ONE))){assertThrows(IllegalArgumentException.class,()->client.credit(invalid));}assertThrows(IllegalArgumentException.class,()->client.credit(null));assertEquals(0,requests.get());}
    @Test void zeroNegativePrecisionAndRangeFailuresNeverReachTheWire(){for(var amount:List.of(BigDecimal.ZERO,BigDecimal.ONE.negate(),new BigDecimal("0.0000000001"),new BigDecimal("1000000000000000000000"),new BigDecimal("999999999999999999999.999999999"),new BigDecimal("1E-2147483647"))){assertThrows(IllegalArgumentException.class,()->client.credit(new EngineDepositClient.Credit(id,3,"BTC",amount)));}assertEquals(0,requests.get());}
    @Test void largeValidIntegerIsSerializedWithoutExponent(){assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,client.credit(new EngineDepositClient.Credit(id,3,"BTC",new BigDecimal("1E+20"))));assertEquals("100000000000000000000",json.readTree(sent).path("amount").textValue());}
    @Test void credentialsQueryFragmentAndPathInEndpointAreRejected(){for(String origin:List.of("http://user:secret@127.0.0.1:1","http://127.0.0.1:1/path","http://127.0.0.1:1?token=secret","http://127.0.0.1:1#fragment","ftp://127.0.0.1","relative","http:/missing-host")){assertThrows(IllegalArgumentException.class,()->new EngineDepositClient(origin,1000,"isolated-engine-service-token-32-bytes"));}}
    @Test void invalidTimeoutAndSecretFailAtConstruction(){for(int timeout:List.of(0,-1))assertThrows(IllegalArgumentException.class,()->new EngineDepositClient(origin,timeout,"isolated-engine-service-token-32-bytes"));for(String token:List.of("short","x".repeat(1025),"x".repeat(32)+"\n"))assertThrows(IllegalArgumentException.class,()->new EngineDepositClient(origin,1000,token));}
}
