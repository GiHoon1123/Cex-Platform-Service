package dustin.cex.domains.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.model.dto.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.*;

/** 실제 공개키 서명으로 세 사람을 준비하고 네 승인 업무의 서명 결합과 실패 원자성을 검증한다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BoundApprovalTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");
        r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;@Autowired RefreshTokenRepository tokens;@Autowired JwtService jwt;
    @Autowired PasswordService passwords;@Autowired AuthService auth;@Autowired OperatorMfaService mfa;
    @Autowired OperatorRoleService service;@Autowired OperatorDeviceService devices;@Autowired SessionService sessions;
    @MockitoSpyBean AuditService audit;
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    record Account(User user,SoftwareAuthenticator key,OperatorMfaService.Verified verified,UUID sid) {}
    Account maker,checker,owner;User candidate;String hash;
    final java.util.concurrent.atomic.AtomicLong counter=new java.util.concurrent.atomic.AtomicLong(1);
    final String enrollment="isolated-enrollment-256-bit-test-secret-at-least-43-characters";
    @BeforeAll void password() {
        hash=passwords.hash("security-test-password");
        // 전체 복구 시나리오도 작은 Long 캐시를 벗어난 실제 DB 사용자로 검증한다.
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('users','id'),1000,false)",Long.class);
    }
    @BeforeEach void setup() throws Exception {maker=account(UserRole.OPERATOR);checker=account(UserRole.APPROVER);owner=account(UserRole.AUDITOR);
        candidate=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@example.test").passwordHash(hash).build());
        counter.set(1);}
    Account account(UserRole role) throws Exception {
        var user=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@example.test").passwordHash(hash).role(role)
            .operatorPersonId(UUID.randomUUID()).operatorAccessExpiresAt(Instant.now().plusSeconds(86400)).build());
        jdbc.update("INSERT INTO operator_enrollment(user_id,token_hash,expires_at) VALUES(?,?,clock_timestamp()+interval '10 minutes')",user.getId(),OperatorMfaService.sha256Hex(enrollment));
        var login=auth.signin(new SigninRequest(user.getEmail(),"security-test-password"));UUID sid=sid(login.getAccessToken());var key=new SoftwareAuthenticator();
        var options=mfa.registrationOptions(user.getId(),sid,enrollment);mfa.register(user.getId(),sid,options.challengeId(),key.registration(options,"http://localhost:3003",true));
        return authenticated(user,key,sid,1);
    }
    Account authenticated(User user,SoftwareAuthenticator key,UUID sid,long count) throws Exception {
        var options=mfa.authenticationOptions(user.getId(),sid);
        var verified=mfa.authenticate(user.getId(),sid,options.challengeId(),key.assertion(options,"http://localhost:3003",true,count,null));
        return new Account(user,key,verified,sid(verified.accessToken()));
    }
    UUID sid(String access) {return UUID.fromString(jwt.verifyAccessToken(access).get("sid",String.class));}
    User current(Account a) {return users.findById(a.user().getId()).orElseThrow();}
    @Autowired OperatorLifecycleService lifecycle;
    @Autowired OperatorApprovalService restrictions;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    record Case(OperatorMfaService.ApprovalKind kind,UUID id,String hash,String path) {}
    Case create(OperatorMfaService.ApprovalKind kind) {
        return switch(kind) {
            case EDD_REVIEW, EDD_ADDRESS_REVIEW, EDD_FUNDS_REVIEW, EDD_WEALTH_REVIEW, STR_REVIEW -> throw new AssertionError("EDD 전용 통합 검증을 사용하세요");
            case ROLE -> {var r=service.request(maker.user().getId(),maker.sid(),candidate.getId(),candidate.getVersion(),UUID.randomUUID(),UserRole.OPERATOR,
                Instant.now().plusSeconds(86400),"역할 배정","IDENTITY_100","NOTICE_100");yield new Case(kind,r.payload().id(),r.payloadHash(),"/api/admin/operator-security/role-requests/");}
            case MFA_RECOVERY,ACCESS_REVIEW -> {var r=lifecycle.request(maker.user().getId(),maker.sid(),owner.user().getId(),current(owner).getVersion(),
                kind==OperatorMfaService.ApprovalKind.MFA_RECOVERY?OperatorLifecycleService.Kind.RECOVER_MFA:OperatorLifecycleService.Kind.REVIEW_ACCESS,
                kind==OperatorMfaService.ApprovalKind.MFA_RECOVERY?null:Instant.now().plusSeconds(7*86400),"운영 검토","IDENTITY_100","NOTICE_100");
                yield new Case(kind,r.id(),r.payloadHash(),"/api/admin/operator-security/requests/");}
            case TRADING_RESTRICTION -> {var r=restrictions.request(maker.user().getId(),maker.sid(),candidate.getId(),candidate.getVersion(),true,"위험 검토");
                yield new Case(kind,r.id(),r.payloadHash(),"/api/admin/requests/");}
        };
    }
    OperatorMfaService.Options options(Case c,boolean approve) {
        return switch(c.kind()) {
            case EDD_REVIEW, EDD_ADDRESS_REVIEW, EDD_FUNDS_REVIEW, EDD_WEALTH_REVIEW, STR_REVIEW -> throw new AssertionError("EDD 전용 통합 검증을 사용하세요");
            case ROLE -> service.options(checker.user().getId(),checker.sid(),c.id(),c.hash(),approve).authentication();
            case MFA_RECOVERY,ACCESS_REVIEW -> lifecycle.options(checker.user().getId(),checker.sid(),c.id(),c.hash(),approve).authentication();
            case TRADING_RESTRICTION -> restrictions.options(checker.user().getId(),checker.sid(),c.id(),c.hash(),approve).authentication();
        };
    }
    OperatorMfaService.AuthorizationProof signed(Case c,boolean approve) throws Exception {
        var o=options(c,approve);return new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    Object decide(Case c,boolean approve,OperatorMfaService.AuthorizationProof p) {return decide(c,checker.sid(),c.hash(),approve,p);}
    Object decide(Case c,UUID sid,String hash,boolean approve,OperatorMfaService.AuthorizationProof p) {
        return switch(c.kind()) {
            case EDD_REVIEW, EDD_ADDRESS_REVIEW, EDD_FUNDS_REVIEW, EDD_WEALTH_REVIEW, STR_REVIEW -> throw new AssertionError("EDD 전용 통합 검증을 사용하세요");
            case ROLE -> service.decide(checker.user().getId(),sid,c.id(),hash,approve,p);
            case MFA_RECOVERY,ACCESS_REVIEW -> lifecycle.decide(checker.user().getId(),sid,c.id(),hash,approve,p);
            case TRADING_RESTRICTION -> restrictions.decide(checker.user().getId(),sid,c.id(),hash,approve,p);
        };
    }
    String table(Case c) {return switch(c.kind()){case EDD_REVIEW, EDD_ADDRESS_REVIEW, EDD_FUNDS_REVIEW, EDD_WEALTH_REVIEW, STR_REVIEW->throw new AssertionError("EDD 전용 통합 검증을 사용하세요");case ROLE->"operator_role_requests";case MFA_RECOVERY,ACCESS_REVIEW->"operator_security_requests";case TRADING_RESTRICTION->"operator_requests";};}
    String action(Case c) {return switch(c.kind()){case EDD_REVIEW, EDD_ADDRESS_REVIEW, EDD_FUNDS_REVIEW, EDD_WEALTH_REVIEW, STR_REVIEW->throw new AssertionError("EDD 전용 통합 검증을 사용하세요");case ROLE->"ROLE_DECIDE";case MFA_RECOVERY,ACCESS_REVIEW->"OPERATOR_SECURITY_DECIDE";case TRADING_RESTRICTION->"RESTRICTION_APPROVE";};}
    String state(Case c) {return jdbc.queryForObject("SELECT status FROM "+table(c)+" WHERE id=?",String.class,c.id());}
    void unchanged(Case c) {
        assertEquals("PENDING",state(c));assertEquals(UserRole.CUSTOMER,users.findById(candidate.getId()).orElseThrow().getRole());
        assertFalse(users.findById(candidate.getId()).orElseThrow().isTradingRestricted());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,owner.key().id()));
        if(c.kind()==OperatorMfaService.ApprovalKind.MFA_RECOVERY) assertEquals(c.id(),current(owner).getOperatorRecoveryRequestId());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void eachWorkflowRequiresItsOwnSignature(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);assertThrows(ResponseStatusException.class,()->decide(c,true,null));unchanged(c);
        var result=decide(c,true,signed(c,true));assertNotNull(result);assertEquals("APPROVED",state(c));
        switch(kind) {
            case ROLE -> assertEquals(UserRole.OPERATOR,users.findById(candidate.getId()).orElseThrow().getRole());
            case TRADING_RESTRICTION -> assertTrue(users.findById(candidate.getId()).orElseThrow().isTradingRestricted());
            case MFA_RECOVERY -> {assertNull(current(owner).getOperatorRecoveryRequestId());assertNotNull(((OperatorLifecycleService.Decision)result).enrollmentToken());}
            case ACCESS_REVIEW -> assertTrue(current(owner).getOperatorAccessExpiresAt().isAfter(Instant.now().plusSeconds(6*86400)));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void rejectionHasIndependentSignatureAndNoBusinessEffect(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);var p=signed(c,true);assertThrows(ResponseStatusException.class,()->decide(c,false,p));unchanged(c);
        var rejected=signed(c,false);assertThrows(ResponseStatusException.class,()->decide(c,true,rejected));
        decide(c,false,rejected);assertEquals("REJECTED",state(c));
        if(kind==OperatorMfaService.ApprovalKind.MFA_RECOVERY) assertNotNull(current(owner).getOperatorRecoveryRequestId());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void hashExpiryAndSuccessfulReplayAreChecked(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);var p=signed(c,true);
        assertThrows(ResponseStatusException.class,()->decide(c,checker.sid(),"0".repeat(64),true,p));
        jdbc.update("UPDATE operator_authorization_challenges SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",p.challengeId());
        assertThrows(ResponseStatusException.class,()->decide(c,true,p));unchanged(c);
        var next=signed(c,true);decide(c,true,next);assertThrows(ResponseStatusException.class,()->decide(c,true,next));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void signatureCannotMoveToRefreshedSession(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);var p=signed(c,true);var refreshed=auth.refreshAccessToken(checker.verified().refreshToken());
        assertThrows(ResponseStatusException.class,()->decide(c,sid(refreshed.getAccessToken()),c.hash(),true,p));unchanged(c);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void loginSignatureAndMissingUserVerificationCannotExecute(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);var login=mfa.authenticationOptions(checker.user().getId(),checker.sid());
        var loginProof=new OperatorMfaService.AuthorizationProof(login.challengeId(),checker.key().assertion(login,"http://localhost:3003",true,2,null));
        assertThrows(ResponseStatusException.class,()->decide(c,true,loginProof));
        var o=options(c,true);var uvMissing=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",false,3,null));
        assertThrows(ResponseStatusException.class,()->decide(c,true,uvMissing));unchanged(c);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void requesterRoleRoundTripInvalidatesExistingProof(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);var p=signed(c,true);var makerUser=current(maker);makerUser.setRole(UserRole.AUDITOR);users.saveAndFlush(makerUser);
        makerUser=current(maker);makerUser.setRole(UserRole.OPERATOR);users.saveAndFlush(makerUser);
        assertThrows(ResponseStatusException.class,()->decide(c,true,p));unchanged(c);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void businessAuditFailureRestoresProofCounterAndBusinessState(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);var p=signed(c,true);var deadline=current(owner).getOperatorAccessExpiresAt();
        doThrow(new IllegalStateException("최종 감사 장애 주입")).when(audit).record(eq(checker.user().getId()),eq(action(c)),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->decide(c,true,p));unchanged(c);
        assertEquals(deadline,current(owner).getOperatorAccessExpiresAt());
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));
        assertEquals(1,jdbc.queryForObject("SELECT signature_count FROM user_credentials WHERE credential_id=?",Long.class,checker.key().id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='APPROVAL_AUTHORIZATION' AND outcome='SUCCESS' AND target=?",Long.class,"request:"+c.id()));
        reset(audit);decide(c,true,p);assertEquals("APPROVED",state(c));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void concurrentApprovalsHaveExactlyOneWinner(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);var p=signed(c,true);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<Boolean> work=()->{gate.await();try{decide(c,true,p);return true;}catch(ResponseStatusException e){return false;}};
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='APPROVAL_AUTHORIZATION' AND target=? AND outcome='SUCCESS'",Long.class,"request:"+c.id()));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void fiveInvalidProofsSharePersistentMfaLimit(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);var valid=signed(c,true);var wrong=new OperatorMfaService.AuthorizationProof(UUID.randomUUID(),valid.credential());
        for(int i=0;i<5;i++) assertThrows(ResponseStatusException.class,()->decide(c,true,wrong));
        assertEquals(429,assertThrows(ResponseStatusException.class,()->decide(c,true,valid)).getStatusCode().value());
        assertEquals(429,assertThrows(ResponseStatusException.class,()->options(c,true)).getStatusCode().value());unchanged(c);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void bindingHistoryCannotBeChangedOrDeleted(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);decide(c,true,signed(c,true));
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE operator_approval_bindings SET requester_version=0 WHERE kind=? AND request_id=?",kind.name(),c.id()));
        assertThrows(RuntimeException.class,()->jdbc.update("DELETE FROM operator_approval_bindings WHERE kind=? AND request_id=?",kind.name(),c.id()));
        assertThrows(RuntimeException.class,()->jdbc.execute("TRUNCATE operator_approval_bindings CASCADE"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM operator_approval_bindings WHERE kind=? AND request_id=?",Long.class,kind.name(),c.id()));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void realHttpRequiresProofReturnsServerContentAndNeverCaches(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);String path=c.path()+c.id()+"/approve";
        var unsigned=http(path,checker.verified().accessToken(),Map.of("payloadHash",c.hash(),"password","security-test-password"));assertEquals(400,unsigned.statusCode());
        var raw=http(path+"/options",checker.verified().accessToken(),Map.of("payloadHash",c.hash()));assertEquals(200,raw.statusCode());
        assertEquals("no-store",raw.headers().firstValue("Cache-Control").orElseThrow());
        var node=json.readTree(raw.body());assertEquals(c.hash(),node.path("request").path("payloadHash").asText());assertTrue(node.path("approve").asBoolean());
        var o=json.treeToValue(node.path("authentication"),OperatorMfaService.Options.class);
        var p=new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,2,null));
        var result=http(path,checker.verified().accessToken(),Map.of("payloadHash",c.hash(),"proof",p));assertEquals(200,result.statusCode());
        assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());assertEquals("APPROVED",state(c));
        assertEquals(409,http(path,checker.verified().accessToken(),Map.of("payloadHash",c.hash(),"proof",p)).statusCode());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void onlyApproverCanObtainWorkflowOptions(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);for(Account actor:List.of(maker,owner)) {
            var result=http(c.path()+c.id()+"/approve/options",actor.verified().accessToken(),Map.of("payloadHash",c.hash()));
            // 복구 요청으로 정지된 대상은 필터에서 401, 다른 운영 역할은 인가에서 403이다.
            assertEquals(kind==OperatorMfaService.ApprovalKind.MFA_RECOVERY && actor==owner?401:403,result.statusCode());
        }
    }
    @Test void identicalIdsAndHashesAcrossWorkflowsDoNotShareProof() throws Exception {
        var c=create(OperatorMfaService.ApprovalKind.ACCESS_REVIEW);var p=signed(c,true);
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.executeWithoutResult(status->mfa.bindApproval(OperatorMfaService.ApprovalKind.TRADING_RESTRICTION,c.id(),maker.user().getId(),current(maker).getVersion(),c.hash(),Instant.now().plusSeconds(600)));
        // 의도적으로 요청 ID·해시까지 충돌시킨 fixture다. 업무 구분만 달라도 서명은 통과하지 않아야 한다.
        assertThrows(ResponseStatusException.class,()->tx.executeWithoutResult(status->mfa.authorizeApproval(checker.user().getId(),checker.sid(),OperatorMfaService.ApprovalKind.TRADING_RESTRICTION,c.id(),c.hash(),true,p)));
        unchanged(c);decide(c,true,p);
    }
    @Test void oldPendingRequestWithoutBindingRemainsReadableButCannotExecute() {
        UUID id=UUID.randomUUID();String hash="a".repeat(64);
        jdbc.update("""
            INSERT INTO operator_requests(id,requester_id,requester_person,target_id,target_version,restricted,reason,payload_hash,expires_at)
            VALUES(?,?,?,?,?,true,'과거 요청',?,clock_timestamp()+interval '10 minutes')
            """,id,maker.user().getId(),maker.user().getOperatorPersonId(),candidate.getId(),candidate.getVersion(),hash);
        assertTrue(restrictions.page(0).stream().anyMatch(r->r.id().equals(id)));
        assertThrows(ResponseStatusException.class,()->restrictions.options(checker.user().getId(),checker.sid(),id,hash,true));
        assertThrows(ResponseStatusException.class,()->restrictions.decide(checker.user().getId(),checker.sid(),id,hash,true,null));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM operator_requests WHERE id=?",String.class,id));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void proofCannotExecuteAnotherRequestInSameWorkflow(OperatorMfaService.ApprovalKind kind) throws Exception {
        var first=create(kind);var p=signed(first,true);var second=create(kind);
        assertThrows(ResponseStatusException.class,()->decide(second,true,p));unchanged(second);
        decide(second,true,signed(second,true));assertEquals("APPROVED",state(second));assertEquals("PENDING",state(first));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void newOptionsInAnotherWorkflowInvalidateOldChallenge(OperatorMfaService.ApprovalKind kind) throws Exception {
        var first=create(kind);var old=signed(first,true);
        var second=create(kind==OperatorMfaService.ApprovalKind.ROLE?OperatorMfaService.ApprovalKind.TRADING_RESTRICTION:OperatorMfaService.ApprovalKind.ROLE);
        var next=signed(second,true);assertThrows(ResponseStatusException.class,()->decide(first,true,old));unchanged(first);
        decide(second,true,next);assertEquals("APPROVED",state(second));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=OperatorMfaService.ApprovalKind.class,names={"ROLE","MFA_RECOVERY","ACCESS_REVIEW","TRADING_RESTRICTION"})
    void independentApproverApprovalAndRejectionRaceHasOneFinalDecision(OperatorMfaService.ApprovalKind kind) throws Exception {
        var c=create(kind);var approve=signed(c,true);var other=account(UserRole.APPROVER);
        var options=switch(kind) {
            case EDD_REVIEW, EDD_ADDRESS_REVIEW, EDD_FUNDS_REVIEW, EDD_WEALTH_REVIEW, STR_REVIEW -> throw new AssertionError("EDD 전용 통합 검증을 사용하세요");
            case ROLE -> service.options(other.user().getId(),other.sid(),c.id(),c.hash(),false).authentication();
            case MFA_RECOVERY,ACCESS_REVIEW -> lifecycle.options(other.user().getId(),other.sid(),c.id(),c.hash(),false).authentication();
            case TRADING_RESTRICTION -> restrictions.options(other.user().getId(),other.sid(),c.id(),c.hash(),false).authentication();
        };
        var reject=new OperatorMfaService.AuthorizationProof(options.challengeId(),other.key().assertion(options,"http://localhost:3003",true,2,null));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var approval=pool.submit(()->{gate.await();try{decide(c,true,approve);return true;}catch(ResponseStatusException e){return false;}});
            var rejection=pool.submit(()->{gate.await();try{
                switch(kind) {
                    case ROLE -> service.decide(other.user().getId(),other.sid(),c.id(),c.hash(),false,reject);
                    case MFA_RECOVERY,ACCESS_REVIEW -> lifecycle.decide(other.user().getId(),other.sid(),c.id(),c.hash(),false,reject);
                    case TRADING_RESTRICTION -> restrictions.decide(other.user().getId(),other.sid(),c.id(),c.hash(),false,reject);
                }
                return true;
            }catch(ResponseStatusException e){return false;}});
            gate.countDown();boolean approved=approval.get(15,TimeUnit.SECONDS);boolean rejected=rejection.get(15,TimeUnit.SECONDS);
            assertNotEquals(approved,rejected);assertEquals(approved?"APPROVED":"REJECTED",state(c));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='APPROVAL_AUTHORIZATION' AND target=? AND outcome='SUCCESS'",Long.class,"request:"+c.id()));
    }
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    java.net.http.HttpResponse<String> http(String path,String token,Object body) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path))
            .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }
}
