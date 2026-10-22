package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.JwtService;
import dustin.cex.domains.security.*;

/** 실제 HTTP 공급사 대역·웹훅 HMAC·PG·사용자 잠금으로 정상/실패 상태와 주문 정책을 검증한다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KycIntegrationTest {
    static final UUID APP=UUID.fromString("11111111-2222-3333-4444-555555555555"),WORKFLOW=UUID.fromString("66666666-7777-8888-9999-000000000000");
    static final String SECRET=DiditWebhookVerifierTest.SECRET;
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final HttpServer PROVIDER;
    static final Map<UUID,UUID> CREATED=new ConcurrentHashMap<>();
    static final AtomicInteger POSTS=new AtomicInteger(),GETS=new AtomicInteger();
    static volatile String mode="NORMAL";
    static volatile CountDownLatch arrived,release;
    static volatile java.util.function.Consumer<UUID> early;
    static {
        try {
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PROVIDER.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            PROVIDER.createContext("/v3/",exchange->{
                try {
                    assertEquals("isolated-api-key-at-least-16",exchange.getRequestHeaders().getFirst("x-api-key"));
                    if(exchange.getRequestMethod().equals("POST")) {
                        assertEquals("/v3/session/",exchange.getRequestURI().getPath());POSTS.incrementAndGet();
                        var payload=JSON.readTree(exchange.getRequestBody().readAllBytes());UUID attempt=UUID.fromString(payload.path("vendor_data").asText());
                        assertEquals(WORKFLOW.toString(),payload.path("workflow_id").asText());assertEquals("https://exchange.example.test/kyc/return",payload.path("callback").asText());
                        UUID session=CREATED.computeIfAbsent(attempt,id->UUID.randomUUID());
                        if(early!=null) early.accept(attempt);
                        if(mode.equals("BLOCK")) {arrived.countDown();assertTrue(release.await(8,TimeUnit.SECONDS));}
                        var body=new HashMap<String,Object>(Map.of("session_id",session,"session_kind","user","vendor_data",attempt.toString(),"workflow_id",WORKFLOW,
                            "workflow_version",mode.equals("WRONG_VERSION")?5:4,"url",mode.equals("BAD_URL")?"https://evil.example/session/token":"https://verify.didit.me/session/"+session,"status","Not Started"));
                        if(mode.equals("LARGE_BODY")) {byte[] raw=new byte[1048577];exchange.sendResponseHeaders(201,raw.length);exchange.getResponseBody().write(raw);return;}
                        if(mode.equals("HANG_BODY")) {exchange.sendResponseHeaders(201,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();release.await(15,TimeUnit.SECONDS);return;}
                        int status=mode.equals("LOST_RESPONSE")?503:mode.equals("REDIRECT")?302:201;
                        byte[] raw=JSON.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
                        if(status==302) exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/steal");
                        exchange.sendResponseHeaders(status,raw.length);exchange.getResponseBody().write(raw);
                    } else {
                        GETS.incrementAndGet();assertEquals("/v3/sessions/",exchange.getRequestURI().getPath());
                        var params=new HashMap<String,String>();for(String pair:exchange.getRequestURI().getRawQuery().split("&")) {var kv=pair.split("=",2);params.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
                        UUID attempt=UUID.fromString(params.get("vendor_data"));UUID session=CREATED.get(attempt);
                        List<Object> results=session==null?List.of():List.of(Map.of("session_id",session,"session_kind","user","vendor_data",attempt.toString(),"session_url","https://verify.didit.me/session/"+session));
                        var body=new HashMap<String,Object>();body.put("count",mode.equals("COUNT_OVERFLOW")?4294967297L:mode.equals("MULTIPLE")?2:results.size());body.put("results",results);body.put("next",null);
                        byte[] raw=JSON.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,raw.length);exchange.getResponseBody().write(raw);
                    }
                } catch(Exception | AssertionError failure) {exchange.sendResponseHeaders(500,-1);}
                finally {exchange.close();}
            });PROVIDER.start();
        } catch(IOException e) {throw new ExceptionInInitializerError(e);}
    }
    @TestConfiguration static class ProviderConfiguration {
        @Bean @Primary DiditClient testDiditClient(DiditSettings s,ObjectMapper json) {return new DiditClient(s,json,URI.create("http://127.0.0.1:"+PROVIDER.getAddress().getPort()));}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");
        r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
        r.add("didit.api-key",()->"isolated-api-key-at-least-16");r.add("didit.webhook-secret",()->SECRET);r.add("didit.application-id",APP::toString);
        r.add("didit.workflow-id",WORKFLOW::toString);r.add("didit.workflow-version",()->4);r.add("didit.environment",()->"live");
        r.add("didit.callback",()->"https://exchange.example.test/kyc/return");r.add("didit.encryption-key",()->Base64.getEncoder().encodeToString(new byte[32]));
    }
    @Autowired KycService service;@Autowired DiditWebhookVerifier verifier;@Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;@Autowired RefreshTokenRepository sessions;@Autowired JwtService jwt;
    @Autowired PlatformTransactionManager manager;@Autowired TradingAccessPolicy trading;
    @MockitoSpyBean AuditService audit;
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    User user;UUID sid;String access;long now;
    @BeforeEach void setup() {
        POSTS.set(0);GETS.set(0);CREATED.clear();mode="NORMAL";early=null;now=Instant.now().getEpochSecond();
        user=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash("격리 검증").build());
        var session=sessions.saveAndFlush(RefreshToken.builder().userId(user.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        sid=session.getSessionId();access=jwt.generateAccessToken(user.getId(),sid);
    }
    @AfterAll void stop() {PROVIDER.stop(0);}
    UUID start() {return service.start(user.getId(),sid,UUID.randomUUID()).status().attemptId();}
    Map<String,Object> event(UUID attempt,String status,long time) {
        var body=new HashMap<String,Object>();body.put("event_id",UUID.randomUUID());body.put("webhook_type","status.updated");
        body.put("timestamp",Math.max(now,time));body.put("created_at",time);body.put("application_id",APP);body.put("environment","live");body.put("sandbox_scenario",null);
        body.put("session_id",CREATED.get(attempt));body.put("workflow_id",WORKFLOW);body.put("workflow_version",4);body.put("vendor_data",attempt.toString());body.put("status",status);
        if(status.equals("Approved")) body.put("decision",Map.of("session_id",CREATED.get(attempt),"status","Approved",
            "id_verifications",List.of(Map.of("status","Approved","verification_method","document","assurance","documentary")),
            "liveness_checks",List.of(Map.of("status","Approved")),"face_matches",List.of(Map.of("status","Approved"))));
        return body;
    }
    byte[] bytes(Map<String,Object> e) {return JSON.writeValueAsBytes(e);}
    String signature(byte[] raw) throws Exception {
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(verifier.canonical(verifier.json.readTree(raw)).getBytes(StandardCharsets.UTF_8)));
    }
    void send(Map<String,Object> e) throws Exception {byte[] raw=bytes(e);service.webhook(raw,signature(raw),e.get("timestamp").toString());}
    void allowed() {new TransactionTemplate(manager).executeWithoutResult(status->trading.requireNewOrder(user.getId()));}
    void blocked() {assertThrows(ResponseStatusException.class,this::allowed);}
    @Test void unverifiedCustomerIsBlockedThenVerifiedOnlyAfterSignedChecks() throws Exception {
        blocked();UUID id=start();blocked();send(event(id,"Approved",now));assertEquals("VERIFIED",service.status(user.getId()).identityStatus());allowed();
        assertEquals(1,POSTS.get());assertTrue(service.status(user.getId()).validUntil().isAfter(Instant.now()));
    }
    @Test void sameKeyReusesAttemptAndDifferentKeyConflictsWithoutCreatingAnother() {
        UUID key=UUID.randomUUID();var one=service.start(user.getId(),sid,key);var same=service.start(user.getId(),sid,key);
        assertThrows(ResponseStatusException.class,()->service.start(user.getId(),sid,UUID.randomUUID()));
        assertEquals(one,same);assertEquals(one,service.ownSession(user.getId(),sid));assertEquals(1,POSTS.get());
    }
    @Test void simultaneousSessionStartDoesNotWaitOnNetworkOrCreateTwice() throws Exception {
        mode="BLOCK";arrived=new CountDownLatch(1);release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var original=pool.submit(()->service.start(user.getId(),sid,UUID.randomUUID()));assertTrue(arrived.await(5,TimeUnit.SECONDS));
            var duplicate=pool.submit(()->{assertThrows(ResponseStatusException.class,()->service.start(user.getId(),sid,UUID.randomUUID()));return service.ownSession(user.getId(),sid);});
            var waiting=duplicate.get(3,TimeUnit.SECONDS);assertEquals("CREATING",waiting.status().creationState());assertNull(waiting.verificationUrl());
            release.countDown();assertEquals(waiting.status().attemptId(),original.get(8,TimeUnit.SECONDS).status().attemptId());
        } finally {release.countDown();}
        assertEquals(1,POSTS.get());
    }
    @Test void lostResponseUsesReadOnlyReconciliationAndNeverRepeatsPost() {
        mode="LOST_RESPONSE";UUID key=UUID.randomUUID();assertThrows(ResponseStatusException.class,()->service.start(user.getId(),sid,key));UUID id=service.status(user.getId()).attemptId();
        assertEquals("UNKNOWN",service.status(user.getId()).creationState());
        service.start(user.getId(),sid,key);assertThrows(ResponseStatusException.class,()->service.start(user.getId(),sid,UUID.randomUUID()));assertEquals(1,POSTS.get());
        mode="NORMAL";var reconciled=service.reconcile(user.getId(),sid,id);assertNotNull(reconciled.verificationUrl());assertEquals("READY",reconciled.status().creationState());
        assertEquals(1,GETS.get());assertEquals(1,POSTS.get());blocked();
    }
    @Test void multipleReconciliationCandidatesRemainBlocked() {
        mode="LOST_RESPONSE";UUID key=UUID.randomUUID();assertThrows(ResponseStatusException.class,()->service.start(user.getId(),sid,key));UUID id=service.status(user.getId()).attemptId();
        for(String invalid:List.of("MULTIPLE","COUNT_OVERFLOW")) {
            mode=invalid;assertThrows(ResponseStatusException.class,()->service.reconcile(user.getId(),sid,id));
            assertEquals("UNKNOWN",service.status(user.getId()).creationState());blocked();
        }
    }
    @Test void webhookMayArriveBeforeCreationResponseWithoutLosingDecision() throws Exception {
        early=id->{try{send(event(id,"Approved",now));}catch(Exception e){throw new IllegalStateException(e);}};
        UUID id=start();assertNotNull(service.ownSession(user.getId(),sid).verificationUrl());assertEquals("VERIFIED",service.status(user.getId()).identityStatus());allowed();
        assertEquals(id,service.status(user.getId()).attemptId());
    }
    @Test void retryChangesDispatchTimestampWithoutApplyingEffectAgain() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-1);send(e);Instant until=service.status(user.getId()).validUntil();
        e.put("timestamp",now+1);send(e);assertEquals(until,service.status(user.getId()).validUntil());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM kyc_events WHERE attempt_id=?",Long.class,id));allowed();
    }
    @Test void staleEventCannotReplaceNewerDecision() throws Exception {
        UUID id=start();send(event(id,"Approved",now));send(event(id,"Declined",now-1));allowed();
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM kyc_events WHERE attempt_id=? AND disposition='STALE'",Long.class,id));
    }
    @Test void sameTimestampDisagreementBlocksRatherThanGuessingOrder() throws Exception {
        UUID id=start();send(event(id,"Approved",now));send(event(id,"Declined",now));assertEquals("CONFLICT",service.status(user.getId()).identityStatus());blocked();
        send(event(id,"Approved",now+1));assertEquals("CONFLICT",service.status(user.getId()).identityStatus());blocked();
    }
    @Test void eventIdContentChangeBlocksAndPreservesOriginalReceipt() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);send(e);e.put("status","Declined");send(e);blocked();
        assertEquals("CONFLICT",service.status(user.getId()).identityStatus());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM kyc_events WHERE attempt_id=?",Long.class,id));
        assertEquals("Approved",jdbc.queryForObject("SELECT provider_status FROM kyc_events WHERE event_id=?",String.class,e.get("event_id")));
    }
    @Test void supersededAttemptCannotApproveNewVerification() throws Exception {
        UUID old=start();send(event(old,"Declined",now-1));UUID next=start();assertNotEquals(old,next);
        send(event(old,"Approved",now));assertEquals("PENDING",service.status(user.getId()).identityStatus());blocked();
        send(event(next,"Approved",now));allowed();
    }
    @Test void manualRestrictionAndDisableAreNeverClearedByProvider() throws Exception {
        UUID id=start();var account=users.findById(user.getId()).orElseThrow();account.setTradingRestricted(true);users.saveAndFlush(account);
        send(event(id,"Approved",now));blocked();assertTrue(users.findById(user.getId()).orElseThrow().isTradingRestricted());
        account=users.findById(user.getId()).orElseThrow();account.setTradingRestricted(false);account.setEnabled(false);users.saveAndFlush(account);blocked();
    }
    @Test void localExpiryBlocksWithoutWaitingForAnotherWebhook() throws Exception {
        UUID id=start();send(event(id,"Approved",now));jdbc.update("UPDATE kyc_profiles SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?",user.getId());blocked();
        UUID next=start();assertNotEquals(id,next);blocked();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"Not Started","In Progress","In Review","Awaiting User","Resubmitted","Declined","Expired","Abandoned","Kyc Expired","unexpected"})
    void nonApprovedStatusesNeverOpenTrading(String state) throws Exception {UUID id=start();send(event(id,state,now));blocked();}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"application_id","environment","workflow_id","workflow_version","session_id","vendor_data","session_kind","sandbox_scenario"})
    void crossApplicationEnvironmentWorkflowAndSessionBindingsAreRejected(String field) throws Exception {
        UUID id=start();var e=event(id,"Approved",now);
        e.put(field,switch(field){case "workflow_version"->5;case "environment"->"sandbox";case "session_kind"->"business";case "sandbox_scenario"->"approved";default->UUID.randomUUID().toString();});
        // 이미 연결된 세션 식별자도 다른 값으로 바꿀 수 없다.
        assertThrows(ResponseStatusException.class,()->send(e));blocked();assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM kyc_events WHERE attempt_id=?",Long.class,id));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"id_verifications","liveness_checks","face_matches","status","session_id"})
    void approvedLabelWithoutRequiredDecisionEvidenceIsConflict(String field) throws Exception {
        UUID id=start();var e=event(id,"Approved",now);var decision=new HashMap<>((Map<String,Object>)e.get("decision"));decision.remove(field);e.put("decision",decision);
        send(e);assertEquals("CONFLICT",service.status(user.getId()).identityStatus());blocked();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"BAD_URL","WRONG_VERSION","REDIRECT"})
    void malformedProviderResponseCannotLeakKeyOrCreateUsableSession(String failure) {
        mode=failure;assertThrows(ResponseStatusException.class,this::start);assertEquals("UNKNOWN",service.status(user.getId()).creationState());blocked();
    }
    @Test void linksAreEncryptedBoundToAttemptAndAbsentFromAudit() {
        UUID id=start();String url=service.ownSession(user.getId(),sid).verificationUrl();byte[] stored=jdbc.queryForObject("SELECT link_cipher FROM kyc_attempts WHERE id=?",byte[].class,id);
        assertFalse(new String(stored,StandardCharsets.ISO_8859_1).contains(url));assertFalse(jdbc.queryForList("SELECT detail FROM security_audit").toString().contains(url));
        stored[stored.length-1]^=1;jdbc.update("UPDATE kyc_attempts SET link_cipher=? WHERE id=?",stored,id);
        assertThrows(IllegalStateException.class,()->service.ownSession(user.getId(),sid));
    }
    @Test void anotherUsersAttemptOrRevokedSessionCannotBeUsed() {
        UUID id=start();var other=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash("격리").build());
        var session=sessions.saveAndFlush(RefreshToken.builder().userId(other.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        var theirs=service.start(other.getId(),session.getSessionId(),id);
        assertNotEquals(id,theirs.status().attemptId());
        assertThrows(ResponseStatusException.class,()->service.reconcile(other.getId(),session.getSessionId(),id));
        var revoked=sessions.findBySessionId(sid).orElseThrow();revoked.setRevoked(true);sessions.saveAndFlush(revoked);
        assertThrows(ResponseStatusException.class,()->service.ownSession(user.getId(),sid));
    }
    @Test void dailyAttemptLimitIsPersisted() throws Exception {
        for(int i=0;i<3;i++){UUID id=start();send(event(id,"Declined",now));}
        assertEquals(429,assertThrows(ResponseStatusException.class,this::start).getStatusCode().value());assertEquals(3,POSTS.get());blocked();
    }
    @Test void creationAuditFailureDoesNotCallProvider() {
        doThrow(new IllegalStateException("요청 감사 장애 주입")).when(audit).record(eq(user.getId()),eq("KYC_SESSION_REQUESTED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,this::start);assertEquals(0,POSTS.get());assertNull(service.status(user.getId()).attemptId());
    }
    @Test void webhookAuditFailureRollsBackReceiptAndTradingPermission() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);
        doThrow(new IllegalStateException("웹훅 감사 장애 주입")).when(audit).record(eq(user.getId()),eq("KYC_EVENT_RECEIVED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->send(e));blocked();assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM kyc_events WHERE attempt_id=?",Long.class,id));
        reset(audit);send(e);allowed();
    }
    @Test void concurrentIdenticalWebhooksCommitOneReceipt() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<Void> work=()->{gate.await();send(e);return null;};var a=pool.submit(work);var b=pool.submit(work);gate.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM kyc_events WHERE attempt_id=?",Long.class,id));allowed();
    }
    @Test void immutableReceiptCannotBeChangedDeletedOrTruncated() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);send(e);
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE kyc_events SET provider_status='Declined' WHERE event_id=?",e.get("event_id")));
        assertThrows(RuntimeException.class,()->jdbc.update("DELETE FROM kyc_events WHERE event_id=?",e.get("event_id")));
        assertThrows(RuntimeException.class,()->jdbc.execute("TRUNCATE kyc_events"));
    }
    @Test void realHttpOnlyAllowsOwnSessionAndSignedWebhookAndNeverCachesLinks() throws Exception {
        var create=http("POST","/api/kyc/session",access,null,Map.of("Idempotency-Key",UUID.randomUUID().toString()));assertEquals(200,create.statusCode());
        assertEquals("no-store",create.headers().firstValue("Cache-Control").orElseThrow());UUID id=UUID.fromString(JSON.readTree(create.body()).path("status").path("attemptId").asText());
        var e=event(id,"Approved",now);byte[] raw=bytes(e);
        assertEquals(401,http("POST","/api/kyc/webhook",null,new String(raw,StandardCharsets.UTF_8),Map.of("X-Timestamp",Long.toString(now),"X-Signature-Simple","0".repeat(64))).statusCode());
        assertEquals(200,http("POST","/api/kyc/webhook",null,new String(raw,StandardCharsets.UTF_8),Map.of("X-Timestamp",Long.toString(now),"X-Signature-V2",signature(raw))).statusCode());allowed();
        assertEquals(401,http("GET","/api/kyc/session",null,null,Map.of()).statusCode());
        assertEquals(403,http("GET","/api/admin/kyc/customers/"+user.getId(),access,null,Map.of()).statusCode());
        assertEquals(400,http("POST","/api/kyc/session",access,null,Map.of()).statusCode());
        assertEquals(403,http("POST","/api/kyc/return?status=Approved",access,null,Map.of()).statusCode());
    }
    @Test void sandboxVerificationCannotEnableLiveTrading() throws Exception {
        UUID id=start();jdbc.update("UPDATE kyc_attempts SET environment='sandbox' WHERE id=?",id);
        var e=event(id,"Approved",now);e.put("environment","sandbox");send(e);
        assertEquals("VERIFIED",service.status(user.getId()).identityStatus());assertEquals("sandbox",service.status(user.getId()).environment());blocked();
    }
    @Test void completionAuditFailureLeavesDurableAttemptForReadOnlyRecovery() {
        doThrow(new IllegalStateException("응답 저장 감사 장애 주입")).when(audit).record(eq(user.getId()),eq("KYC_SESSION_CREATED"),anyString(),anyString(),anyString());
        UUID key=UUID.randomUUID();assertThrows(IllegalStateException.class,()->service.start(user.getId(),sid,key));UUID id=service.status(user.getId()).attemptId();
        assertEquals("CREATING",service.status(user.getId()).creationState());assertEquals(1,POSTS.get());
        reset(audit);service.reconcile(user.getId(),sid,id);assertEquals("READY",service.status(user.getId()).creationState());assertEquals(1,POSTS.get());blocked();
    }
    @Test void conflictRequiresFreshVerificationAndOldEventsStaySuperseded() throws Exception {
        UUID old=start();send(event(old,"Approved",now));send(event(old,"Declined",now));blocked();
        UUID next=start();assertNotEquals(old,next);send(event(old,"Approved",now+1));blocked();send(event(next,"Approved",now+1));allowed();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"4.5","4294967300","\"4\""})
    void workflowVersionMustBeExactIntegerWithoutOverflow(String invalid) throws Exception {
        UUID id=start();var e=event(id,"Approved",now);e.put("workflow_version",JSON.readTree(invalid));
        assertThrows(ResponseStatusException.class,()->send(e));blocked();
    }
    @Test void responseBodyHasHardSizeAndCompletionDeadline() {
        mode="LARGE_BODY";assertThrows(ResponseStatusException.class,this::start);assertEquals("UNKNOWN",service.status(user.getId()).creationState());
        // 첫 결과 불명 시도를 다른 POST로 재시도하지 않으므로 별도 고객을 준비한다.
        setup();mode="HANG_BODY";release=new CountDownLatch(1);long start=System.nanoTime();
        try {assertThrows(ResponseStatusException.class,this::start);assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()<14);}
        finally {release.countDown();}
        assertEquals("UNKNOWN",service.status(user.getId()).creationState());assertEquals(1,POSTS.get());
    }
    @Test void incompleteChecksAndNonDocumentaryMethodCannotPass() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);var d=new HashMap<>((Map<String,Object>)e.get("decision"));
        d.put("id_verifications",List.of(Map.of("status","Approved","verification_method","wallet","assurance","cryptographic")));e.put("decision",d);
        send(e);assertEquals("CONFLICT",service.status(user.getId()).identityStatus());blocked();
    }
    @Test void idempotencyKeysAreScopedPerCustomerAndVendorReferencesComeFromServer() {
        UUID key=UUID.randomUUID();var mine=service.start(user.getId(),sid,key);assertNotEquals(key,mine.status().attemptId());
        var other=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash("격리").build());
        var session=sessions.saveAndFlush(RefreshToken.builder().userId(other.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        var theirs=service.start(other.getId(),session.getSessionId(),key);assertNotEquals(key,theirs.status().attemptId());assertNotEquals(mine.status().attemptId(),theirs.status().attemptId());
        assertEquals(2,POSTS.get());
    }
    @Test void outerTransactionCannotKeepLocksOpenAcrossProviderHttp() {
        var tx=new TransactionTemplate(manager);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->tx.execute(status->service.start(user.getId(),sid,UUID.randomUUID())));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->tx.execute(status->service.reconcile(user.getId(),sid,UUID.randomUUID())));
        assertEquals(0,POSTS.get());assertEquals(0,GETS.get());
    }
    HttpResponse<String> http(String method,String path,String token,String body,Map<String,String> headers) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(token!=null)request.header("Authorization","Bearer "+token);headers.forEach(request::header);
        request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
