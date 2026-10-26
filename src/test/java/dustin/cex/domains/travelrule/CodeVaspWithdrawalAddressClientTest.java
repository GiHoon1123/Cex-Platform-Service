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
import tools.jackson.databind.json.JsonMapper;

/** 소유 HTTP 서버로 지급 주소 소속 조회의 최소 공개·서명·응답/실패 경계를 검증한다. */
class CodeVaspWithdrawalAddressClientTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String ADDRESS="bcrt1q"+"a".repeat(38);
    final String ciphertext=Base64.getEncoder().encodeToString(new byte[64]);
    HttpServer module,gateway;ExecutorService workers;CodeVaspTransport transport;CodeVaspWithdrawalAddressClient client;
    KeyPair pair;String ownKey,peerKey;volatile String mode="NORMAL";
    final AtomicLong nonce=new AtomicLong();final AtomicInteger signs=new AtomicInteger(),sent=new AtomicInteger(),decrypts=new AtomicInteger();
    final AtomicReference<Throwable> callbackFailure=new AtomicReference<>();final AtomicReference<String> signedBody=new AtomicReference<>();
    final CountDownLatch release=new CountDownLatch(1);
    CodeVaspDirectoryClient.Key key(){return new CodeVaspDirectoryClient.Key(peerKey,Instant.now().plusSeconds(3600));}
    CodeVaspDirectoryClient.Vasp peer(CodeVaspDirectoryClient.Key k){return new CodeVaspDirectoryClient.Vasp("counterparty","가상 수취 거래소","가상 수취 사업자","KR","CODE",true,List.of(k));}
    CodeVaspWithdrawalAddressClient.Request request(){var k=key();return new CodeVaspWithdrawalAddressClient.Request(ADDRESS,peer(k),k);}
    CodeVaspCounterpartyPolicy approved(){return new CodeVaspCounterpartyPolicy(JSON.writeValueAsString(List.of(Map.of("entityId","counterparty","legalName","가상 수취 사업자","reviewReference","LOCAL-TEST-ONLY","evidenceSha256","a".repeat(64),"validFrom",Instant.now().minusSeconds(60).toString(),"validUntil",Instant.now().plusSeconds(60).toString()))));}
    @BeforeEach void start()throws Exception {
        pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();ownKey=Base64.getEncoder().encodeToString(Arrays.copyOfRange(pair.getPublic().getEncoded(),12,44));peerKey=Base64.getEncoder().encodeToString(new byte[32]);
        workers=Executors.newVirtualThreadPerTaskExecutor();module=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);gateway=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        module.setExecutor(workers);gateway.setExecutor(workers);
        module.createContext("/api/v1/code/api-payloads",e->{try {
            signs.incrementAndGet();assertEquals("POST",e.getRequestMethod());var command=JSON.readTree(e.getRequestBody().readAllBytes());
            assertEquals("CODE",command.path("allianceName").asText());assertEquals("SEARCH_ADDRESS",command.path("apiType").asText());assertEquals(peerKey,command.path("remotePublicKey").asText());
            var r=command.path("request");assertEquals(Set.of("currency","beneficiaryWalletAddress","address","network"),r.propertyNames());
            assertEquals("BTC",r.path("currency").asText());assertEquals("BTC",r.path("network").asText());assertEquals(ADDRESS,r.path("address").asText());assertEquals(ADDRESS,r.path("beneficiaryWalletAddress").asText());
            if(mode.equals("SIGN_SLOW")){slow(e);return;}
            var body=JSON.createObjectNode().put("currency","BTC").put("address",ADDRESS).put("network","BTC").put("payload",ciphertext);
            switch(mode){case "WRONG_ADDRESS"->body.put("address","bcrt1q"+"b".repeat(38));case "WRONG_NETWORK"->body.put("network","ETH");case "WRONG_CURRENCY"->body.put("currency","ETH");case "EXTRA_PII"->body.put("beneficiaryName","가상 개인정보");case "PLAIN_PAYLOAD"->body.put("payload",ADDRESS);case "SHORT_PAYLOAD"->body.put("payload","YWJj");case "MISSING_ADDRESS"->body.remove("address");}
            String raw=JSON.writeValueAsString(body).replace(",",", ");
            if(mode.equals("DUPLICATE_SIGNED_FIELD"))raw=raw.substring(0,raw.length()-1)+",\"currency\":\"BTC\"}";
            if(mode.equals("TRAILING_SIGNED_OBJECT"))raw+=" {}";
            signedBody.set(raw);String date=Instant.now().toString();long number=nonce.incrementAndGet();
            var signature=Signature.getInstance("Ed25519");signature.initSign(pair.getPrivate());signature.update((date+raw+number).getBytes(StandardCharsets.UTF_8));
            var response=JSON.createObjectNode().put("signature",Base64.getEncoder().encodeToString(signature.sign())).put("nonce",number).put("dateTime",date).put("publicKey",ownKey).put("body",raw);
            if(mode.equals("WRONG_SIGNER"))response.put("publicKey",peerKey);if(mode.equals("STALE_SIGNATURE"))response.put("dateTime",Instant.now().minusSeconds(120).toString());
            send(e,200,JSON.writeValueAsString(response));
        }catch(Throwable failure){callbackFailure.compareAndSet(null,failure);send(e,500,"격리 주소 서명 대역 실패");}});
        module.createContext("/api/v1/code/decrypt",e->{decrypts.incrementAndGet();send(e,500,"주소 소속 조회에서는 복호화를 요청하면 안 됩니다");});
        gateway.createContext("/v1/code/VerifyAddress/counterparty",e->{try {
            sent.incrementAndGet();assertEquals("POST",e.getRequestMethod());String raw=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertEquals(signedBody.get(),raw);
            assertEquals(peerKey,e.getRequestHeaders().getFirst("X-Code-Req-Remote-PubKey"));assertEquals(ownKey,e.getRequestHeaders().getFirst("X-Code-Req-PubKey"));assertEquals("code:cex-address",e.getRequestHeaders().getFirst("X-Request-Origin"));
            var signature=Signature.getInstance("Ed25519");signature.initVerify(pair.getPublic());signature.update((e.getRequestHeaders().getFirst("X-Code-Req-Datetime")+raw+e.getRequestHeaders().getFirst("X-Code-Req-Nonce")).getBytes(StandardCharsets.UTF_8));assertTrue(signature.verify(Base64.getDecoder().decode(e.getRequestHeaders().getFirst("X-Code-Req-Signature"))));
            if(mode.equals("GATEWAY_SLOW")){slow(e);return;}if(mode.equals("GATEWAY_ERROR")){send(e,503,"개인정보가 섞인 원문");return;}
            if(mode.equals("REDIRECT")){e.getResponseHeaders().set("Location",moduleBase()+"/api/v1/code/decrypt");send(e,302,"리다이렉트 원문");return;}
            var response=JSON.createObjectNode().put("result","valid").put("beneficiaryVaspEntityId","counterparty");
            switch(mode){case "OTHER_ENTITY"->response.put("beneficiaryVaspEntityId","other");case "MISSING_ENTITY"->response.remove("beneficiaryVaspEntityId");case "UNKNOWN_RESULT"->response.put("result","verified");case "CONTRADICTORY_REASON"->response.put("reasonType","SANCTION_LIST");case "EXTRA_REPLY_PII"->response.put("beneficiaryName","가상 개인정보");case "EMPTY_REASON"->response.put("reasonType","");}
            if(mode.startsWith("INVALID_"))response=JSON.createObjectNode().put("result","invalid").put("reasonType",mode.substring(8)).put("reasonMsg","개인정보가 섞인 상세 원문").put("beneficiaryVaspEntityId","");
            if(mode.equals("INVALID_OTHER_ENTITY"))response.put("reasonType","NOT_FOUND_ADDRESS").put("beneficiaryVaspEntityId","other");
            String answer=JSON.writeValueAsString(response);if(mode.equals("DUPLICATE_REPLY"))answer=answer.substring(0,answer.length()-1)+",\"result\":\"valid\"}";
            if(mode.equals("TRAILING_REPLY"))answer+=" {}";send(e,200,answer);
        }catch(Throwable failure){callbackFailure.compareAndSet(null,failure);send(e,500,"격리 주소 게이트웨이 대역 실패");}});
        module.start();gateway.start();transport=new CodeVaspTransport(new CodeVaspSettings(gatewayBase(),moduleBase(),"cex-address",ownKey));client=new CodeVaspWithdrawalAddressClient(transport,approved());
    }
    String moduleBase(){return "http://127.0.0.1:"+module.getAddress().getPort();}String gatewayBase(){return "http://127.0.0.1:"+gateway.getAddress().getPort();}
    void send(HttpExchange e,int status,String text)throws java.io.IOException{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);try(e){e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);}}
    void slow(HttpExchange e)throws Exception{try(e){e.sendResponseHeaders(200,0);e.getResponseBody().write('{');e.getResponseBody().flush();assertTrue(release.await(10,TimeUnit.SECONDS));}catch(java.io.IOException closed){}}
    @AfterEach void close(){release.countDown();if(transport!=null)transport.close();if(module!=null)module.stop(0);if(gateway!=null)gateway.stop(0);if(workers!=null)workers.close();assertEquals(0,decrypts.get());assertNull(callbackFailure.get());}
    void failure(CodeVaspSubmissionFailure.Admission expected){var error=assertThrows(CodeVaspSubmissionFailure.class,()->client.verify(request()));assertEquals(expected,error.admission());assertNull(error.getCause());assertFalse(error.toString().contains("원문"));assertFalse(error.toString().contains(ADDRESS));}

    @Test void signedMinimalAddressRequestChecksTheSelectedEntity(){var result=client.verify(request());assertEquals("VALID",result.status());assertEquals("counterparty",result.entityId());assertNull(result.reason());assertEquals(1,signs.get());assertEquals(1,sent.get());}
    @Test void validWithAnEmptyReasonIsStillOnlyAnAddressResult(){mode="EMPTY_REASON";assertEquals("VALID",client.verify(request()).status());}
    @Test void moduleCannotChangeTheBtcDestinationOrAddPrivateFields(){for(String bad:List.of("WRONG_ADDRESS","WRONG_NETWORK","WRONG_CURRENCY","EXTRA_PII","MISSING_ADDRESS")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.NOT_SENT);}assertEquals(0,sent.get());}
    @Test void unencryptedOrShortPayloadCannotReachTheGateway(){for(String bad:List.of("PLAIN_PAYLOAD","SHORT_PAYLOAD")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.NOT_SENT);}assertEquals(0,sent.get());}
    @Test void duplicateOrTrailingSignedJsonCannotReachTheGateway(){for(String bad:List.of("DUPLICATE_SIGNED_FIELD","TRAILING_SIGNED_OBJECT")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.NOT_SENT);}assertEquals(0,sent.get());}
    @Test void wrongSigningIdentityOrStaleTimestampIsKnownNotSent(){for(String bad:List.of("WRONG_SIGNER","STALE_SIGNATURE")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.NOT_SENT);}assertEquals(0,sent.get());}
    @Test void otherOrMissingBeneficiaryEntityCannotVerifyTheDestination(){for(String bad:List.of("OTHER_ENTITY","MISSING_ENTITY")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}}
    @Test void documentedInvalidReasonsHaveNoRawMessageOrEntity(){for(String reason:List.of("NOT_FOUND_ADDRESS","NOT_SUPPORTED_SYMBOL","NOT_KYC_USER","SANCTION_LIST","LACK_OF_INFORMATION","UNKNOWN")){mode="INVALID_"+reason;var result=client.verify(request());assertEquals("INVALID",result.status());assertEquals(reason,result.reason());assertNull(result.entityId());assertFalse(result.toString().contains("원문"));}}
    @Test void invalidReplyCannotSelectADifferentCounterparty(){mode="INVALID_OTHER_ENTITY";failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}
    @Test void unknownReasonAndUnknownResultStayUnconfirmed(){for(String bad:List.of("INVALID_NEW_REASON","UNKNOWN_RESULT")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}}
    @Test void contradictoryReasonOrPrivateReplyFieldCannotPass(){for(String bad:List.of("CONTRADICTORY_REASON","EXTRA_REPLY_PII")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}}
    @Test void duplicateAndTrailingResponseJsonStayUnconfirmed(){for(String bad:List.of("DUPLICATE_REPLY","TRAILING_REPLY")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}}
    @Test void unapprovedButReachablePeerCannotStartDisclosure(){client=new CodeVaspWithdrawalAddressClient(transport,new CodeVaspCounterpartyPolicy("[]"));failure(CodeVaspSubmissionFailure.Admission.NOT_SENT);assertEquals(0,signs.get());}
    @Test void missingSettingsCannotStartAddressLookup(){try(var disabled=new CodeVaspTransport(new CodeVaspSettings("","","",""))){var error=assertThrows(CodeVaspSubmissionFailure.class,()->new CodeVaspWithdrawalAddressClient(disabled,approved()).verify(request()));assertEquals(CodeVaspSubmissionFailure.Admission.NOT_SENT,error.admission());}assertEquals(0,signs.get());}
    @Test void invalidAddressForeignPeerAndExpiredKeyCannotConstructRequests(){var r=request();for(String address:List.of(""," ",ADDRESS+"\n","../../keys"))assertThrows(IllegalArgumentException.class,()->new CodeVaspWithdrawalAddressClient.Request(address,r.peer(),r.key()));var expired=new CodeVaspDirectoryClient.Key(peerKey,Instant.now().minusSeconds(1));assertThrows(IllegalArgumentException.class,()->new CodeVaspWithdrawalAddressClient.Request(ADDRESS,peer(expired),expired));var foreign=new CodeVaspDirectoryClient.Vasp("counterparty","가상","가상 수취 사업자","US","CODE",true,List.of(r.key()));assertThrows(IllegalArgumentException.class,()->new CodeVaspWithdrawalAddressClient.Request(ADDRESS,foreign,r.key()));assertEquals(0,signs.get());}
    @Test void databaseTransactionCannotRetainLocksDuringTheLookup(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->client.verify(request()));}finally{TransactionSynchronizationManager.clear();}assertEquals(0,signs.get());}
    @Test void signerBodyDeadlineIsKnownNotSent(){mode="SIGN_SLOW";long start=System.nanoTime();failure(CodeVaspSubmissionFailure.Admission.NOT_SENT);assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()>=4);assertEquals(0,sent.get());}
    @Test void gatewayBodyDeadlineStaysUnknownAfterOneRequest(){mode="GATEWAY_SLOW";long start=System.nanoTime();failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()>=4);assertEquals(1,sent.get());}
    @Test void gatewayFailureAndRedirectCannotRetryOrOpenPrivateIdentity(){for(String bad:List.of("GATEWAY_ERROR","REDIRECT")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}assertEquals(2,sent.get());}
    @Test void requestAndResponseDiagnosticsDoNotEchoTheWallet(){var request=request();var result=client.verify(request);assertFalse(request.toString().contains(ADDRESS));assertFalse(result.toString().contains(ADDRESS));}
}
