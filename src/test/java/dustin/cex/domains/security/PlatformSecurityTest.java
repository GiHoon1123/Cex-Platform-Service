package dustin.cex.domains.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.model.dto.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.*;
import dustin.cex.domains.order.service.OrderService;
import dustin.cex.domains.order.model.dto.CreateOrderRequest;
import dustin.cex.domains.ledger.LedgerService;
import dustin.cex.shared.http.EngineHttpClient;

/** 실행 소유 PostgreSQL에서 실제 보안 체인·트랜잭션·회수·승인 경쟁을 함께 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformSecurityTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));
        r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");
        r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;@Autowired RefreshTokenRepository tokens;@Autowired JwtService jwt;
    @Autowired SessionService sessionService;
    @Autowired PasswordService passwords;@Autowired AuthService auth;@Autowired OperatorApprovalService approvals;
    @Autowired TradingAccessPolicy access;@Autowired OrderService orders;@Autowired LedgerService ledger;
    @Autowired PlatformTransactionManager manager;
    @SpyBean AuditService audit;
    @MockBean EngineHttpClient engine;
    @MockBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockBean dustin.cex.domains.fee.service.FeeConfigService fees;
    String hash;User customer,operator,approver,auditor;TransactionTemplate tx;
    @BeforeAll void opening() {hash=passwords.hash("security-test-password");tx=new TransactionTemplate(manager);ledger.initializeOpening();}
    User user(UserRole role,UUID person) {
        return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@example.test").passwordHash(hash)
            .role(role).operatorPersonId(person).build());
    }
    @BeforeEach void setup() {
        customer=user(UserRole.CUSTOMER,null);operator=user(UserRole.OPERATOR,UUID.randomUUID());
        approver=user(UserRole.APPROVER,UUID.randomUUID());auditor=user(UserRole.AUDITOR,UUID.randomUUID());
        when(fees.getFeeConfig(anyString(),anyString())).thenReturn(dustin.cex.domains.fee.model.entity.FeeConfig.builder().feeRate(new BigDecimal("0.001")).build());
        when(engine.submitOrder(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),any())).thenReturn(true);
    }
    String token(User user) {
        var row=tokens.saveAndFlush(RefreshToken.builder().userId(user.getId()).tokenHash(UUID.randomUUID().toString())
            .expiresAt(LocalDateTime.now().plusDays(1)).build());
        String encoded=jwt.generateAccessToken(user.getId(),row.getSessionId());
        jwt.verifyAccessToken(encoded);sessionService.authenticate(user.getId(),row.getSessionId());
        return encoded;
    }
    String body(Object value) throws Exception {return json.writeValueAsString(value);}
    OperatorApprovalService.Request request() {return approvals.request(operator.getId(),customer.getId(),customer.getVersion(),true,"보안 검토");}
    void decide(OperatorApprovalService.Request r) {approvals.decide(approver.getId(),r.id(),r.payloadHash(),"security-test-password",true);}
    @Test void apiNeverRedirectsToLoginPageOrChallengesBasic() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().doesNotExist("Location")).andExpect(header().doesNotExist("WWW-Authenticate"));
        mvc.perform(get("/login")).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Location"));
    }
    @Test void settlementAndPrivateTradeDataAreNotPublic() throws Exception {
        for(String path:List.of("/api/settlement/trade/failures","/api/cex/trades"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
    @Test void oldUnapprovedSettlementWritesAndUnknownRoutesAreDenied() throws Exception {
        for(String path:List.of("/api/settlement/trade/manual/daily","/api/settlement/trade/validate/2026-10-22","/api/cex/new-unreviewed-action"))
            mvc.perform(post(path).header("Authorization","Bearer "+token(operator))).andExpect(status().isForbidden());
    }
    @Test void auditUsesPeerAddressAndPreservesRoleSnapshot() throws Exception {
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+token(auditor)).header("X-Forwarded-For","203.0.113.99"))
            .andExpect(status().isOk());
        var row=jdbc.queryForMap("SELECT actor_role,source_address FROM security_audit WHERE actor_id=? AND action='HTTP_AUTHORIZATION' ORDER BY occurred_at DESC LIMIT 1",auditor.getId());
        assertEquals("AUDITOR",row.get("actor_role"));assertEquals("127.0.0.1",row.get("source_address"));
    }
    @Test void customerAndOperatorCannotEscalateRoles() throws Exception {
        mvc.perform(get("/api/settlement/trade/failures").header("Authorization","Bearer "+token(customer))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/requests/"+UUID.randomUUID()+"/approve").header("Authorization","Bearer "+token(operator))
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/cex/orders").header("Authorization","Bearer "+token(operator)).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
    @Test void roleChangeAndDisableAffectExistingAccessToken() throws Exception {
        String old=token(auditor);auditor.setRole(UserRole.CUSTOMER);auditor=users.saveAndFlush(auditor);
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+old)).andExpect(status().isForbidden());
        auditor.setEnabled(false);users.saveAndFlush(auditor);
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+old)).andExpect(status().isUnauthorized());
    }
    @Test void logoutRevokesAccessAndRefreshTogether() throws Exception {
        var signed=auth.signin(new SigninRequest(customer.getEmail(),"security-test-password"));
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer "+signed.getAccessToken())
            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("refreshToken",signed.getRefreshToken())))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+signed.getAccessToken())).andExpect(status().isUnauthorized());
        assertThrows(RuntimeException.class,()->auth.refreshAccessToken(signed.getRefreshToken()));
    }
    @Test void logoutCannotRevokeAnotherPersonsSession() throws Exception {
        var other=auth.signin(new SigninRequest(operator.getEmail(),"security-test-password"));
        mvc.perform(post("/api/auth/logout").header("Authorization","Bearer "+token(customer)).contentType(MediaType.APPLICATION_JSON)
            .content(body(Map.of("refreshToken",other.getRefreshToken())))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+other.getAccessToken())).andExpect(status().isOk());
    }
    @Test void refreshRotationHasOneWinnerUnderConcurrency() throws Exception {
        var signed=auth.signin(new SigninRequest(customer.getEmail(),"security-test-password"));
        var gate=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)) {
            var work=(Callable<Boolean>)()->{gate.await();try {auth.refreshAccessToken(signed.getRefreshToken());return true;} catch(dustin.cex.domains.auth.exception.AuthException denied){return false;}};
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+signed.getAccessToken())).andExpect(status().isUnauthorized());
    }
    @Test void fakeSessionAndWrongTokenPurposeAreRejected() throws Exception {
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+jwt.generateAccessToken(customer.getId(),UUID.randomUUID()))).andExpect(status().isUnauthorized());
        var key=io.jsonwebtoken.security.Keys.hmacShaKeyFor("isolated-test-jwt-secret-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(String purpose:List.of("refresh","access")) {
            String invalid=io.jsonwebtoken.Jwts.builder().issuer("other-issuer").subject(customer.getId().toString()).claim("type",purpose)
                .issuedAt(new Date()).expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
            mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+invalid)).andExpect(status().isUnauthorized());
        }
    }
    @Test void wrongAudiencePurposeAlgorithmAndTokenLifetimeAreRejected() {
        String secret="isolated-test-secret-with-64-bytes-"+"a".repeat(64);
        var service=new JwtService(secret);var key=io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var now=Instant.now().minusSeconds(1);
        for(int defect=0;defect<5;defect++) {
            var builder=io.jsonwebtoken.Jwts.builder().issuer("cex-platform").audience().add(defect==0?"other":"cex-api").and()
                .subject("1").claim("type",defect==1?"refresh":"access").claim("sid",UUID.randomUUID().toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(defect==2?1800:defect==3?0:60)));
            String encoded=defect==4 ? builder.signWith(key,io.jsonwebtoken.Jwts.SIG.HS512).compact() : builder.signWith(key,io.jsonwebtoken.Jwts.SIG.HS256).compact();
            assertThrows(RuntimeException.class,()->service.verifyAccessToken(encoded));
        }
    }
    @Test void missingAndWeakSecretsFailImmediately() {
        assertThrows(RuntimeException.class,()->new JwtService("short"));
    }
    @Test void requiredSigninAuditFailureDoesNotRevokeOrIssueSessions() {
        String old=token(customer);long before=tokens.count();
        doThrow(new IllegalStateException("감사 장애 주입")).when(audit).record(eq(customer.getId()),eq("SIGNIN"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->auth.signin(new SigninRequest(customer.getEmail(),"security-test-password")));
        var claims=jwt.verifyAccessToken(old);sessionService.authenticate(customer.getId(),UUID.fromString(claims.get("sid",String.class)));
        assertEquals(before,tokens.count());
    }
    @Test void bootstrapIsExplicitRevokesSessionsAndAuditsRoleGrant() {
        var context=mock(org.springframework.context.ConfigurableApplicationContext.class);UUID person=UUID.randomUUID();String old=token(customer);
        var runner=new OperatorBootstrapRunner(manager,users,tokens,audit,jdbc,context,customer.getId(),person,UserRole.OPERATOR);
        runner.run(new org.springframework.boot.DefaultApplicationArguments());
        var current=users.findById(customer.getId()).orElseThrow();assertEquals(UserRole.OPERATOR,current.getRole());assertEquals(person,current.getOperatorPersonId());
        var claims=jwt.verifyAccessToken(old);assertThrows(IllegalArgumentException.class,()->sessionService.authenticate(customer.getId(),UUID.fromString(claims.get("sid",String.class))));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='OPERATOR_BOOTSTRAP' AND target=?",Long.class,"user:"+customer.getId()));
        verify(context).close();
    }
    @Test void bootstrapRejectsCustomerWithFinancialState() {
        fund();var context=mock(org.springframework.context.ConfigurableApplicationContext.class);
        var runner=new OperatorBootstrapRunner(manager,users,tokens,audit,jdbc,context,customer.getId(),UUID.randomUUID(),UserRole.OPERATOR);
        assertThrows(IllegalStateException.class,()->runner.run(new org.springframework.boot.DefaultApplicationArguments()));
        assertEquals(UserRole.CUSTOMER,users.findById(customer.getId()).orElseThrow().getRole());verifyNoInteractions(context);
    }
    @Test void bootstrapRejectsZeroBalanceWithHistoricalLedgerEntries() {
        fund();tx.executeWithoutResult(status->{
            jdbc.update("UPDATE user_balances SET available=0 WHERE user_id=?",customer.getId());
            ledger.record("fund-return:"+customer.getId(),"TEST",List.of(LedgerService.p(customer.getId(),"AVAILABLE","USDT",new BigDecimal("-1000")),LedgerService.p(0,"OPENING","USDT",new BigDecimal("1000"))));
        });
        var context=mock(org.springframework.context.ConfigurableApplicationContext.class);
        var runner=new OperatorBootstrapRunner(manager,users,tokens,audit,jdbc,context,customer.getId(),UUID.randomUUID(),UserRole.OPERATOR);
        assertThrows(IllegalStateException.class,()->runner.run(new org.springframework.boot.DefaultApplicationArguments()));
        assertEquals(UserRole.CUSTOMER,users.findById(customer.getId()).orElseThrow().getRole());verifyNoInteractions(context);
    }
    @Test void publicSignupCannotChooseOperatorRole() throws Exception {
        String email=UUID.randomUUID()+"@example.test";
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("email",email,"password","security-test-password","role","APPROVER","operatorPersonId",UUID.randomUUID())))).andExpect(status().isCreated());
        assertEquals(UserRole.CUSTOMER,users.findByEmail(email).orElseThrow().getRole());
        assertNull(users.findByEmail(email).orElseThrow().getOperatorPersonId());
    }
    @Test void approvalRunsThroughActualHttpAndAudit() throws Exception {
        var created=mvc.perform(post("/api/admin/trading-restrictions").header("Authorization","Bearer "+token(operator))
            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("targetUserId",customer.getId(),"expectedVersion",customer.getVersion(),"restricted",true,"reason","보안 검토"))))
            .andExpect(status().isOk()).andReturn();
        var r=json.readTree(created.getResponse().getContentAsString());
        mvc.perform(post("/api/admin/requests/"+r.path("id").asText()+"/approve").header("Authorization","Bearer "+token(approver))
            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("payloadHash",r.path("payloadHash").asText(),"password","security-test-password"))))
            .andExpect(status().isOk());
        assertTrue(users.findById(customer.getId()).orElseThrow().isTradingRestricted());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='RESTRICTION_APPROVE' AND target=?",Long.class,"request:"+r.path("id").asText()));
    }
    @Test void missingApprovalFieldsAndUnboundedPagesAreRejected() throws Exception {
        mvc.perform(post("/api/admin/trading-restrictions").header("Authorization","Bearer "+token(operator))
            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("targetUserId",customer.getId(),"reason","값 누락"))))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/audit?page=-1").header("Authorization","Bearer "+token(auditor))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/requests?page=10001").header("Authorization","Bearer "+token(auditor))).andExpect(status().isBadRequest());
    }
    @Test void differentAccountsOfSamePersonCannotApprove() {
        var r=request();approver.setOperatorPersonId(operator.getOperatorPersonId());users.saveAndFlush(approver);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->decide(r)).getStatusCode().value());
        assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());
    }
    @Test void changedPayloadAndWrongPasswordAreRejected() {
        var r=request();
        assertThrows(ResponseStatusException.class,()->approvals.decide(approver.getId(),r.id(),"0".repeat(64),"security-test-password",true));
        assertThrows(ResponseStatusException.class,()->approvals.decide(approver.getId(),r.id(),r.payloadHash(),"wrong",true));
        assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());
    }
    @Test void changedTargetVersionAndRevokedRequesterInvalidateApproval() {
        var r=request();customer.setUsername("변경");users.saveAndFlush(customer);
        assertThrows(ResponseStatusException.class,()->decide(r));
        var current=users.findById(customer.getId()).orElseThrow();
        var second=approvals.request(operator.getId(),customer.getId(),current.getVersion(),true,"재요청");
        operator.setEnabled(false);users.saveAndFlush(operator);
        assertThrows(ResponseStatusException.class,()->decide(second));
    }
    @Test void expiredApprovalCannotExecute() {
        UUID id=UUID.randomUUID();String digest="a".repeat(64);
        jdbc.update("INSERT INTO operator_requests(id,requester_id,requester_person,target_id,target_version,restricted,reason,payload_hash,expires_at) VALUES(?,?,?,?,?,true,'만료 검증',?,now()-interval '1 second')",
            id,operator.getId(),operator.getOperatorPersonId(),customer.getId(),customer.getVersion(),digest);
        assertThrows(ResponseStatusException.class,()->approvals.decide(approver.getId(),id,digest,"security-test-password",true));
        assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());
    }
    @Test void restrictionCanBeLiftedOnlyByAnotherApprovedRequest() {
        decide(request());var current=users.findById(customer.getId()).orElseThrow();
        var lift=approvals.request(operator.getId(),customer.getId(),current.getVersion(),false,"심사 완료");decide(lift);
        assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());
    }
    @Test void restrictionStillAllowsOwnCancellationButNeverAnotherCustomersOrder() throws Exception {
        fund();var result=orders.createOrder(customer.getId(),UUID.randomUUID(),order());decide(request());
        when(engine.cancelOrder(anyLong(),anyLong(),anyString())).thenReturn(true);
        String path="/api/cex/orders/"+result.response().getOrder().getId();
        mvc.perform(delete(path).header("Authorization","Bearer "+token(customer))).andExpect(status().isAccepted());
        var other=user(UserRole.CUSTOMER,null);
        mvc.perform(delete(path).header("Authorization","Bearer "+token(other))).andExpect(status().isBadRequest());
        verify(engine,times(1)).cancelOrder(Long.valueOf(result.response().getOrder().getId()),customer.getId(),"BTC/USDT");
    }
    @Test void loginPasswordGuessingIsBoundedAndDoesNotRevokeExistingSession() {
        String old=token(customer);
        for(int i=0;i<5;i++) assertThrows(dustin.cex.domains.auth.exception.AuthException.class,()->auth.signin(new SigninRequest(customer.getEmail(),"wrong")));
        assertThrows(dustin.cex.domains.auth.exception.AuthException.class,()->auth.signin(new SigninRequest(customer.getEmail(),"security-test-password")));
        var claims=jwt.verifyAccessToken(old);sessionService.authenticate(customer.getId(),UUID.fromString(claims.get("sid",String.class)));
    }
    @Test void approvalPasswordGuessingIsBoundedWithoutApplyingEffect() {
        var r=request();for(int i=0;i<5;i++) assertThrows(ResponseStatusException.class,()->approvals.decide(approver.getId(),r.id(),r.payloadHash(),"wrong",true));
        assertEquals(429,assertThrows(ResponseStatusException.class,()->decide(r)).getStatusCode().value());
        assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());
    }
    @Test void completedRequestCannotExecuteAgain() {
        var r=request();decide(r);assertThrows(ResponseStatusException.class,()->decide(r));
    }
    @Test void concurrentApprovalsExecuteExactlyOnce() throws Exception {
        var r=request();try(var pool=Executors.newFixedThreadPool(2)) {
            var work=(Callable<Boolean>)()->{try {decide(r);return true;} catch(ResponseStatusException denied){return false;}};
            var a=pool.submit(work);var b=pool.submit(work);assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='RESTRICTION_APPROVE' AND outcome='SUCCESS' AND target=?",Long.class,"request:"+r.id()));
    }
    @Test void rejectionDoesNotApplyRestrictionAndCannotBeReused() {
        var r=request();approvals.decide(approver.getId(),r.id(),r.payloadHash(),"security-test-password",false);
        assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());assertThrows(ResponseStatusException.class,()->decide(r));
    }
    @Test void mandatoryAuditFailureRollsBackApprovalAndCustomerState() {
        var r=request();doThrow(new IllegalStateException("감사 장애 주입")).when(audit).record(eq(approver.getId()),eq("RESTRICTION_APPROVE"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->decide(r));
        assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM operator_requests WHERE id=?",String.class,r.id()));
    }
    @Test void auditAndApprovalPayloadCannotBeMutated() {
        var r=request();
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE operator_requests SET reason='tamper' WHERE id=?",r.id()));
        assertThrows(RuntimeException.class,()->jdbc.update("DELETE FROM operator_requests WHERE id=?",r.id()));
        assertThrows(RuntimeException.class,()->jdbc.execute("UPDATE security_audit SET detail='tamper'"));
        assertThrows(RuntimeException.class,()->jdbc.execute("TRUNCATE security_audit"));
    }
    @Test void restrictionBlocksNewReservationButKeepsOriginalReplay() {
        fund();var key=UUID.randomUUID();var first=orders.createOrder(customer.getId(),key,order());
        decide(request());
        assertThrows(ResponseStatusException.class,()->orders.createOrder(customer.getId(),UUID.randomUUID(),order()));
        assertEquals(first.response().getOrder().getId(),orders.createOrder(customer.getId(),key,order()).response().getOrder().getId());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id=?",Long.class,customer.getId()));
        assertEquals(0,new BigDecimal("900").compareTo(jdbc.queryForObject("SELECT available FROM user_balances WHERE user_id=?",BigDecimal.class,customer.getId())));
    }
    @Test void auditFailureRollsBackOrderReservationLedgerAndDispatch() {
        fund();doThrow(new IllegalStateException("감사 장애 주입")).when(audit).record(eq(customer.getId()),eq("ORDER_RESERVED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->orders.createOrder(customer.getId(),UUID.randomUUID(),order()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id=?",Long.class,customer.getId()));
        assertEquals(0,new BigDecimal("1000").compareTo(jdbc.queryForObject("SELECT available FROM user_balances WHERE user_id=?",BigDecimal.class,customer.getId())));
        verifyNoInteractions(engine);
    }
    @Test void auditReadsAreBoundedAndContainNoCredentials() throws Exception {
        auth.signin(new SigninRequest(customer.getEmail(),"security-test-password"));
        var read=mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+token(auditor))).andExpect(status().isOk()).andReturn();
        String content=read.getResponse().getContentAsString();
        assertFalse(content.contains("security-test-password"));assertFalse(content.contains(hash));assertFalse(content.contains("accessToken"));
        assertTrue(json.readTree(content).size()<=50);
    }
    void fund() {
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO user_balances(user_id,mint_address,available,locked,created_at,updated_at) VALUES(?,'USDT',1000,0,now(),now())",customer.getId());
            ledger.record("fund:"+customer.getId(),"TEST",List.of(LedgerService.p(customer.getId(),"AVAILABLE","USDT",new BigDecimal("1000")),LedgerService.p(0,"OPENING","USDT",new BigDecimal("-1000"))));
        });
    }
    CreateOrderRequest order() {return CreateOrderRequest.builder().orderType("buy").orderSide("limit").baseMint("BTC").quoteMint("USDT").price(new BigDecimal("100")).amount(BigDecimal.ONE).build();}
}
