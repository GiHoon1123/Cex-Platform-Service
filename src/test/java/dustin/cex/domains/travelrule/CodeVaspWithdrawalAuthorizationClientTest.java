package dustin.cex.domains.travelrule;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import tools.jackson.databind.json.JsonMapper;

/** 소유 모듈·게이트웨이 HTTP로 출금 이전 승인 계약을 검증한다. 실제 공급사 모듈의 암호 검증 시험과 구분한다. */
class CodeVaspWithdrawalAuthorizationClientTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String SENDER="가상 송금인",RECIPIENT="가상 수취인",ADDRESS="bcrt1q"+"a".repeat(38),ACCOUNT="bcrt1q"+"b".repeat(38);
    static final BigDecimal NET=new BigDecimal("0.01000000"),KRW=new BigDecimal("999999.00000000");
    static final LocalDate BIRTH=LocalDate.of(1990,1,1);
    final UUID id=UUID.randomUUID();
    final String outgoing=Base64.getEncoder().encodeToString(new byte[64]),incoming=Base64.getEncoder().encodeToString("x".repeat(64).getBytes(StandardCharsets.US_ASCII));
    HttpServer module,gateway;ExecutorService workers;CodeVaspTransport transport;CodeVaspWithdrawalAuthorizationClient client;
    KeyPair pair;String ownKey,peerKey;
    volatile String mode="NORMAL";
    final AtomicLong nonce=new AtomicLong();
    final AtomicInteger signs=new AtomicInteger(),sent=new AtomicInteger(),decrypts=new AtomicInteger();
    final AtomicReference<Throwable> callbackFailure=new AtomicReference<>();
    final AtomicReference<String> signedBody=new AtomicReference<>();
    final CountDownLatch release=new CountDownLatch(1);
    CodeVaspDirectoryClient.Key key(){return new CodeVaspDirectoryClient.Key(peerKey,Instant.now().plusSeconds(3600));}
    CodeVaspDirectoryClient.Vasp peer(CodeVaspDirectoryClient.Key key){return new CodeVaspDirectoryClient.Vasp("counterparty","가상 수취 거래소","가상 수취 사업자","KR","CODE",true,List.of(key));}
    CodeVaspWithdrawalAuthorizationClient.Request request(){var k=key();return new CodeVaspWithdrawalAuthorizationClient.Request(id,NET,KRW,false,ACCOUNT,SENDER,BIRTH,ADDRESS,RECIPIENT,peer(k),k);}
    CodeVaspCounterpartyPolicy approved(){return new CodeVaspCounterpartyPolicy(JSON.writeValueAsString(List.of(Map.of("entityId","counterparty","legalName","가상 수취 사업자","reviewReference","LOCAL-TEST-ONLY","evidenceSha256","a".repeat(64),"validFrom",Instant.now().minusSeconds(60).toString(),"validUntil",Instant.now().plusSeconds(60).toString()))));}
    @BeforeEach void start()throws Exception {
        pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();ownKey=Base64.getEncoder().encodeToString(Arrays.copyOfRange(pair.getPublic().getEncoded(),12,44));peerKey=Base64.getEncoder().encodeToString(new byte[32]);
        workers=Executors.newVirtualThreadPerTaskExecutor();module=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);gateway=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        module.setExecutor(workers);gateway.setExecutor(workers);
        module.createContext("/api/v1/code/api-payloads",e->{try {
            signs.incrementAndGet();assertEquals("POST",e.getRequestMethod());
            var command=JSON.readTree(e.getRequestBody().readAllBytes());assertEquals("TRANSFER_AUTHORIZATION",command.path("apiType").asText());assertEquals("CODE",command.path("allianceName").asText());assertEquals(peerKey,command.path("remotePublicKey").asText());
            var r=command.path("request");assertEquals(id.toString(),r.path("transferId").asText());assertEquals(NET.toPlainString(),r.path("amount").asText());
            assertEquals(KRW.toPlainString(),r.path("tradePrice").asText());assertEquals("KRW",r.path("tradeCurrency").asText());
            assertEquals(SENDER,r.path("originatorNaturalPersonLastName").asText());assertEquals(BIRTH.toString(),r.path("originatorNaturalPersonDob").asText());
            assertEquals(ACCOUNT,r.path("originatorWalletAddress").asText());assertEquals(RECIPIENT,r.path("beneficiaryNaturalPersonLastName").asText());assertEquals(ADDRESS,r.path("beneficiaryWalletAddress").asText());
            if(mode.equals("SIGN_SLOW")){slow(e);return;}
            var body=JSON.createObjectNode();for(String field:List.of("transferId","currency","amount","tradePrice","tradeCurrency","isExceedingThreshold","address","network"))body.set(field,r.path(field));
            body.put("payload",outgoing);
            switch(mode){
                case "WRONG_ID"->body.put("transferId",UUID.randomUUID().toString());case "WRONG_AMOUNT"->body.put("amount","0.01000100");
                case "WRONG_PRICE"->body.put("tradePrice","1000000");case "WRONG_CURRENCY"->body.put("currency","ETH");
                case "WRONG_NETWORK"->body.put("network","ETH");case "WRONG_ADDRESS"->body.put("address",ACCOUNT);
                case "WRONG_THRESHOLD"->body.put("isExceedingThreshold",!r.path("isExceedingThreshold").asBoolean());
                case "PLAIN_PII"->body.put("beneficiaryName",RECIPIENT);case "PLAIN_PAYLOAD"->body.put("payload",RECIPIENT);
                case "SHORT_PAYLOAD"->body.put("payload","YWJj");case "MISSING_FIELD"->body.remove("amount");
                case "RAW_COST"->body.put("historicalCost","5");case "RAW_VASP"->body.set("originatingVasp",JSON.createObjectNode().put("customerName",SENDER));
            }
            String raw=JSON.writeValueAsString(body).replace(",",", ");
            if(mode.equals("DUPLICATE_SIGNED_FIELD"))raw=raw.substring(0,raw.length()-1)+",\"transferId\":\""+id+"\"}";
            if(mode.equals("TRAILING_SIGNED_OBJECT"))raw+=" {}";
            signedBody.set(raw);String date=Instant.now().toString();long number=nonce.incrementAndGet();
            var sig=Signature.getInstance("Ed25519");sig.initSign(pair.getPrivate());sig.update((date+raw+number).getBytes(StandardCharsets.UTF_8));
            var answer=JSON.createObjectNode().put("signature",Base64.getEncoder().encodeToString(sig.sign())).put("nonce",number).put("dateTime",date).put("publicKey",ownKey).put("body",raw);
            if(mode.equals("WRONG_SIGNER"))answer.put("publicKey",peerKey);if(mode.equals("STALE_SIGNATURE"))answer.put("dateTime",Instant.now().minusSeconds(120).toString());
            send(e,200,JSON.writeValueAsString(answer));
        }catch(Throwable t){callbackFailure.compareAndSet(null,t);send(e,500,"격리 서명 대역 실패");}});
        gateway.createContext("/v1/code/transfer/counterparty",e->{try {
            sent.incrementAndGet();assertEquals("POST",e.getRequestMethod());String raw=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);assertEquals(signedBody.get(),raw);
            assertFalse(raw.contains(SENDER));assertFalse(raw.contains(RECIPIENT));assertFalse(raw.contains(BIRTH.toString()));assertFalse(raw.contains(ACCOUNT));
            assertEquals(peerKey,e.getRequestHeaders().getFirst("X-Code-Req-Remote-PubKey"));assertEquals(ownKey,e.getRequestHeaders().getFirst("X-Code-Req-PubKey"));assertEquals("code:cex-withdrawal",e.getRequestHeaders().getFirst("X-Request-Origin"));
            var sig=Signature.getInstance("Ed25519");sig.initVerify(pair.getPublic());sig.update((e.getRequestHeaders().getFirst("X-Code-Req-Datetime")+raw+e.getRequestHeaders().getFirst("X-Code-Req-Nonce")).getBytes(StandardCharsets.UTF_8));assertTrue(sig.verify(Base64.getDecoder().decode(e.getRequestHeaders().getFirst("X-Code-Req-Signature"))));
            if(mode.equals("GATEWAY_SLOW")){slow(e);return;}if(mode.equals("GATEWAY_ERROR")){send(e,503,"개인정보가 섞인 원문");return;}
            if(mode.equals("REDIRECT")){e.getResponseHeaders().set("Location",moduleBase()+"/api/v1/code/decrypt");send(e,302,"리다이렉트 원문");return;}
            var response=JSON.createObjectNode().put("result","verified").put("transferId",id.toString()).put("payload",incoming);
            switch(mode){case "RESPONSE_ID"->response.put("transferId",UUID.randomUUID().toString());case "EXTRA_PII"->response.put("fullName",RECIPIENT);case "UNKNOWN_RESULT"->response.put("result","approved");case "BAD_CIPHERTEXT"->response.put("payload","invalid");case "CONTRADICTORY_REASON"->response.put("reasonType","SANCTION_LIST");}
            if(mode.startsWith("DENIED_"))response=JSON.createObjectNode().put("result","denied").put("transferId",id.toString()).put("reasonType",mode.substring(7)).put("reasonMsg","개인정보가 섞인 원문");
            String answer=JSON.writeValueAsString(response);if(mode.equals("DUPLICATE_REPLY"))answer=answer.substring(0,answer.length()-1)+",\"result\":\"verified\"}";
            if(mode.equals("TRAILING_REPLY"))answer+=" {}";send(e,200,answer);
        }catch(Throwable t){callbackFailure.compareAndSet(null,t);send(e,500,"격리 게이트웨이 대역 실패");}});
        module.createContext("/api/v1/code/decrypt",e->{try {
            decrypts.incrementAndGet();var request=JSON.readTree(e.getRequestBody().readAllBytes());assertEquals(incoming,request.path("data").asText());assertEquals(peerKey,request.path("remotePublicKey").asText());
            if(mode.equals("DECRYPT_SLOW")){slow(e);return;}
            var ivms=JSON.createObjectNode();ivms.set("OriginatingVASP",JSON.createObjectNode());ivms.set("BeneficiaryVASP",JSON.createObjectNode());
            ivms.set("Originator",party("originatorPersons",mode.equals("SENDER_NAME")?"다른 송금인":SENDER,mode.equals("SENDER_ACCOUNT")?ADDRESS:ACCOUNT,mode.equals("SENDER_BIRTH")?"1991-01-01":BIRTH.toString()));
            ivms.set("Beneficiary",party("beneficiaryPersons",mode.equals("BENEFICIARY_NAME")?"다른 수취인":RECIPIENT,mode.equals("BENEFICIARY_ACCOUNT")?ACCOUNT:ADDRESS,null));
            if(mode.equals("MISSING_VASP"))ivms.remove("BeneficiaryVASP");
            if(mode.equals("STRUCTURED_NAME"))((ObjectNode)ivms.path("Beneficiary").path("beneficiaryPersons").get(0).path("naturalPerson").path("name").path("nameIdentifier").get(0)).put("secondaryIdentifier","분리 이름");
            if(mode.equals("LEGAL_PERSON"))((ObjectNode)ivms.path("Beneficiary").path("beneficiaryPersons").get(0)).putObject("legalPerson");
            var identity=JSON.createObjectNode().set("ivms101",ivms);String original=JSON.writeValueAsString(identity);
            if(mode.equals("BODY_MISMATCH"))original="{}";if(mode.equals("BODY_TRAILING"))original+=" {}";
            send(e,200,JSON.writeValueAsString(JSON.createObjectNode().set("body",identity).put("bodyString",original)));
        }catch(Throwable t){callbackFailure.compareAndSet(null,t);send(e,500,"격리 복호화 대역 실패");}});
        module.start();gateway.start();transport=new CodeVaspTransport(new CodeVaspSettings(gatewayBase(),moduleBase(),"cex-withdrawal",ownKey));client=new CodeVaspWithdrawalAuthorizationClient(transport,approved());
    }
    static ObjectNode party(String field,String name,String account,String birth){
        var person=JSON.createObjectNode();person.putObject("name").putArray("nameIdentifier").addObject().put("nameIdentifierType","LEGL").put("primaryIdentifier",name);
        if(birth!=null)person.putObject("dateAndPlaceOfBirth").put("dateOfBirth",birth);
        var party=JSON.createObjectNode();party.putArray("accountNumber").add(account);party.putArray(field).addObject().set("naturalPerson",person);return party;
    }
    String moduleBase(){return "http://127.0.0.1:"+module.getAddress().getPort();}String gatewayBase(){return "http://127.0.0.1:"+gateway.getAddress().getPort();}
    void send(HttpExchange e,int status,String text)throws java.io.IOException{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);try(e){e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);}}
    void slow(HttpExchange e)throws Exception{try(e){e.sendResponseHeaders(200,0);e.getResponseBody().write('{');e.getResponseBody().flush();assertTrue(release.await(10,TimeUnit.SECONDS));}catch(java.io.IOException closed){}}
    @AfterEach void close(){release.countDown();if(transport!=null)transport.close();if(module!=null)module.stop(0);if(gateway!=null)gateway.stop(0);if(workers!=null)workers.close();assertNull(callbackFailure.get());}
    void failure(CodeVaspSubmissionFailure.Admission expected){var e=assertThrows(CodeVaspSubmissionFailure.class,()->client.authorize(request()));assertEquals(expected,e.admission());assertNull(e.getCause());assertFalse(e.toString().contains(SENDER));assertFalse(e.toString().contains("원문"));}

    @Test void exactNetAmountAndPrivateIdentityAreBoundToTheApprovedCounterparty(){var result=client.authorize(request());assertEquals("VERIFIED",result.status());assertEquals(id,result.data().transferId());assertEquals(SENDER,result.data().originator().primaryName());assertEquals(RECIPIENT,result.data().beneficiary().primaryName());assertEquals(64,result.data().ciphertextHash().length());assertEquals(1,signs.get());assertEquals(1,sent.get());assertEquals(1,decrypts.get());}
    @Test void signerCannotChangeTheWithdrawalOrDisclosePlainNames(){for(String bad:List.of("WRONG_ID","WRONG_AMOUNT","WRONG_PRICE","WRONG_CURRENCY","WRONG_NETWORK","WRONG_ADDRESS","WRONG_THRESHOLD","PLAIN_PII","PLAIN_PAYLOAD","SHORT_PAYLOAD","MISSING_FIELD","RAW_COST","RAW_VASP")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.NOT_SENT);}assertEquals(0,sent.get());}
    @Test void malformedSignedBodyNeverReachesTheGateway(){for(String bad:List.of("DUPLICATE_SIGNED_FIELD","TRAILING_SIGNED_OBJECT","WRONG_SIGNER","STALE_SIGNATURE")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.NOT_SENT);}assertEquals(0,sent.get());}
    @Test void publicResponseMustMatchBeforeOpeningPrivateIdentity(){for(String bad:List.of("RESPONSE_ID","EXTRA_PII","UNKNOWN_RESULT","CONTRADICTORY_REASON","DUPLICATE_REPLY","TRAILING_REPLY")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}assertEquals(0,decrypts.get());}
    @Test void documentedDenialsDoNotReturnRawMessagesOrDecryptData(){for(String reason:List.of("NOT_FOUND_ADDRESS","NOT_SUPPORTED_SYMBOL","NOT_KYC_USER","INPUT_NAME_MISMATCHED","DOB_MISMATCHED","SANCTION_LIST","LACK_OF_INFORMATION","UNKNOWN")){mode="DENIED_"+reason;var result=client.authorize(request());assertEquals("DENIED",result.status());assertEquals(reason,result.reason());assertNull(result.data());assertFalse(result.toString().contains("원문"));}assertEquals(0,decrypts.get());}
    @Test void unknownDenialAndMalformedCiphertextStayUnconfirmed(){mode="DENIED_NEW_REASON";failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);mode="BAD_CIPHERTEXT";failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);assertEquals(0,decrypts.get());}
    @Test void privateSenderAndBeneficiaryMustMatchEvenBelowTheThreshold(){for(String bad:List.of("SENDER_NAME","SENDER_ACCOUNT","SENDER_BIRTH","BENEFICIARY_NAME","BENEFICIARY_ACCOUNT","MISSING_VASP","STRUCTURED_NAME","LEGAL_PERSON")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}}
    @Test void decodedBodyAndOriginalBytesCannotDisagree(){for(String bad:List.of("BODY_MISMATCH","BODY_TRAILING")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}}
    @Test void unapprovedCounterpartyNeverReceivesIdentity(){client=new CodeVaspWithdrawalAuthorizationClient(transport,new CodeVaspCounterpartyPolicy("[]"));failure(CodeVaspSubmissionFailure.Admission.NOT_SENT);assertEquals(0,signs.get());assertEquals(0,sent.get());}
    @Test void missingSettingsCannotStartAnAuthorization(){try(var disabled=new CodeVaspTransport(new CodeVaspSettings("","","",""))){var e=assertThrows(CodeVaspSubmissionFailure.class,()->new CodeVaspWithdrawalAuthorizationClient(disabled,approved()).authorize(request()));assertEquals(CodeVaspSubmissionFailure.Admission.NOT_SENT,e.admission());}assertEquals(0,signs.get());}
    @Test void unsupportedNetworkPeerOrExpiredKeyCannotConstructARequest(){var r=request();var expired=new CodeVaspDirectoryClient.Key(peerKey,Instant.now().minusSeconds(1));assertThrows(IllegalArgumentException.class,()->new CodeVaspWithdrawalAuthorizationClient.Request(id,NET,KRW,false,ACCOUNT,SENDER,BIRTH,ADDRESS,RECIPIENT,peer(expired),expired));var foreign=new CodeVaspDirectoryClient.Vasp("counterparty","가상 수취 거래소","가상 수취 사업자","US","CODE",true,List.of(r.key()));assertThrows(IllegalArgumentException.class,()->new CodeVaspWithdrawalAuthorizationClient.Request(id,NET,KRW,false,ACCOUNT,SENDER,BIRTH,ADDRESS,RECIPIENT,foreign,r.key()));assertEquals(0,signs.get());}
    @Test void subSatoshiOrUnboundedFiatCannotReachTheModule(){var r=request();assertThrows(IllegalArgumentException.class,()->new CodeVaspWithdrawalAuthorizationClient.Request(id,new BigDecimal("0.000000001"),KRW,false,ACCOUNT,SENDER,BIRTH,ADDRESS,RECIPIENT,r.peer(),r.key()));assertThrows(IllegalArgumentException.class,()->new CodeVaspWithdrawalAuthorizationClient.Request(id,NET,new BigDecimal("1e1000000"),false,ACCOUNT,SENDER,BIRTH,ADDRESS,RECIPIENT,r.peer(),r.key()));assertEquals(0,signs.get());}
    @Test void invalidNameBirthOrIdentifierCannotStartIo(){var r=request();assertThrows(IllegalArgumentException.class,()->new CodeVaspWithdrawalAuthorizationClient.Request(new UUID(0,0),NET,KRW,false,ACCOUNT,SENDER,BIRTH,ADDRESS,RECIPIENT,r.peer(),r.key()));assertThrows(IllegalArgumentException.class,()->new CodeVaspWithdrawalAuthorizationClient.Request(id,NET,KRW,false,ACCOUNT,SENDER+"\n",BIRTH,ADDRESS,RECIPIENT,r.peer(),r.key()));assertThrows(IllegalArgumentException.class,()->new CodeVaspWithdrawalAuthorizationClient.Request(id,NET,KRW,false,ACCOUNT,SENDER,LocalDate.now().plusDays(1),ADDRESS,RECIPIENT,r.peer(),r.key()));assertEquals(0,signs.get());}
    @Test void databaseTransactionCannotKeepLocksDuringDisclosure(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->client.authorize(request()));}finally{TransactionSynchronizationManager.clear();}assertEquals(0,signs.get());}
    @Test void signingBodyDeadlineIsKnownNotSent(){mode="SIGN_SLOW";long start=System.nanoTime();failure(CodeVaspSubmissionFailure.Admission.NOT_SENT);assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()>=4);assertEquals(0,sent.get());}
    @Test void gatewayBodyDeadlineIsUnknownAndNeverRetries(){mode="GATEWAY_SLOW";long start=System.nanoTime();failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()>=4);assertEquals(1,sent.get());assertEquals(0,decrypts.get());}
    @Test void decryptBodyDeadlineIsUnknownAfterTheOnlyGatewayRequest(){mode="DECRYPT_SLOW";long start=System.nanoTime();failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()>=4);assertEquals(1,sent.get());assertEquals(1,decrypts.get());}
    @Test void gatewayErrorAndRedirectCannotRetryOrLeakResponseText(){for(String bad:List.of("GATEWAY_ERROR","REDIRECT")){mode=bad;failure(CodeVaspSubmissionFailure.Admission.OUTCOME_UNKNOWN);}assertEquals(2,sent.get());assertEquals(0,decrypts.get());}
    @Test void sensitiveRecordsDoNotPrintIdentityOrAccount(){var r=request();var result=client.authorize(r);for(String secret:List.of(SENDER,RECIPIENT,ACCOUNT,ADDRESS,BIRTH.toString()))for(String printed:List.of(r.toString(),result.toString(),result.data().toString(),result.data().originator().toString(),result.data().beneficiary().toString()))assertFalse(printed.contains(secret));}
    @Test void globalProxyCannotObserveModuleOrGatewayIdentity(){
        var previous=ProxySelector.getDefault();var selected=new AtomicInteger();
        ProxySelector.setDefault(new ProxySelector(){public List<Proxy> select(URI uri){selected.incrementAndGet();return List.of(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",1)));}public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}});
        try(var direct=new CodeVaspTransport(new CodeVaspSettings(gatewayBase(),moduleBase(),"cex-withdrawal",ownKey))){assertEquals("VERIFIED",new CodeVaspWithdrawalAuthorizationClient(direct,approved()).authorize(request()).status());assertEquals(0,selected.get());}
        finally{ProxySelector.setDefault(previous);}
    }
}
