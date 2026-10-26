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
class EngineWithdrawalClientTest {
    final ObjectMapper json=new ObjectMapper();
    final UUID id=UUID.randomUUID();
    HttpServer server;
    ExecutorService executor;
    EngineWithdrawalClient client;
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
        response=reply("DURABLY_RESERVED").getBytes(StandardCharsets.UTF_8);
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
        server.start();origin="http://127.0.0.1:"+server.getAddress().getPort();client=new EngineWithdrawalClient(origin,2000,"isolated-engine-service-token-32-bytes");
    }
    @AfterEach void stop(){release.countDown();if(client!=null)client.close();if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();}
    String reply(String code){return json.writeValueAsString(Map.of("code",code,"withdrawal_id",id,"action","reserve"));}
    void reply(int status,String code){this.status=status;response=reply(code).getBytes(StandardCharsets.UTF_8);}
    EngineWithdrawalClient.Command command(){return new EngineWithdrawalClient.Command(id,3,"BTC",new BigDecimal("0.123456780"),EngineWithdrawalClient.Action.RESERVE);}
    EngineWithdrawalClient.Outcome call(){return client.execute(command());}
    void unknown(){assertEquals(EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN,call());}
    @Test void sendsExactStableFinancialContentAndServiceIdentity(){assertEquals(EngineWithdrawalClient.Outcome.DURABLY_RESERVED,call());assertEquals("POST",method);assertEquals("/api/cex/withdrawals/reservations",path);assertEquals("isolated-engine-service-token-32-bytes",token);var body=json.readTree(sent);assertEquals(id.toString(),body.path("withdrawal_id").stringValue());assertEquals(3,body.path("user_id").longValue());assertEquals("BTC",body.path("asset").stringValue());assertTrue(body.path("amount").isString());assertEquals("0.12345678",body.path("amount").stringValue());assertEquals(5,body.size());assertEquals("reserve",body.path("action").stringValue());assertEquals(1,requests.get());}
    @Test void financialInputIsNotCopiedIntoDebugOutput(){String debug=command().toString();assertFalse(debug.contains(id.toString()));assertFalse(debug.contains("0.123456780"));assertFalse(debug.contains("BTC"));}
    @Test void explicitRetryKeepsTheSameIdentityAndBody(){assertEquals(EngineWithdrawalClient.Outcome.DURABLY_RESERVED,call());byte[] original=sent.clone();assertEquals(EngineWithdrawalClient.Outcome.DURABLY_RESERVED,call());assertArrayEquals(original,sent);assertEquals(2,requests.get());}
    @Test void onlyExactDurableRejectionIsAConfirmedRejection(){reply(409,"DURABLY_REJECTED");assertEquals(EngineWithdrawalClient.Outcome.DURABLY_REJECTED,call());}
    @Test void onlyExplicitThisAttemptNotAcceptedHasThatMeaning(){reply(503,"NOT_ACCEPTED");assertEquals(EngineWithdrawalClient.Outcome.NOT_ACCEPTED,call());}
    @Test void explicitUnknownDoesNotBecomeARejection(){reply(504,"OUTCOME_UNKNOWN");unknown();}
    @Test void mismatchedStatusAndCodeNeverConfirmsAnything(){for(int status:List.of(200,201,202,204,400,401,403,404,409,429,500,503,504)){reply(status,"OUTCOME_UNKNOWN");unknown();}reply(200,"NOT_ACCEPTED");unknown();reply(409,"DURABLY_RESERVED");unknown();reply(503,"DURABLY_REJECTED");unknown();}
    @Test void anotherWithdrawalsReplyCannotConfirmThisIntent(){response=reply("DURABLY_RESERVED").replace(id.toString(),UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8);unknown();}
    @Test void missingIdentityOrCodeIsUnknown(){for(String raw:List.of("{}","{\"code\":\"DURABLY_RESERVED\"}","{\"withdrawal_id\":\""+id+"\"}")){response=raw.getBytes(StandardCharsets.UTF_8);unknown();}}
    @Test void unknownAndNullCodesAreNotCompatibilityFallbacks(){for(String raw:List.of(reply("accepted"),reply("DURABLY_RESERVED").replace("\"DURABLY_RESERVED\"","null"))){response=raw.getBytes(StandardCharsets.UTF_8);unknown();}}
    @Test void duplicateFieldsAndTrailingDocumentsAreUnknown(){for(String raw:List.of(reply("DURABLY_RESERVED")+"{}",reply("DURABLY_RESERVED").replace("\"code\":","\"code\":\"NOT_ACCEPTED\",\"code\":"))){response=raw.getBytes(StandardCharsets.UTF_8);unknown();}}
    @Test void unexpectedFieldsAndWrongJsonTypesAreUnknown(){for(String raw:List.of(reply("DURABLY_RESERVED").replace("}",",\"extra\":true}"),"[]","null","true",reply("DURABLY_RESERVED").replace("\""+id+"\"","3"))){response=raw.getBytes(StandardCharsets.UTF_8);unknown();}}
    @Test void malformedUtf8IsNotSilentlyReplaced(){response=new byte[]{(byte)0xc3,(byte)0x28};unknown();}
    @Test void nonJsonContentTypeCannotConfirmFinancialState(){for(String content:List.of("text/plain","text/html","application/jsonp")){type=content;unknown();}type=null;unknown();}
    @Test void declaredJsonWithCharsetIsSupported(){type="Application/JSON; charset=UTF-8";assertEquals(EngineWithdrawalClient.Outcome.DURABLY_RESERVED,call());}
    @Test void oversizedResponseIsCancelledAndRemainsUnknown(){response=new byte[5000];Arrays.fill(response,(byte)'x');unknown();assertEquals(1,requests.get());}
    @Test void globalProxyCannotReceiveTheInternalServiceKey(){
        var previous=java.net.ProxySelector.getDefault();var selections=new AtomicInteger();client.close();
        try{
            java.net.ProxySelector.setDefault(new java.net.ProxySelector(){
                @Override public List<java.net.Proxy> select(java.net.URI uri){selections.incrementAndGet();return List.of(new java.net.Proxy(java.net.Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",1)));}
                @Override public void connectFailed(java.net.URI uri,java.net.SocketAddress address,java.io.IOException failure){}
            });
            // 기본 선택기가 바뀐 뒤 새 클라이언트를 만들어 전역 설정을 실제로 상속할 위험을 재현한다.
            client=new EngineWithdrawalClient(origin,2000,"isolated-engine-service-token-32-bytes");assertEquals(EngineWithdrawalClient.Outcome.DURABLY_RESERVED,call());assertEquals(0,selections.get());assertEquals(1,requests.get());
        }finally{java.net.ProxySelector.setDefault(previous);}
    }
    @Test void redirectDoesNotForwardTheServiceSecret(){status=307;location=origin+"/redirect-target";unknown();assertEquals(1,requests.get());assertEquals(0,redirected.get());}
    @Test void deadlineIncludesWaitingForResponseHeaders(){client.close();client=new EngineWithdrawalClient(origin,500,"isolated-engine-service-token-32-bytes");waitBeforeHeaders=true;unknown();assertEquals(1,requests.get());}
    @Test void deadlineIncludesTheIncompleteBody(){client.close();client=new EngineWithdrawalClient(origin,500,"isolated-engine-service-token-32-bytes");waitInsideBody=true;unknown();assertEquals(1,requests.get());}
    @Test void connectionFailureDoesNotProveNoPreviousExecution(){server.stop(0);unknown();assertEquals(0,requests.get());}
    @Test void interruptedCallerRetainsTheInterruptFlag(){Thread.currentThread().interrupt();try{unknown();assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}}
    @Test void databaseTransactionCannotHoldLocksAcrossTransport(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,this::call);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}assertEquals(0,requests.get());}
    @Test void invalidIdentityAndAssetsNeverReachTheWire(){for(var invalid:List.of(new EngineWithdrawalClient.Command(new UUID(0,0),3,"BTC",BigDecimal.ONE,EngineWithdrawalClient.Action.RESERVE),new EngineWithdrawalClient.Command(id,0,"BTC",BigDecimal.ONE,EngineWithdrawalClient.Action.RESERVE),new EngineWithdrawalClient.Command(id,3,"btc",BigDecimal.ONE,EngineWithdrawalClient.Action.RESERVE),new EngineWithdrawalClient.Command(id,3," BTC",BigDecimal.ONE,EngineWithdrawalClient.Action.RESERVE),new EngineWithdrawalClient.Command(id,3,"X".repeat(129),BigDecimal.ONE,EngineWithdrawalClient.Action.RESERVE))){assertThrows(IllegalArgumentException.class,()->client.execute(invalid));}assertThrows(IllegalArgumentException.class,()->client.execute(null));assertEquals(0,requests.get());}
    @Test void zeroNegativePrecisionAndRangeFailuresNeverReachTheWire(){for(var amount:List.of(BigDecimal.ZERO,BigDecimal.ONE.negate(),new BigDecimal("0.0000000001"),new BigDecimal("1000000000000000000000"),new BigDecimal("999999999999999999999.999999999"),new BigDecimal("1E-2147483647"))){assertThrows(IllegalArgumentException.class,()->client.execute(new EngineWithdrawalClient.Command(id,3,"BTC",amount,EngineWithdrawalClient.Action.RESERVE)));}assertEquals(0,requests.get());}
    @Test void largeValidIntegerIsSerializedWithoutExponent(){assertEquals(EngineWithdrawalClient.Outcome.DURABLY_RESERVED,client.execute(new EngineWithdrawalClient.Command(id,3,"BTC",new BigDecimal("1E+20"),EngineWithdrawalClient.Action.RESERVE)));assertEquals("100000000000000000000",json.readTree(sent).path("amount").stringValue());}
    @Test void credentialsQueryFragmentAndPathInEndpointAreRejected(){for(String origin:List.of("http://user:secret@127.0.0.1:1","http://127.0.0.1:1/path","http://127.0.0.1:1?token=secret","http://127.0.0.1:1#fragment","ftp://127.0.0.1","relative","http:/missing-host")){assertThrows(IllegalArgumentException.class,()->new EngineWithdrawalClient(origin,1000,"isolated-engine-service-token-32-bytes"));}}
    @Test void invalidTimeoutAndSecretFailAtConstruction(){for(int timeout:List.of(0,-1))assertThrows(IllegalArgumentException.class,()->new EngineWithdrawalClient(origin,timeout,"isolated-engine-service-token-32-bytes"));for(String token:List.of("short","x".repeat(1025),"x".repeat(32)+"\n"))assertThrows(IllegalArgumentException.class,()->new EngineWithdrawalClient(origin,1000,token));}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(EngineWithdrawalClient.Action.class)
    void everyStageUsesItsOwnActionAndDurableCode(EngineWithdrawalClient.Action action){
        var expected=switch(action){case RESERVE->EngineWithdrawalClient.Outcome.DURABLY_RESERVED;case RELEASE->EngineWithdrawalClient.Outcome.DURABLY_RELEASED;case COMPLETE->EngineWithdrawalClient.Outcome.DURABLY_COMPLETED;};
        response=json.writeValueAsBytes(Map.of("code",expected.name(),"withdrawal_id",id,"action",action.wire()));
        assertEquals(expected,client.execute(new EngineWithdrawalClient.Command(id,3,"BTC",new BigDecimal("0.01"),action)));assertEquals(action.wire(),json.readTree(sent).path("action").stringValue());
    }
    @Test void anotherStageSuccessCannotConfirmTheCurrentStage(){
        for(var action:EngineWithdrawalClient.Action.values()){
            var expected=switch(action){case RESERVE->"DURABLY_RESERVED";case RELEASE->"DURABLY_RELEASED";case COMPLETE->"DURABLY_COMPLETED";};
            for(var other:EngineWithdrawalClient.Action.values()){
                if(other==action)continue;
                response=json.writeValueAsBytes(Map.of("code",expected,"withdrawal_id",id,"action",other.wire()));
                assertEquals(EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN,client.execute(new EngineWithdrawalClient.Command(id,3,"BTC",BigDecimal.ONE,action)));
                var wrong=switch(other){case RESERVE->"DURABLY_RESERVED";case RELEASE->"DURABLY_RELEASED";case COMPLETE->"DURABLY_COMPLETED";};
                response=json.writeValueAsBytes(Map.of("code",wrong,"withdrawal_id",id,"action",action.wire()));
                assertEquals(EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN,client.execute(new EngineWithdrawalClient.Command(id,3,"BTC",BigDecimal.ONE,action)));
            }
        }
    }
    @Test void missingNullOrNonTextualActionIsUnknown(){
        for(String raw:List.of(json.writeValueAsString(Map.of("code","DURABLY_RESERVED","withdrawal_id",id)),reply("DURABLY_RESERVED").replace("\"reserve\"","null"),reply("DURABLY_RESERVED").replace("\"reserve\"","1"))){response=raw.getBytes(StandardCharsets.UTF_8);unknown();}
    }
    @Test void nullActionCannotReachTheWire(){assertThrows(IllegalArgumentException.class,()->client.execute(new EngineWithdrawalClient.Command(id,3,"BTC",BigDecimal.ONE,null)));assertEquals(0,requests.get());}

    @Test void anotherStageRejectionOrNotAcceptedCannotConfirmThisCommand(){
        for(int status:List.of(409,503)){
            this.status=status;response=json.writeValueAsBytes(Map.of("code",status==409?"DURABLY_REJECTED":"NOT_ACCEPTED","withdrawal_id",id,"action","release"));unknown();
        }
    }

}
