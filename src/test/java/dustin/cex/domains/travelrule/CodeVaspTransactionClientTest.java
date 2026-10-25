package dustin.cex.domains.travelrule;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

/** 소유 모듈과 게이트웨이가 실제 서명 바이트·거래 결합·결과 구분을 검사한다. 외부 공급사/블록체인에는 접속하지 않는다. */
class CodeVaspTransactionClientTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    final UUID id=UUID.randomUUID();final String txid="a".repeat(64),address="bc1q"+"a".repeat(38);
    HttpServer cipher,gateway;ExecutorService workers;CodeVaspTransport transport;CodeVaspTransactionClient client;KeyPair pair;String key;
    volatile String mode="NORMAL";volatile String outcome="FOUND";
    final AtomicInteger cipherCalls=new AtomicInteger(),gatewayCalls=new AtomicInteger();final AtomicLong nonce=new AtomicLong(100);
    final AtomicReference<Throwable> callbackFailure=new AtomicReference<>();final AtomicReference<String> signedBody=new AtomicReference<>();final CountDownLatch release=new CountDownLatch(1);
    CodeVaspTransactionClient.Request request(){return new CodeVaspTransactionClient.Request(id,txid,address);}
    @BeforeEach void start()throws Exception{
        pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();byte[] encoded=pair.getPublic().getEncoded();key=Base64.getEncoder().encodeToString(Arrays.copyOfRange(encoded,12,encoded.length));
        workers=Executors.newVirtualThreadPerTaskExecutor();cipher=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);gateway=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);cipher.setExecutor(workers);gateway.setExecutor(workers);
        cipher.createContext("/api/v1/code/api-payloads",exchange->{try{
            cipherCalls.incrementAndGet();assertEquals("POST",exchange.getRequestMethod());var command=JSON.readTree(exchange.getRequestBody().readAllBytes());assertEquals("CODE",command.path("allianceName").asString());
            boolean post=command.path("apiType").asString().equals("SEARCH_TRANSACTION_ID");
            if(post){assertEquals(3,command.path("request").size());assertEquals(id.toString(),command.path("request").path("requestId").asString());assertEquals(txid,command.path("request").path("txid").asString());assertEquals(address,command.path("request").path("beneficiaryAddress").asString());}
            else{assertEquals("FETCH_TRANSACTION_ID",command.path("apiType").asString());assertFalse(command.has("request"));}
            var body=post?command.path("request").deepCopy():null;
            if(post)switch(mode){
                case "WRONG_TXID"->((ObjectNode)body).put("txid","b".repeat(64));
                case "WRONG_ADDRESS"->((ObjectNode)body).put("beneficiaryAddress","bc1q"+"b".repeat(38));
                case "WRONG_REQUEST"->((ObjectNode)body).put("requestId",UUID.randomUUID().toString());
                case "EXTRA_FIELD"->((ObjectNode)body).put("callbackUrl","https://example.invalid");
                case "MISSING_FIELD"->((ObjectNode)body).remove("beneficiaryAddress");
            }
            // 필드 사이 공백을 넣어 제품이 재직렬화하지 않고 서명 원문을 전달하는지 검사한다.
            String raw=post?JSON.writeValueAsString(body).replace(",",", "):"";
            if(mode.equals("DUPLICATE_SIGNED_FIELD")&&post)raw=raw.substring(0,raw.length()-1)+",\"txid\":\""+txid+"\"}";
            signedBody.set(raw);String date=Instant.now().toString();long sequence=nonce.incrementAndGet();var signature=Signature.getInstance("Ed25519");signature.initSign(pair.getPrivate());signature.update((date+raw+sequence).getBytes(StandardCharsets.UTF_8));
            var answer=JSON.createObjectNode().put("signature",Base64.getEncoder().encodeToString(signature.sign())).put("nonce",sequence).put("dateTime",date).put("publicKey",key);
            if(post)answer.put("body",raw);if(mode.equals("NON_STRING_BODY"))answer.put("body",4);
            send(exchange,200,JSON.writeValueAsString(answer));
        }catch(Throwable failure){callbackFailure.compareAndSet(null,failure);send(exchange,500,"격리 서명 시험 실패");}});
        gateway.createContext("/v1/code/verification/tx",exchange->{try{
            gatewayCalls.incrementAndGet();boolean post=exchange.getRequestMethod().equals("POST");assertEquals(post?"/v1/code/verification/tx":"/v1/code/verification/tx/"+id,exchange.getRequestURI().toString());
            String raw=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertEquals(post?signedBody.get():"",raw);assertEquals("code:cex-query",exchange.getRequestHeaders().getFirst("X-Request-Origin"));assertEquals(key,exchange.getRequestHeaders().getFirst("X-Code-Req-PubKey"));
            var signature=Signature.getInstance("Ed25519");signature.initVerify(pair.getPublic());signature.update((exchange.getRequestHeaders().getFirst("X-Code-Req-Datetime")+raw+exchange.getRequestHeaders().getFirst("X-Code-Req-Nonce")).getBytes(StandardCharsets.UTF_8));assertTrue(signature.verify(Base64.getDecoder().decode(exchange.getRequestHeaders().getFirst("X-Code-Req-Signature"))));
            if(mode.equals("REDIRECT")){exchange.getResponseHeaders().set("Location",cipherBase()+"/api/v1/code/api-payloads");send(exchange,302,"비공개 리다이렉트 원문");return;}
            if(mode.equals("ERROR")){send(exchange,429,"민감한 공급사 오류 원문");return;}
            if(mode.equals("SUBMIT_SLOW")){try(exchange){exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();assertTrue(release.await(10,TimeUnit.SECONDS));}catch(java.io.IOException closed){}return;}
            var result=JSON.createObjectNode().put("requestId",mode.equals("WRONG_RESPONSE_ID")?UUID.randomUUID().toString():id.toString());
            if(post)result.put("result",mode.equals("BAD_ACK")?"valid":"SUCCESS");
            else{
                boolean found=outcome.equals("FOUND");result.put("result",found?"valid":"invalid");
                if(found)result.put("vaspEntityId","origin");else result.put("reasonType",outcome).put("reasonMsg","개인정보가 섞인 공급사 원문");
                var analysis=result.putArray("analysisResult");if(found)analysis.addObject().put("entityId","origin").put("result","FOUND");
                switch(mode){
                    case "MISMATCHED_ORIGIN"->result.put("vaspEntityId","other");
                    case "DUPLICATE_ORIGIN"->analysis.add(analysis.get(0).deepCopy());
                    case "MULTIPLE_FOUND"->analysis.addObject().put("entityId","other").put("result","FOUND");
                    case "UNKNOWN_ANALYSIS"->analysis.addObject().put("entityId","other").put("result","NOT_FOUND_ADDRESS");
                    case "MISSING_ANALYSIS"->result.remove("analysisResult");
                    case "NOT_FOUND_BUT_FOUND"->analysis.addObject().put("entityId","origin").put("result","FOUND");
                    case "BAD_ANALYSIS_TYPE"->result.put("analysisResult","not-array");
                    case "UNKNOWN_RESULT"->result.put("result","ok");
                }
            }
            String payload=JSON.writeValueAsString(result);if(mode.equals("DUPLICATE_RESPONSE_FIELD"))payload=payload.substring(0,payload.length()-1)+",\"requestId\":\""+id+"\"}";send(exchange,200,payload);
        }catch(Throwable failure){callbackFailure.compareAndSet(null,failure);send(exchange,500,"격리 게이트웨이 시험 실패");}});
        cipher.start();gateway.start();transport=new CodeVaspTransport(new CodeVaspSettings(gatewayBase(),cipherBase(),"cex-query",key));client=new CodeVaspTransactionClient(transport);
    }
    String cipherBase(){return "http://127.0.0.1:"+cipher.getAddress().getPort();}String gatewayBase(){return "http://127.0.0.1:"+gateway.getAddress().getPort();}
    void send(HttpExchange e,int status,String payload)throws java.io.IOException{byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);try(e){e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);}}
    @AfterEach void close(){release.countDown();if(transport!=null)transport.close();if(cipher!=null)cipher.stop(0);if(gateway!=null)gateway.stop(0);if(workers!=null)workers.close();assertNull(callbackFailure.get());}
    void unavailable(Runnable action){var e=assertThrows(ResponseStatusException.class,action::run);assertEquals(503,e.getStatusCode().value());assertEquals("이전거래 공급사 요청을 완료할 수 없습니다",e.getReason());assertNull(e.getCause());}
    @Test void signedSubmissionPreservesExactBodyAndStableRequestIdentity(){client.submit(request());assertTrue(signedBody.get().contains(", "));client.submit(request());assertEquals(2,cipherCalls.get());assertEquals(2,gatewayCalls.get());assertFalse(request().toString().contains(address));}
    @Test void foundResultIsBoundToRequestedIdAndOneConsistentOrigin(){var result=client.result(id);assertEquals(id,result.requestId());assertEquals(CodeVaspTransactionClient.Status.FOUND,result.status());assertEquals("origin",result.entityId());assertEquals(1,result.analysis().size());assertEquals(1,cipherCalls.get());assertEquals(1,gatewayCalls.get());}
    @Test void unresolvedResultsRemainSeparateWithoutClaimingPersonalWalletOrFinancialApproval(){
        var statuses=Map.of("PROCESSING",CodeVaspTransactionClient.Status.PROCESSING,"NOT_FOUND_TXID",CodeVaspTransactionClient.Status.NOT_FOUND,"LACK_OF_INFORMATION",CodeVaspTransactionClient.Status.UNKNOWN_REQUEST,"ALLIANCE_DATA_NON_UNIQUE_RESULT",CodeVaspTransactionClient.Status.AMBIGUOUS);
        statuses.forEach((reason,status)->{outcome=reason;var result=client.result(id);assertEquals(status,result.status());assertNull(result.entityId());assertFalse(result.toString().contains("개인정보"));});
    }
    @Test void mismatchedSignedBodyNeverReachesGateway(){for(String invalid:List.of("WRONG_TXID","WRONG_ADDRESS","WRONG_REQUEST","EXTRA_FIELD","MISSING_FIELD","NON_STRING_BODY","DUPLICATE_SIGNED_FIELD")){mode=invalid;unavailable(()->client.submit(request()));}assertEquals(0,gatewayCalls.get());}
    @Test void acknowledgementMustEchoSuccessAndSameRequestId(){for(String invalid:List.of("BAD_ACK","WRONG_RESPONSE_ID","DUPLICATE_RESPONSE_FIELD")){mode=invalid;unavailable(()->client.submit(request()));}assertEquals(3,gatewayCalls.get());}
    @Test void inconsistentOrUnknownResultsNeverBecomeFound(){for(String invalid:List.of("WRONG_RESPONSE_ID","MISMATCHED_ORIGIN","DUPLICATE_ORIGIN","MULTIPLE_FOUND","UNKNOWN_ANALYSIS","MISSING_ANALYSIS","BAD_ANALYSIS_TYPE","UNKNOWN_RESULT","DUPLICATE_RESPONSE_FIELD")){mode=invalid;unavailable(()->client.result(id));}}
    @Test void notFoundWithPositiveAnalysisAndUnknownReasonsAreRejected(){outcome="NOT_FOUND_TXID";mode="NOT_FOUND_BUT_FOUND";unavailable(()->client.result(id));outcome="NEW_REASON";mode="NORMAL";unavailable(()->client.result(id));}
    @Test void invalidInputNeverRequestsSigningMaterial(){for(String bad:List.of("0x"+txid,txid.toUpperCase(),"x".repeat(64),"a".repeat(63)))assertThrows(IllegalArgumentException.class,()->new CodeVaspTransactionClient.Request(id,bad,address));assertThrows(IllegalArgumentException.class,()->new CodeVaspTransactionClient.Request(null,txid,address));assertThrows(IllegalArgumentException.class,()->new CodeVaspTransactionClient.Request(id,txid,"bad/address"));assertThrows(NullPointerException.class,()->client.submit(null));assertThrows(NullPointerException.class,()->client.result(null));assertEquals(0,cipherCalls.get());}
    @Test void databaseTransactionCannotSubmitOrPoll(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->client.submit(request()));assertThrows(IllegalStateException.class,()->client.result(id));}finally{TransactionSynchronizationManager.clear();}assertEquals(0,cipherCalls.get());}
    @Test void providerErrorsAreNotRetriedOrExposed(){mode="ERROR";unavailable(()->client.submit(request()));unavailable(()->client.result(id));assertEquals(2,cipherCalls.get());assertEquals(2,gatewayCalls.get());}
    @Test void submissionTimeoutDoesNotProveRequestWasNeverReceived(){mode="SUBMIT_SLOW";long started=System.nanoTime();unavailable(()->client.submit(request()));long elapsed=Duration.ofNanos(System.nanoTime()-started).toSeconds();assertTrue(elapsed>=4&&elapsed<8);assertEquals(1,gatewayCalls.get());assertEquals(1,cipherCalls.get());}
    @Test void submissionRedirectIsNotFollowed(){mode="REDIRECT";unavailable(()->client.submit(request()));assertEquals(1,cipherCalls.get());assertEquals(1,gatewayCalls.get());}
}
