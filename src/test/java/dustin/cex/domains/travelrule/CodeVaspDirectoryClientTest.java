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
import tools.jackson.databind.json.JsonMapper;

/** 소유한 두 HTTP 대역으로 공식 모듈→게이트웨이 계약을 검사한다. 실제 공급사/개인키/신원 데이터를 사용하지 않는다. */
class CodeVaspDirectoryClientTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    HttpServer cipher,gateway;ExecutorService workers;CodeVaspDirectoryClient client;CodeVaspTransport transport;KeyPair pair;String key;
    volatile String mode="NORMAL";AtomicInteger cipherCalls=new AtomicInteger(),gatewayCalls=new AtomicInteger();AtomicLong sequence=new AtomicLong(1000);
    AtomicReference<Throwable> callbackFailure=new AtomicReference<>();CountDownLatch release=new CountDownLatch(1);AtomicBoolean verified=new AtomicBoolean();
    final Set<String> nonces=ConcurrentHashMap.newKeySet();
    @BeforeEach void start()throws Exception{
        pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();byte[] encoded=pair.getPublic().getEncoded();
        // 시험 키의 표준 공개키 형식을 확인하고 공급사의32바이트 표현만 만든다. 제품에는 개인키가 없다.
        assertEquals("302a300506032b6570032100",HexFormat.of().formatHex(Arrays.copyOf(encoded,12)));key=Base64.getEncoder().encodeToString(Arrays.copyOfRange(encoded,12,encoded.length));
        workers=Executors.newVirtualThreadPerTaskExecutor();cipher=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);gateway=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);cipher.setExecutor(workers);gateway.setExecutor(workers);
        cipher.createContext("/api/v1/code/api-payloads",exchange->{try{
            cipherCalls.incrementAndGet();assertEquals("POST",exchange.getRequestMethod());assertEquals(JSON.readTree("{\"allianceName\":\"CODE\",\"apiType\":\"SEARCH_VASP_LIST\"}"),JSON.readTree(exchange.getRequestBody().readAllBytes()));
            if(mode.equals("CIPHER_REDIRECT")){exchange.getResponseHeaders().set("Location",gatewayBase()+"/v1/code/vasps");send(exchange,302,"중계 원문 비공개");return;}
            if(mode.equals("CIPHER_SLOW")){slow(exchange);return;}
            String date=mode.equals("UTC_OFFSET")?Instant.now().toString().replace("Z","+00:00"):Instant.now().toString();String nonce=Long.toString(sequence.incrementAndGet());
            var signature=Signature.getInstance("Ed25519");signature.initSign(pair.getPrivate());signature.update((date+nonce).getBytes(StandardCharsets.UTF_8));
            var body=JSON.createObjectNode().put("signature",Base64.getEncoder().encodeToString(signature.sign())).put("nonce",Long.parseLong(nonce)).put("dateTime",date).put("publicKey",key);
            switch(mode){
                case "WRONG_KEY"->body.put("publicKey",Base64.getEncoder().encodeToString(new byte[32]));
                case "OLD_SIGNATURE"->body.put("dateTime",Instant.now().minusSeconds(120).toString());
                case "FUTURE_SIGNATURE"->body.put("dateTime",Instant.now().plusSeconds(30).toString());
                case "NON_UTC_SIGNATURE"->body.put("dateTime",OffsetDateTime.now(ZoneOffset.ofHours(9)).toString());
                case "NONCE_STRING"->body.put("nonce",nonce);
                case "NONCE_FRACTION"->body.put("nonce",1.5);
                case "NONCE_NEGATIVE"->body.put("nonce",-1);
                case "BAD_SIGNATURE"->body.put("signature","not-signature");
                case "EXTRA_GET_BODY"->body.put("body","금지된 본문");
                case "TAMPERED_SIGNATURE"->body.put("signature",Base64.getEncoder().encodeToString(new byte[64]));
            }
            // 정상 필드/작은 문자열로 된 유효 JSON을 크게 만든다. 파싱 실패가 크기 제한 회귀를 가리지 않게 한다.
            if(mode.equals("CIPHER_OVERSIZE")){var padding=body.putArray("padding");for(int i=0;i<1100;i++)padding.add("x".repeat(64));}
            String payload=JSON.writeValueAsString(body);if(mode.equals("DUPLICATE_CIPHER_FIELD"))payload=payload.substring(0,payload.length()-1)+",\"nonce\":1}";
            send(exchange,200,payload);
        }catch(Throwable failure){callbackFailure.compareAndSet(null,failure);send(exchange,500,"격리 모듈 시험 실패");}});
        gateway.createContext("/v1/code/vasps",exchange->{try{
            gatewayCalls.incrementAndGet();assertEquals("GET",exchange.getRequestMethod());assertEquals(0,exchange.getRequestBody().readAllBytes().length);assertEquals("code:cex-study",exchange.getRequestHeaders().getFirst("X-Request-Origin"));assertEquals(key,exchange.getRequestHeaders().getFirst("X-Code-Req-PubKey"));
            String date=exchange.getRequestHeaders().getFirst("X-Code-Req-Datetime"),nonce=exchange.getRequestHeaders().getFirst("X-Code-Req-Nonce");assertTrue(nonces.add(nonce));
            var signature=Signature.getInstance("Ed25519");signature.initVerify(pair.getPublic());signature.update((date+nonce).getBytes(StandardCharsets.UTF_8));
            boolean valid=signature.verify(Base64.getDecoder().decode(exchange.getRequestHeaders().getFirst("X-Code-Req-Signature")));verified.set(valid);
            if(!valid){send(exchange,401,"격리 서명 불일치");return;}
            if(mode.equals("GATEWAY_REDIRECT")){exchange.getResponseHeaders().set("Location",cipherBase()+"/api/v1/code/api-payloads");send(exchange,302,"공급사 비공개 원문");return;}
            if(mode.equals("GATEWAY_ERROR")){send(exchange,429,"민감한 공급사 원문은 노출하면 안 됩니다");return;}
            if(mode.equals("GATEWAY_SLOW")){slow(exchange);return;}
            var entry=JSON.createObjectNode().put("vaspEntityId","counterparty").put("vaspName","검증 상대").put("vaspLegalName","검증 사업자 주식회사").put("countryOfRegistration","KR").put("allianceName","code").put("health",mode.equals("DOWN_EXPIRED")?"down":"up");
            entry.putArray("pubkeys").addObject().put("pubkey",key).put("expiresAt",mode.equals("DOWN_EXPIRED")?"2000-01-01T00:00:00Z":mode.equals("NAIVE_EXPIRY")?"2099-01-01T00:00:00":"2099-01-01T00:00:00Z");
            switch(mode){
                case "MISSING_COUNTRY"->entry.remove("countryOfRegistration");
                case "UNKNOWN_HEALTH"->entry.put("health","idle");
                case "BAD_DIRECTORY_KEY"->((tools.jackson.databind.node.ObjectNode)entry.path("pubkeys").get(0)).put("pubkey","not-a-key");
                case "DUPLICATE_KEY"->((tools.jackson.databind.node.ArrayNode)entry.path("pubkeys")).add(entry.path("pubkeys").get(0).deepCopy());
            }
            if(mode.equals("BAD_EXPIRY"))((tools.jackson.databind.node.ObjectNode)entry.path("pubkeys").get(0)).put("expiresAt","2099-01-01T00:00:00+09:00");
            var result=JSON.createObjectNode();var rows=result.putArray("vasps");rows.add(entry);if(mode.equals("DUPLICATE_VASP"))rows.add(entry.deepCopy());
            if(mode.equals("GATEWAY_OVERSIZE")){var padding=result.putArray("padding");for(int i=0;i<40000;i++)padding.add("x".repeat(64));}
            if(mode.equals("EMPTY_DIRECTORY"))rows.removeAll();String payload=JSON.writeValueAsString(result);if(mode.equals("DUPLICATE_DIRECTORY_FIELD"))payload=payload.substring(0,payload.length()-1)+",\"vasps\":[]}";
            send(exchange,200,payload);
        }catch(Throwable failure){callbackFailure.compareAndSet(null,failure);send(exchange,500,"격리 공급사 시험 실패");}});
        cipher.start();gateway.start();transport=new CodeVaspTransport(new CodeVaspSettings(gatewayBase(),cipherBase(),"cex-study",key));client=new CodeVaspDirectoryClient(transport);
    }
    String cipherBase(){return "http://127.0.0.1:"+cipher.getAddress().getPort();}String gatewayBase(){return "http://127.0.0.1:"+gateway.getAddress().getPort();}
    void send(HttpExchange exchange,int status,String payload)throws java.io.IOException{byte[] body=payload.getBytes(StandardCharsets.UTF_8);try(exchange){exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);}catch(java.io.IOException closed){if(!mode.endsWith("OVERSIZE"))throw closed;}}
    void slow(HttpExchange exchange)throws Exception{try(exchange){exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();assertTrue(release.await(10,TimeUnit.SECONDS));}catch(java.io.IOException closed){/* 수신 제한으로 닫힌 시험 연결은 종료 처리한다. */}}
    @AfterEach void stop(){release.countDown();if(transport!=null)transport.close();if(cipher!=null)cipher.stop(0);if(gateway!=null)gateway.stop(0);if(workers!=null)workers.close();assertNull(callbackFailure.get());}
    ResponseStatusException unavailable(){var failure=assertThrows(ResponseStatusException.class,()->client.read());assertEquals(503,failure.getStatusCode().value());assertEquals("이전거래 공급사 요청을 완료할 수 없습니다",failure.getReason());return failure;}
    @Test void officialCipherAndGatewayContractPreservesRealSignatureAndTypedDirectory(){var result=client.read();assertTrue(verified.get());assertEquals(1,cipherCalls.get());assertEquals(1,gatewayCalls.get());assertTrue(!result.readFinishedAt().isBefore(result.readStartedAt()));var vasp=result.vasps().getFirst();assertEquals("counterparty",vasp.entityId());assertTrue(vasp.reachable());assertEquals("KR",vasp.country());assertEquals(key,vasp.keys().getFirst().publicKey());assertEquals(Instant.parse("2099-01-01T00:00:00Z"),vasp.keys().getFirst().expiresAt());mode="UTC_OFFSET";client.read();assertTrue(verified.get());assertEquals(2,gatewayCalls.get());}
    @Test void eachReadObtainsNewSigningMaterialInsteadOfReusingNonce(){client.read();client.read();assertEquals(2,nonces.size());assertEquals(2,cipherCalls.get());}
    @Test void unavailableCounterpartyAndExpiredKeyArePreservedWithoutApprovingThem(){mode="DOWN_EXPIRED";var vasp=client.read().vasps().getFirst();assertFalse(vasp.reachable());assertTrue(vasp.keys().getFirst().expiresAt().isBefore(Instant.now()));}
    @Test void emptySuccessfulDirectoryIsDifferentFromFailedCall(){mode="EMPTY_DIRECTORY";assertTrue(client.read().vasps().isEmpty());assertTrue(verified.get());}
    @Test void invalidCipherMaterialNeverReachesGateway(){for(String invalid:List.of("WRONG_KEY","OLD_SIGNATURE","FUTURE_SIGNATURE","NON_UTC_SIGNATURE","NONCE_STRING","NONCE_FRACTION","NONCE_NEGATIVE","BAD_SIGNATURE","EXTRA_GET_BODY","DUPLICATE_CIPHER_FIELD")){mode=invalid;unavailable();}assertEquals(0,gatewayCalls.get());}
    @Test void alteredButWellFormedSignatureIsRejectedByGateway(){mode="TAMPERED_SIGNATURE";unavailable();assertFalse(verified.get());assertEquals(1,gatewayCalls.get());}
    @Test void malformedDirectoryNeverBecomesPartialOrEmptySuccess(){for(String invalid:List.of("MISSING_COUNTRY","UNKNOWN_HEALTH","BAD_DIRECTORY_KEY","BAD_EXPIRY","DUPLICATE_KEY","DUPLICATE_VASP","DUPLICATE_DIRECTORY_FIELD")){mode=invalid;unavailable();}}
    @Test void redirectsAreNotFollowedAcrossSigningAndGatewayBoundaries(){mode="CIPHER_REDIRECT";unavailable();assertEquals(0,gatewayCalls.get());mode="GATEWAY_REDIRECT";unavailable();assertEquals(1,gatewayCalls.get());assertEquals(2,cipherCalls.get());}
    @Test void gatewayErrorsHaveNoApplicationRetryOrRawBodyDisclosure(){mode="GATEWAY_ERROR";var failure=unavailable();assertFalse(failure.toString().contains("민감한"));assertNull(failure.getCause());assertEquals(1,cipherCalls.get());assertEquals(1,gatewayCalls.get());}
    @Test void cipherAndGatewayBodiesAreBounded(){mode="CIPHER_OVERSIZE";unavailable();assertEquals(0,gatewayCalls.get());mode="GATEWAY_OVERSIZE";unavailable();assertEquals(1,gatewayCalls.get());}
    @Test void stalledCipherBodyHasAbsoluteDeadline(){mode="CIPHER_SLOW";long started=System.nanoTime();unavailable();long elapsed=Duration.ofNanos(System.nanoTime()-started).toSeconds();assertTrue(elapsed>=4&&elapsed<8);assertEquals(0,gatewayCalls.get());}
    @Test void stalledGatewayBodyHasAbsoluteDeadline(){mode="GATEWAY_SLOW";long started=System.nanoTime();unavailable();long elapsed=Duration.ofNanos(System.nanoTime()-started).toSeconds();assertTrue(elapsed>=4&&elapsed<8);}
    @Test void ambientDatabaseTransactionCannotPerformProviderIo(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(IllegalStateException.class,()->client.read());}finally{TransactionSynchronizationManager.clear();}assertEquals(0,cipherCalls.get());assertEquals(0,gatewayCalls.get());}
    @Test void absentSettingsNeverCallAnyDefaultProvider(){try(var disabled=new CodeVaspTransport(new CodeVaspSettings("","","",""))){assertThrows(ResponseStatusException.class,()->new CodeVaspDirectoryClient(disabled).read());}assertEquals(0,cipherCalls.get());assertEquals(0,gatewayCalls.get());}
    @Test void documentedUtcExpiryWithoutOffsetDoesNotUseJvmLocalTimezone(){
        var original=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));mode="NAIVE_EXPIRY";assertEquals(Instant.parse("2099-01-01T00:00:00Z"),client.read().vasps().getFirst().keys().getFirst().expiresAt());}finally{TimeZone.setDefault(original);}
    }

}
