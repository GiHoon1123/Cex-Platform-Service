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
    static final Map<UUID,List<Map<String,Object>>> HISTORY=new ConcurrentHashMap<>();
    static final Map<UUID,Map<String,Object>> DECISIONS=new ConcurrentHashMap<>();
    static final AtomicInteger POLLS=new AtomicInteger();
    static volatile Runnable pollHook;
    static volatile String retryHeader="180";
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
                    } else if(exchange.getRequestURI().getPath().endsWith("/webhooks/") || exchange.getRequestURI().getPath().endsWith("/decision/")) {
                        POLLS.incrementAndGet();UUID session=UUID.fromString(exchange.getRequestURI().getPath().split("/")[3]);
                        Object value=exchange.getRequestURI().getPath().endsWith("/webhooks/")?HISTORY.getOrDefault(session,List.of()):DECISIONS.get(session);
                        byte[] raw=JSON.writeValueAsBytes(value);
                        if(exchange.getRequestURI().getPath().endsWith("/decision/") && pollHook!=null) pollHook.run();
                        int code=mode.equals("POLL_FAIL")?503:mode.equals("POLL_RATE")?429:200;
                        if(code==429) exchange.getResponseHeaders().set("Retry-After",retryHeader);
                        if(mode.equals("POLL_BIG")) raw=new byte[1048577];
                        exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(code,raw.length);exchange.getResponseBody().write(raw);
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
    @Autowired dustin.cex.domains.cdd.CddService cdd;
    @Autowired KycReconciliationService reconciliation;
    @Autowired DiditSettings settings;@Autowired DiditClient didit;
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
        HISTORY.clear();DECISIONS.clear();POLLS.set(0);pollHook=null;retryHeader="180";
        jdbc.update("UPDATE kyc_provider_cooldowns SET not_before=clock_timestamp()-interval '1 second'");
        // 테스트 DB의 앞선 사례를 지우지 않고 대기 시각만 분리한다. 대상 시도만 명시적으로 실행한다.
        jdbc.update("UPDATE kyc_reconciliations SET next_attempt_at=clock_timestamp()+interval '1 day'");
        user=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash("격리 검증").build());
        var session=sessions.saveAndFlush(RefreshToken.builder().userId(user.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        sid=session.getSessionId();access=jwt.generateAccessToken(user.getId(),sid);
        cdd.submit(user.getId(),sid,UUID.randomUUID(),declaration());
    }
    @AfterAll void stop() {PROVIDER.stop(0);}
    UUID start() {return service.start(user.getId(),sid,UUID.randomUUID()).status().attemptId();}
    dustin.cex.domains.cdd.CustomerDeclaration declaration() {return new dustin.cex.domains.cdd.CustomerDeclaration("가상 고객 "+user.getId(),LocalDate.of(1990,1,1),"KOR","KOR","가상 검증 주소",
        dustin.cex.domains.cdd.CustomerDeclaration.Occupation.EMPLOYED,dustin.cex.domains.cdd.CustomerDeclaration.Purpose.PERSONAL_INVESTMENT,
        dustin.cex.domains.cdd.CustomerDeclaration.SourceOfFunds.SALARY,new java.math.BigDecimal("1000"),true,dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO);}
    Map<String,Object> event(UUID attempt,String status,long time) {
        var body=new HashMap<String,Object>();body.put("event_id",UUID.randomUUID());body.put("webhook_type","status.updated");
        body.put("timestamp",Math.max(now,time));body.put("created_at",time);body.put("application_id",APP);body.put("environment","live");body.put("sandbox_scenario",null);
        body.put("session_id",CREATED.get(attempt));body.put("workflow_id",WORKFLOW);body.put("workflow_version",4);body.put("vendor_data",attempt.toString());body.put("status",status);
        if(status.equals("Approved")) body.put("decision",Map.of("session_id",CREATED.get(attempt),"status","Approved",
            "id_verifications",List.of(Map.of("status","Approved","verification_method","document","assurance","documentary","full_name",declaration().fullName(),"date_of_birth","1990-01-01","nationality","KOR")),
            "liveness_checks",List.of(Map.of("status","Approved")),"face_matches",List.of(Map.of("status","Approved")),
            "aml_screenings",List.of(Map.of("status","Approved","total_hits",0,"entity_type","person","score",0,"hits",List.of(),"warnings",List.of(),
                "screened_data",Map.of("full_name",declaration().fullName(),"date_of_birth","1990-01-01","nationality","KR")))));
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
    // 보관 API는 원래 본문과 헤더를 문자열로 반환한다. 실제 서명을 만들고 네트워크로 다시 읽는다.
    Map<String,Object> archived(Map<String,Object> event) throws Exception {
        byte[] raw=bytes(event);
        return Map.of("uuid",UUID.randomUUID(),"session_id",event.get("session_id"),"webhook_type",event.get("webhook_type"),
            "request_body",new String(raw,StandardCharsets.UTF_8),"request_header",JSON.writeValueAsString(Map.of(
                "X-Signature-V2",signature(raw),"X-Timestamp",event.get("timestamp").toString())));
    }
    @SuppressWarnings("unchecked")
    void evidence(UUID id,Map<String,Object> event) throws Exception {
        UUID session=CREATED.get(id);HISTORY.put(session,List.of(archived(event)));
        var decision=new HashMap<String,Object>();
        if(event.get("decision") instanceof Map<?,?> nested) decision.putAll((Map<String,Object>)nested);
        decision.putAll(Map.of("session_id",session,"session_kind","user","status",event.get("status"),
            "vendor_data",id.toString(),"workflow_id",WORKFLOW,"environment","live"));
        DECISIONS.put(session,decision);
    }
    void due(UUID id) {jdbc.update("UPDATE kyc_reconciliations SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE attempt_id=?",id);}
    String outcome(UUID id) {return jdbc.queryForObject("SELECT outcome FROM kyc_reconciliations WHERE attempt_id=?",String.class,id);}
    long receipts(UUID id) {return jdbc.queryForObject("SELECT count(*) FROM kyc_events WHERE attempt_id=?",Long.class,id);}
    java.sql.Timestamp freshness(UUID id) {return jdbc.queryForObject("SELECT fresh_at FROM kyc_reconciliations WHERE attempt_id=?",java.sql.Timestamp.class,id);}

    @Test void missedOldWebhookRecoversFromOriginalSignedEventAndKeepsOriginalExpiry() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-1000);e.put("timestamp",now-900);evidence(id,e);
        assertThrows(ResponseStatusException.class,()->send(e));blocked();due(id);assertTrue(reconciliation.reconcileDue());
        assertEquals("CONFIRMED",outcome(id));assertEquals(1,receipts(id));allowed();assertEquals(2,POLLS.get());
        assertEquals(Instant.ofEpochSecond(now-1000).plusSeconds(30*86400),service.status(user.getId()).validUntil());
    }
    @Test void missedDeclineClosesPreviouslyAllowedAccountAtomically() throws Exception {
        UUID id=start();var approved=event(id,"Approved",now-10);send(approved);allowed();var declined=event(id,"Declined",now-1);evidence(id,declined);
        HISTORY.put(CREATED.get(id),List.of(archived(declined),archived(approved)));due(id);reconciliation.reconcileDue();
        blocked();assertEquals("NOT_VERIFIED",service.status(user.getId()).identityStatus());assertEquals(2,receipts(id));
    }
    @Test void currentDecisionDisagreementSuspendsUntilMatchingEvidenceArrives() throws Exception {
        UUID id=start();var approved=event(id,"Approved",now-10);send(approved);evidence(id,approved);allowed();
        DECISIONS.get(CREATED.get(id)).put("status","Declined");due(id);reconciliation.reconcileDue();
        assertEquals("EVIDENCE_MISMATCH",outcome(id));assertNull(freshness(id));blocked();
        evidence(id,event(id,"Declined",now-1));due(id);reconciliation.reconcileDue();
        assertEquals("CONFIRMED",outcome(id));assertEquals("NOT_VERIFIED",service.status(user.getId()).identityStatus());blocked();
    }
    @Test void repeatedIndependentReadsDoNotExtendIdentityExpiryOrDuplicateEvents() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-10);evidence(id,e);due(id);reconciliation.reconcileDue();var until=service.status(user.getId()).validUntil();
        due(id);reconciliation.reconcileDue();assertEquals(until,service.status(user.getId()).validUntil());assertEquals(1,receipts(id));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM kyc_reconciliation_runs WHERE attempt_id=?",Long.class,id));allowed();
    }
    @Test void archivedRetriesWithChangedDispatchTimeShareOneReceipt() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-10);evidence(id,e);var original=archived(e);e.put("timestamp",now+1);
        HISTORY.put(CREATED.get(id),List.of(archived(e),original));due(id);reconciliation.reconcileDue();assertEquals(1,receipts(id));allowed();
    }
    @Test void missingOrConflictingLatestEventNeverUsesDecisionAsInventedWebhook() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-1);evidence(id,e);HISTORY.put(CREATED.get(id),List.of());due(id);reconciliation.reconcileDue();
        assertEquals("EVIDENCE_MISMATCH",outcome(id));assertEquals(0,receipts(id));blocked();
        HISTORY.put(CREATED.get(id),List.of(archived(e),archived(event(id,"Declined",now-1))));due(id);reconciliation.reconcileDue();
        assertEquals("CONFLICT",outcome(id));assertEquals(2,receipts(id));blocked();
        assertNotEquals(id,start());blocked();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"application_id","environment","workflow_id","workflow_version","session_id","vendor_data","session_kind","sandbox_scenario"})
    void archivedBindingsAreValidatedAgainBeforeAnyEffect(String field) throws Exception {
        UUID id=start();var e=event(id,"Approved",now-1);evidence(id,e);
        e.put(field,switch(field){case "workflow_version"->5;case "environment"->"sandbox";case "session_kind"->"business";case "sandbox_scenario"->"approved";default->UUID.randomUUID();});
        HISTORY.put(CREATED.get(id),List.of(archived(e)));due(id);reconciliation.reconcileDue();
        assertEquals("FAILED",outcome(id));assertEquals(0,receipts(id));blocked();
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"session_id","session_kind","vendor_data","workflow_id","environment"})
    void decisionBindingsCannotBeBorrowedFromAnotherSession(String field) throws Exception {
        UUID id=start();evidence(id,event(id,"Approved",now-1));DECISIONS.get(CREATED.get(id)).put(field,"another");due(id);reconciliation.reconcileDue();
        assertEquals("FAILED",outcome(id));assertEquals(0,receipts(id));blocked();
    }
    @Test void redactedOrTamperedArchiveNeverFallsBackToApiTrustAlone() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-1);evidence(id,e);var old=archived(e);var altered=new HashMap<>(old);
        e.put("metadata",Map.of("redacted",true));altered.put("request_body",new String(bytes(e),StandardCharsets.UTF_8));
        HISTORY.put(CREATED.get(id),List.of(altered));due(id);reconciliation.reconcileDue();assertEquals("FAILED",outcome(id));blocked();
    }
    @Test void providerFailureUsesPersistentBackoffAndDoesNotRenewFreshness() throws Exception {
        UUID id=start();send(event(id,"Approved",now-1));var before=freshness(id);mode="POLL_FAIL";
        due(id);reconciliation.reconcileDue();assertEquals(before,freshness(id));assertEquals("FAILED",outcome(id));
        assertFalse(reconciliation.reconcileDue());assertEquals(1,jdbc.queryForObject("SELECT failures FROM kyc_reconciliations WHERE attempt_id=?",Integer.class,id));
        due(id);reconciliation.reconcileDue();assertEquals(2,jdbc.queryForObject("SELECT failures FROM kyc_reconciliations WHERE attempt_id=?",Integer.class,id));
        assertTrue(jdbc.queryForObject("SELECT next_attempt_at>clock_timestamp()+interval '100 seconds' FROM kyc_reconciliations WHERE attempt_id=?",Boolean.class,id));
        jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp()-interval '16 minutes' WHERE attempt_id=?",id);blocked();
        assertTrue(service.status(user.getId()).validUntil().isAfter(Instant.now()));
    }
    @Test void duplicateLiveWebhookCannotRenewExpiredReconciliationFreshness() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-1);send(e);
        jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp()-interval '16 minutes' WHERE attempt_id=?",id);send(e);blocked();
        evidence(id,e);due(id);reconciliation.reconcileDue();allowed();
    }
    @Test void staleLiveEventDoesNotInvalidateNewerFreshApproval() throws Exception {
        UUID id=start();send(event(id,"Approved",now-1));var before=freshness(id);send(event(id,"Declined",now-10));
        assertEquals(before,freshness(id));allowed();
    }
    @Test void inFlightLiveWebhookInvalidatesOlderPollWithoutHoldingUserLock() throws Exception {
        UUID id=start();evidence(id,event(id,"Approved",now-10));
        pollHook=()->{try {send(event(id,"Declined",now-1));} catch(Exception e){throw new RuntimeException(e);}};
        due(id);reconciliation.reconcileDue();assertEquals("STALE_READ",outcome(id));assertEquals("NOT_VERIFIED",service.status(user.getId()).identityStatus());blocked();
    }
    @Test void newAttemptWhileReadingCannotBeApprovedBySupersededSnapshot() throws Exception {
        UUID id=start();send(event(id,"Declined",now-10));evidence(id,event(id,"Approved",now-1));var next=new AtomicReference<UUID>();
        pollHook=()->next.set(start());due(id);reconciliation.reconcileDue();
        assertNotEquals(id,next.get());assertEquals(next.get(),service.status(user.getId()).attemptId());assertEquals("PENDING",service.status(user.getId()).identityStatus());blocked();
    }
    @Test void twoWorkersCannotClaimSameAttemptAndHttpDoesNotKeepUserLock() throws Exception {
        UUID id=start();evidence(id,event(id,"Approved",now-1));var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        pollHook=()->{entered.countDown();try{assertTrue(finish.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        due(id);try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(reconciliation::reconcileDue);assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertFalse(reconciliation.reconcileDue());pool.submit(()->new TransactionTemplate(manager).execute(status->users.lockById(user.getId()))).get(2,TimeUnit.SECONDS);
            finish.countDown();assertTrue(first.get(5,TimeUnit.SECONDS));
        } finally {finish.countDown();}
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM kyc_reconciliation_runs WHERE attempt_id=?",Long.class,id));allowed();
    }
    @Test void expiredLeaseDiscardsZombieAndFreshWorkerRecoversPersistedAttempt() throws Exception {
        UUID id=start();evidence(id,event(id,"Approved",now-1));
        pollHook=()->jdbc.update("UPDATE kyc_reconciliations SET lease_until=clock_timestamp()-interval '1 second' WHERE attempt_id=?",id);
        due(id);reconciliation.reconcileDue();assertEquals(0,receipts(id));blocked();pollHook=null;
        var restarted=new KycReconciliationService(settings,didit,verifier,service,jdbc,users,audit,manager,cdd);
        assertTrue(restarted.reconcileDue());assertEquals(1,receipts(id));allowed();
    }
    @Test void finalAuditFailureRollsBackEntireRecoveredBatchAndLeaseCanBeReclaimed() throws Exception {
        UUID id=start();var a=event(id,"Approved",now-10);var b=event(id,"Declined",now-1);evidence(id,b);
        HISTORY.put(CREATED.get(id),List.of(archived(b),archived(a)));due(id);
        doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("KYC_RECONCILIATION"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,reconciliation::reconcileDue);assertEquals(0,receipts(id));assertEquals("PENDING",service.status(user.getId()).identityStatus());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM kyc_reconciliation_runs WHERE attempt_id=?",Long.class,id));blocked();reset(audit);
        jdbc.update("UPDATE kyc_reconciliations SET lease_until=clock_timestamp()-interval '1 second' WHERE attempt_id=?",id);
        reconciliation.reconcileDue();assertEquals(2,receipts(id));assertEquals("NOT_VERIFIED",service.status(user.getId()).identityStatus());
    }
    @Test void recoveredIdentityNeverRemovesManualRestriction() throws Exception {
        UUID id=start();var u=users.findById(user.getId()).orElseThrow();u.setTradingRestricted(true);users.saveAndFlush(u);
        evidence(id,event(id,"Approved",now-1));due(id);reconciliation.reconcileDue();assertEquals("VERIFIED",service.status(user.getId()).identityStatus());blocked();
    }
    @Test void reconciliationHistoryCannotBeChangedDeletedOrTruncated() throws Exception {
        UUID id=start();evidence(id,event(id,"Approved",now-1));due(id);reconciliation.reconcileDue();
        for(String statement:List.of("UPDATE kyc_reconciliation_runs SET outcome='FAILED'","DELETE FROM kyc_reconciliation_runs","TRUNCATE kyc_reconciliation_runs"))
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(statement));
    }
    @Test void schedulerEntryCannotJoinAnOuterTransaction() {
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->new TransactionTemplate(manager).execute(status->reconciliation.reconcileDue()));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->service.recoverEvent(JSON.createObjectNode()));assertEquals(0,POLLS.get());
    }
    @Test void unknownCreationIsRecoveredWithReadsWithoutCustomerSessionOrSecondPost() throws Exception {
        mode="LOST_RESPONSE";assertThrows(ResponseStatusException.class,()->service.start(user.getId(),sid,UUID.randomUUID()));UUID id=service.status(user.getId()).attemptId();
        var token=sessions.findBySessionId(sid).orElseThrow();token.setRevoked(true);sessions.saveAndFlush(token);
        mode="NORMAL";evidence(id,event(id,"Approved",now-1));due(id);reconciliation.reconcileDue();
        assertEquals("CONFIRMED",outcome(id));assertEquals("READY",service.status(user.getId()).creationState());assertEquals(1,POSTS.get());assertEquals(1,GETS.get());allowed();
    }
    @Test void oversizedReconciliationResponseIsRejectedBySharedHttpBoundary() throws Exception {
        UUID id=start();evidence(id,event(id,"Approved",now-1));mode="POLL_BIG";due(id);reconciliation.reconcileDue();assertEquals("FAILED",outcome(id));blocked();
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"180","invalid","-1","999999999"})
    void rateLimitIsPersistedAcrossCustomersWithBoundedRetryAfter(String header) throws Exception {
        UUID id=start();evidence(id,event(id,"Approved",now-1));mode="POLL_RATE";retryHeader=header;due(id);reconciliation.reconcileDue();
        assertEquals("RATE_LIMITED",outcome(id));int requests=POLLS.get();
        int expected=header.equals("180")?180:header.equals("999999999")?86400:60;
        assertTrue(jdbc.queryForObject("SELECT not_before>clock_timestamp()+(? * interval '1 second') FROM kyc_provider_cooldowns WHERE application_id=?",Boolean.class,expected-5,APP));
        var other=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash("격리").build());
        var token=sessions.saveAndFlush(RefreshToken.builder().userId(other.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        UUID another=service.start(other.getId(),token.getSessionId(),UUID.randomUUID()).status().attemptId();
        evidence(another,event(another,"Approved",now-1));due(another);assertFalse(reconciliation.reconcileDue());assertEquals(requests,POLLS.get());
        mode="NORMAL";jdbc.update("UPDATE kyc_provider_cooldowns SET not_before=clock_timestamp()-interval '1 second' WHERE application_id=?",APP);
        assertTrue(reconciliation.reconcileDue());assertTrue(POLLS.get()>requests);blocked();
    }
    @Test void expiredFreshnessAllowsExplicitNewVerificationButDoesNotReuseOldRequestKey() throws Exception {
        UUID key=UUID.randomUUID();UUID old=service.start(user.getId(),sid,key).status().attemptId();send(event(old,"Approved",now-10));
        jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp()-interval '16 minutes' WHERE attempt_id=?",old);blocked();
        UUID next=start();assertNotEquals(old,next);assertThrows(ResponseStatusException.class,()->service.start(user.getId(),sid,key));
        send(event(old,"Approved",now-1));assertEquals("PENDING",service.status(user.getId()).identityStatus());blocked();
    }
    @Test void statusExposesLastConfirmationAndReconciliationFailureWithoutRawEvidence() throws Exception {
        UUID id=start();send(event(id,"Approved",now-1));mode="POLL_FAIL";due(id);reconciliation.reconcileDue();
        var result=http("GET","/api/kyc/session",access,null,Map.of());assertEquals(200,result.statusCode());var status=JSON.readTree(result.body()).path("status");
        assertEquals("FAILED",status.path("reconciliationOutcome").asText());assertTrue(status.path("lastConfirmedAt").isString());
        assertFalse(result.body().contains(SECRET));assertFalse(result.body().contains("request_body"));
    }
    @SuppressWarnings("unchecked")
    @Test void providerExpiryCapsLocalThirtyDayWindowForLiveAndRecoveredApproval() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-1);var decision=new HashMap<>((Map<String,Object>)e.get("decision"));
        Instant expiry=Instant.ofEpochSecond(now+60);decision.put("expires_at",expiry.toString());e.put("decision",decision);send(e);
        assertEquals(expiry,service.status(user.getId()).validUntil());allowed();evidence(id,e);due(id);reconciliation.reconcileDue();assertEquals(expiry,service.status(user.getId()).validUntil());
        decision.put("expires_at",Instant.ofEpochSecond(now-10).toString());e.put("event_id",UUID.randomUUID());e.put("created_at",now);e.put("decision",decision);evidence(id,e);
        due(id);reconciliation.reconcileDue();blocked();assertEquals(Instant.ofEpochSecond(now-10),service.status(user.getId()).validUntil());
    }
    @Test void failedRecoveryAuditDoesNotOverwriteLeaseOrExtendConfirmation() throws Exception {
        UUID id=start();evidence(id,event(id,"Approved",now-1));due(id);
        doThrow(new IllegalStateException("격리 복구 감사 실패")).when(audit).record(any(),eq("KYC_EVENT_RECOVERED"),anyString(),anyString(),anyString());
        reconciliation.reconcileDue();assertEquals("FAILED",outcome(id));assertEquals(0,receipts(id));assertNull(freshness(id));blocked();
    }

    @Test void pastAttemptWithoutNewWorkRecordIsNeverBackfilledOrSilentlyReposted() {
        UUID key=UUID.randomUUID();UUID old=service.start(user.getId(),sid,key).status().attemptId();
        jdbc.update("DELETE FROM kyc_reconciliations WHERE attempt_id=?",old);
        assertFalse(reconciliation.reconcileDue());assertEquals(1,POSTS.get());
        assertEquals(old,service.start(user.getId(),sid,key).status().attemptId());assertEquals(1,POSTS.get());
        UUID next=start();assertNotEquals(old,next);assertEquals(2,POSTS.get());assertEquals("PENDING",service.status(user.getId()).identityStatus());blocked();
    }

    @Test void staleProviderReadCannotRenewNewerLocalApproval() throws Exception {
        UUID id=start();send(event(id,"Approved",now-1));allowed();
        evidence(id,event(id,"Approved",now-100));due(id);reconciliation.reconcileDue();
        assertEquals("EVIDENCE_MISMATCH",outcome(id));assertNull(freshness(id));assertEquals("VERIFIED",service.status(user.getId()).identityStatus());blocked();
        evidence(id,event(id,"Approved",now));due(id);reconciliation.reconcileDue();allowed();
    }


    // 아래 사례는 승인된 DB fixture를 넣지 않고 신고 API·서명된 공급사 사실·실제 주문 정책을 연결한다.
    Map<String,Object> cddDecision(Map<String,Object> event) {return new HashMap<>((Map<String,Object>)event.get("decision"));}
    Map<String,Object> cddId(Map<String,Object> decision) {return new HashMap<>(((List<Map<String,Object>>)decision.get("id_verifications")).getFirst());}
    Map<String,Object> cddAml(Map<String,Object> decision) {return new HashMap<>(((List<Map<String,Object>>)decision.get("aml_screenings")).getFirst());}
    void changeId(Map<String,Object> e,String field,Object value) {var d=cddDecision(e);var id=cddId(d);id.put(field,value);d.put("id_verifications",List.of(id));e.put("decision",d);}
    void changeAml(Map<String,Object> e,String field,Object value) {var d=cddDecision(e);var aml=cddAml(d);aml.put(field,value);d.put("aml_screenings",List.of(aml));e.put("decision",d);}
    void review(String reason) {assertEquals("REVIEW_REQUIRED",cdd.status(user.getId()).outcome());assertTrue(cdd.status(user.getId()).reasons().contains(reason));blocked();}
    dustin.cex.domains.cdd.CustomerDeclaration changedDeclaration(String field,Object value) {
        var n=JSON.valueToTree(declaration());((tools.jackson.databind.node.ObjectNode)n).set(field,JSON.valueToTree(value));
        return JSON.treeToValue(n,dustin.cex.domains.cdd.CustomerDeclaration.class);
    }
    @Test void cddCompleteSignedEvidenceProducesVersionedAssessmentWithoutPii() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);send(e);allowed();assertEquals("STANDARD",cdd.status(user.getId()).outcome());
        var row=jdbc.queryForMap("SELECT * FROM cdd_assessments WHERE user_id=?",user.getId());
        assertEquals(dustin.cex.domains.cdd.CddService.POLICY,row.get("policy_version"));assertEquals(e.get("event_id"),row.get("event_id"));
        assertFalse(row.toString().contains(declaration().fullName()));assertFalse(row.toString().contains("1990-01-01"));
        var ciphertext=jdbc.queryForObject("SELECT ciphertext FROM cdd_declarations WHERE user_id=?",byte[].class,user.getId());
        assertFalse(new String(ciphertext,StandardCharsets.UTF_8).contains(declaration().fullName()));
    }
    @Test void cddIdentityApprovalWithoutCustomerDeclarationCannotTrade() throws Exception {
        jdbc.update("DELETE FROM cdd_profiles WHERE user_id=?",user.getId());UUID id=start();send(event(id,"Approved",now));blocked();
        assertEquals("NOT_DECLARED",cdd.status(user.getId()).outcome());
    }
    @Test void cddDeclarationAfterSessionCannotReuseItsIdentityEvidence() throws Exception {
        UUID id=start();cdd.submit(user.getId(),sid,UUID.randomUUID(),declaration());send(event(id,"Approved",now));blocked();
        assertEquals("PENDING_IDENTITY",cdd.status(user.getId()).outcome());
    }
    @Test void cddNameMismatchNeverUsesFuzzyOrTransliteratedMatch() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);changeId(e,"full_name",declaration().fullName()+" 다른 사람");send(e);review("IDENTITY_MISMATCH");
    }
    @Test void cddBirthdayMismatchRequiresReview() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);changeId(e,"date_of_birth","1991-01-01");send(e);review("IDENTITY_MISMATCH");
    }
    @Test void cddIncompleteExtractedIdentityCannotApproveRisk() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);changeId(e,"date_of_birth",null);send(e);review("IDENTITY_EVIDENCE_INCOMPLETE");
    }
    @Test void cddConflictingApprovedDocumentsCannotApproveRisk() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);var d=cddDecision(e);var one=cddId(d);var two=new HashMap<>(one);two.put("full_name","다른 사람");
        d.put("id_verifications",List.of(one,two));e.put("decision",d);send(e);review("IDENTITY_EVIDENCE_INCOMPLETE");
    }
    @Test void cddMissingAmlCannotBeReplacedByIdentityApproval() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);var d=cddDecision(e);d.remove("aml_screenings");e.put("decision",d);send(e);review("AML_REVIEW_REQUIRED");
    }
    @Test void cddApprovedAmlWithPositiveHitsStillRequiresReview() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);changeAml(e,"total_hits",1);send(e);review("AML_REVIEW_REQUIRED");
    }
    @Test void cddAmlScoreWarningAndHitDetailsCannotBeHiddenByZeroCount() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);changeAml(e,"hits",List.of(Map.of("name","가상 제재 후보")));send(e);review("AML_REVIEW_REQUIRED");
    }
    @Test void cddAmlScreenedPersonMustMatchVerifiedPerson() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);changeAml(e,"screened_data",Map.of("full_name","다른 사람","date_of_birth","1990-01-01","nationality","KR"));
        send(e);review("AML_REVIEW_REQUIRED");
    }
    @Test void cddPepDeclarationClosesNewOrdersWithoutManualOverride() throws Exception {
        cdd.submit(user.getId(),sid,UUID.randomUUID(),changedDeclaration("pep","YES"));UUID id=start();send(event(id,"Approved",now));review("PEP_REVIEW_REQUIRED");
    }
    @Test void cddNonSelfBeneficialOwnerRequiresReview() throws Exception {
        cdd.submit(user.getId(),sid,UUID.randomUUID(),changedDeclaration("beneficialOwnerSelf",false));UUID id=start();send(event(id,"Approved",now));review("BENEFICIAL_OWNER_REVIEW_REQUIRED");
    }
    @Test void cddForeignResidenceIsOutsideInitialAutomaticPolicy() throws Exception {
        cdd.submit(user.getId(),sid,UUID.randomUUID(),changedDeclaration("residenceCountry","USA"));UUID id=start();send(event(id,"Approved",now));review("COUNTRY_POLICY_REVIEW_REQUIRED");
    }
    @Test void cddChangedProfileInvalidatesOldApprovalAndRequiresNewAttempt() throws Exception {
        UUID key=UUID.randomUUID();UUID old=service.start(user.getId(),sid,key).status().attemptId();send(event(old,"Approved",now));allowed();
        cdd.submit(user.getId(),sid,UUID.randomUUID(),changedDeclaration("address","변경된 가상 주소"));blocked();
        assertThrows(ResponseStatusException.class,()->service.start(user.getId(),sid,key));UUID next=start();assertNotEquals(old,next);
        send(event(next,"Approved",now));allowed();assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM cdd_assessments WHERE user_id=?",Long.class,user.getId()));
    }
    @Test void cddSubmissionKeyIsBoundToCurrentCanonicalBody() {
        UUID key=UUID.randomUUID();var one=cdd.submit(user.getId(),sid,key,declaration());assertEquals(one,cdd.submit(user.getId(),sid,key,declaration()));
        assertThrows(ResponseStatusException.class,()->cdd.submit(user.getId(),sid,key,changedDeclaration("pep","YES")));
        cdd.submit(user.getId(),sid,UUID.randomUUID(),declaration());assertThrows(ResponseStatusException.class,()->cdd.submit(user.getId(),sid,key,declaration()));
    }
    @Test void cddImmutableHistoryRejectsUpdateDeleteAndTruncate() throws Exception {
        UUID id=start();send(event(id,"Approved",now));
        for(String table:List.of("cdd_declarations","cdd_assessments","cdd_attempt_bindings","cdd_identity_claims")) {
            String field=table.equals("cdd_attempt_bindings")?"attempt_id":table.equals("cdd_identity_claims")?"user_id":"id";
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE "+table+" SET "+field+"="+field));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM "+table));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE "+table+" CASCADE"));
        }
        allowed();
    }
    @Test void cddAuditFailureRollsBackDeclarationAndAssessment() throws Exception {
        UUID id=start();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CDD_ASSESSED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->send(event(id,"Approved",now)));assertEquals(0,receipts(id));blocked();reset(audit);
        send(event(id,"Approved",now));allowed();
    }
    @Test void cddSameSignedEventDoesNotDuplicateAssessment() throws Exception {
        UUID id=start();var e=event(id,"Approved",now);send(e);send(e);allowed();
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM cdd_assessments WHERE user_id=?",Long.class,user.getId()));
    }
    @Test void cddProviderIdentityChangeCannotRefreshOldSignedEvidence() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-10);send(e);allowed();evidence(id,e);
        var current=new HashMap<>(DECISIONS.get(CREATED.get(id)));var one=cddId(current);one.put("full_name","다른 사람");current.put("id_verifications",List.of(one));DECISIONS.put(CREATED.get(id),current);
        due(id);reconciliation.reconcileDue();assertEquals("EVIDENCE_MISMATCH",outcome(id));blocked();
    }
    @Test void cddProviderAmlChangeCannotRefreshOldSignedEvidence() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-10);send(e);allowed();evidence(id,e);
        var current=new HashMap<>(DECISIONS.get(CREATED.get(id)));var aml=cddAml(current);aml.put("total_hits",1);current.put("aml_screenings",List.of(aml));DECISIONS.put(CREATED.get(id),current);
        due(id);reconciliation.reconcileDue();assertEquals("EVIDENCE_MISMATCH",outcome(id));blocked();
    }
    @Test void cddCurrentPolicyAndKeyMustMatchAssessment() throws Exception {
        UUID id=start();send(event(id,"Approved",now));allowed();
        var wrong=new dustin.cex.domains.cdd.CddService(new dustin.cex.domains.cdd.CddCrypto(
            Base64.getEncoder().encodeToString(new byte[32]),Base64.getEncoder().encodeToString("12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8)),""),jdbc,JSON,users,sessions,audit,verifier);
        assertFalse(wrong.permitted(user.getId()));
    }
    @Test void cddHttpRequiresCustomerSessionAndNeverReturnsOriginalPii() throws Exception {
        assertEquals(401,http("GET","/api/kyc/customer-profile",null,null,Map.of()).statusCode());
        var own=http("GET","/api/kyc/customer-profile",access,null,Map.of());assertEquals(200,own.statusCode());assertFalse(own.body().contains(declaration().fullName()));assertEquals("no-store",own.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(403,http("GET","/api/admin/kyc/customer-profiles/"+user.getId(),access,null,Map.of()).statusCode());
        assertEquals(200,http("POST","/api/kyc/customer-profile",access,JSON.writeValueAsString(declaration()),Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());
    }
    @Test void cddHttpRejectsUnknownDuplicateOversizeAndInvalidValuesWithoutEcho() throws Exception {
        String raw=JSON.writeValueAsString(declaration());
        for(String body:List.of(raw.substring(0,raw.length()-1)+",\"documentNumber\":\"비밀 번호\"}",raw.substring(0,raw.length()-1)+",\"pep\":\"YES\"}",raw.replace("EMPLOYED","비밀 직업"))) {
            var response=http("POST","/api/kyc/customer-profile",access,body,Map.of("Idempotency-Key",UUID.randomUUID().toString()));
            assertEquals(400,response.statusCode());assertFalse(response.body().contains("비밀"));
        }
        assertEquals(413,http("POST","/api/kyc/customer-profile",access," ".repeat(8193),Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());
    }

    @Test void cddConcurrentSameIdentityAllowsOnlyOneCustomerAndDoesNotFreezeFirstClaim() throws Exception {
        var other=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash("격리 검증").build());
        var token=sessions.saveAndFlush(RefreshToken.builder().userId(other.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        cdd.submit(other.getId(),token.getSessionId(),UUID.randomUUID(),declaration());
        UUID one=start(),two=service.start(other.getId(),token.getSessionId(),UUID.randomUUID()).status().attemptId();
        var first=event(one,"Approved",now);var second=event(two,"Approved",now);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var a=pool.submit(()->{gate.await();send(first);return true;});var b=pool.submit(()->{gate.await();send(second);return true;});
            gate.countDown();assertTrue(a.get(8,TimeUnit.SECONDS));assertTrue(b.get(8,TimeUnit.SECONDS));
        }
        var statuses=List.of(cdd.status(user.getId()),cdd.status(other.getId()));
        assertEquals(1,statuses.stream().filter(x->x.outcome().equals("STANDARD")).count());
        assertEquals(1,statuses.stream().filter(x->x.reasons().contains("POSSIBLE_DUPLICATE_IDENTITY")).count());
        assertEquals(1,List.of(user.getId(),other.getId()).stream().filter(cdd::permitted).count());
    }
    @Test void cddSubmissionRequiresCurrentOwnedCustomerSession() {
        assertThrows(ResponseStatusException.class,()->cdd.submit(user.getId(),UUID.randomUUID(),UUID.randomUUID(),declaration()));
        new TransactionTemplate(manager).executeWithoutResult(status->sessions.revokeAllByUserId(user.getId(),LocalDateTime.now()));
        assertThrows(ResponseStatusException.class,()->cdd.submit(user.getId(),sid,UUID.randomUUID(),declaration()));
    }
    @Test void cddSubmissionHasBoundedDailyChangesAndAtomicAudit() {
        cdd.submit(user.getId(),sid,UUID.randomUUID(),declaration());cdd.submit(user.getId(),sid,UUID.randomUUID(),declaration());
        assertEquals(429,assertThrows(ResponseStatusException.class,()->cdd.submit(user.getId(),sid,UUID.randomUUID(),declaration())).getStatusCode().value());
    }
    @Test void cddDeclarationAuditFailurePreservesPreviousPointer() {
        var before=cdd.status(user.getId());
        doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CDD_DECLARED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->cdd.submit(user.getId(),sid,UUID.randomUUID(),declaration()));
        assertEquals(before,cdd.status(user.getId()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM cdd_declarations WHERE user_id=?",Long.class,user.getId()));
    }
    @Test void cddCustomerIdentityHistoryCannotBecomeDedicatedOperatorAccount() {
        var bootstrap=new OperatorBootstrapService(manager,users,sessions,audit,jdbc);
        assertThrows(IllegalStateException.class,()->bootstrap.grant(user.getId(),UUID.randomUUID(),UserRole.OPERATOR,"isolated-enrollment-secret-256-bits-at-least-43-characters"));
        assertEquals(UserRole.CUSTOMER,users.findById(user.getId()).orElseThrow().getRole());
    }

    @Test void cddReorderedAmlObjectFieldsRemainSameEvidenceDuringRecovery() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-10);send(e);evidence(id,e);
        var current=new HashMap<>(DECISIONS.get(CREATED.get(id)));var aml=cddAml(current);
        var reordered=new LinkedHashMap<String,Object>();reordered.put("nationality","KR");reordered.put("date_of_birth","1990-01-01");reordered.put("full_name",declaration().fullName());
        aml.put("screened_data",reordered);current.put("aml_screenings",List.of(aml));DECISIONS.put(CREATED.get(id),current);
        due(id);reconciliation.reconcileDue();assertEquals("CONFIRMED",outcome(id));allowed();
    }

    @Test void cddAmlAmbiguousAndMalformedResultsNeverPassAutomatically() throws Exception {
        UUID id=start();int time=0;
        for(var mutation:List.of(Map.entry("score",(Object)1),Map.entry("warnings",(Object)List.of("가상 경고")),
                Map.entry("total_hits",(Object)0.0),Map.entry("total_hits",(Object)4294967296L),
                Map.entry("status",(Object)"In Review"),Map.entry("entity_type",(Object)"company"),Map.entry("hits",(Object)"누락된 배열"))) {
            var e=event(id,"Approved",now-20+time++);changeAml(e,mutation.getKey(),mutation.getValue());send(e);review("AML_REVIEW_REQUIRED");
        }
    }
    @Test void cddUnknownPepIsNotInterpretedAsNoPep() throws Exception {
        cdd.submit(user.getId(),sid,UUID.randomUUID(),changedDeclaration("pep","UNKNOWN"));UUID id=start();send(event(id,"Approved",now));review("PEP_REVIEW_REQUIRED");
    }
    @Test void cddUnexplainedCustomerContextRequiresReview() throws Exception {
        cdd.submit(user.getId(),sid,UUID.randomUUID(),changedDeclaration("purpose","OTHER"));UUID id=start();send(event(id,"Approved",now));review("CUSTOMER_CONTEXT_REVIEW_REQUIRED");
    }
    @Test void cddHttpRejectsScalarCoercionAndDateArrays() throws Exception {
        var raw=JSON.valueToTree(declaration());
        for(var mutation:List.of(Map.entry("pep",(Object)0),Map.entry("beneficialOwnerSelf",(Object)"true"),
                Map.entry("expectedMonthlyQuote",(Object)"1000"),Map.entry("dateOfBirth",(Object)List.of(1990,1,1)))) {
            var node=raw.deepCopy();((tools.jackson.databind.node.ObjectNode)node).set(mutation.getKey(),JSON.valueToTree(mutation.getValue()));
            assertEquals(400,http("POST","/api/kyc/customer-profile",access,JSON.writeValueAsString(node),Map.of("Idempotency-Key",UUID.randomUUID().toString())).statusCode());
        }
    }

    @Test void cddMissingIdentityFieldCanBeReconciledButRemainsReviewRequired() throws Exception {
        UUID id=start();var e=event(id,"Approved",now-10);var d=cddDecision(e);var document=cddId(d);document.remove("full_name");
        d.put("id_verifications",List.of(document));e.put("decision",d);send(e);review("IDENTITY_EVIDENCE_INCOMPLETE");
        evidence(id,e);due(id);reconciliation.reconcileDue();assertEquals("CONFIRMED",outcome(id));review("IDENTITY_EVIDENCE_INCOMPLETE");
    }

    @Test void cddUnneededDocumentNumberDoesNotEnterRiskEvidence() {
        UUID id=start();var decision=cddDecision(event(id,"Approved",now));String before=cdd.evidenceDigest(JSON.valueToTree(decision));
        var aml=cddAml(decision);var screened=new HashMap<String,Object>((Map<String,Object>)aml.get("screened_data"));
        screened.put("document_number","가상 수집 제외 값");aml.put("screened_data",screened);decision.put("aml_screenings",List.of(aml));
        assertEquals(before,cdd.evidenceDigest(JSON.valueToTree(decision)));
    }
}
