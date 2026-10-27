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

/** 실제 공개키 서명으로 세 사람을 준비하고 역할 승인·서명 바인딩과 실패 원자성을 검증한다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorRoleTest {
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
    final Map<Long,Long> counters=new HashMap<>();
    final String enrollment="isolated-enrollment-256-bit-test-secret-at-least-43-characters";
    @BeforeAll void password() {
        hash=passwords.hash("security-test-password");
        // 전체 복구 시나리오도 작은 Long 캐시를 벗어난 실제 DB 사용자로 검증한다.
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('users','id'),1000,false)",Long.class);
    }
    @BeforeEach void setup() throws Exception {maker=account(UserRole.OPERATOR);checker=account(UserRole.APPROVER);owner=account(UserRole.AUDITOR);
        candidate=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@example.test").passwordHash(hash).build());
        counters.clear();}
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
    OperatorRoleService.Request request() {return request(candidate,UUID.randomUUID(),UserRole.OPERATOR);}
    OperatorRoleService.Request request(User target,UUID person,UserRole role) {
        return service.request(maker.user().getId(),maker.sid(),target.getId(),target.getVersion(),person,role,
            Instant.now().plusSeconds(86400),"직무 배정 | 검토","IDENTITY_100","NOTICE_100");
    }
    OperatorMfaService.AuthorizationProof proof(Account actor,OperatorRoleService.Request r,boolean approve) throws Exception {
        var options=service.options(actor.user().getId(),actor.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        long count=counters.merge(actor.user().getId(),2L,(a,b)->a+1);
        return new OperatorMfaService.AuthorizationProof(options.challengeId(),actor.key().assertion(options,"http://localhost:3003",true,count,null));
    }
    OperatorRoleService.Decision decide(Account actor,OperatorRoleService.Request r,boolean approve,OperatorMfaService.AuthorizationProof proof) {
        return service.decide(actor.user().getId(),actor.sid(),r.payload().id(),r.payloadHash(),approve,proof);
    }
    User candidate() {return users.findById(candidate.getId()).orElseThrow();}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=UserRole.class,names={"OPERATOR","APPROVER","AUDITOR"})
    void grantsEachOperatorRoleOnlyAfterIndependentSignature(UserRole role) throws Exception {
        var r=request(candidate,UUID.randomUUID(),role);assertEquals(UserRole.CUSTOMER,candidate().getRole());
        var result=decide(checker,r,true,proof(checker,r,true));
        assertEquals("APPROVED",result.request().status());assertEquals(role,candidate().getRole());
        assertEquals(r.payload().targetPerson(),candidate().getOperatorPersonId());
        assertEquals(r.payload().accessUntil(),candidate().getOperatorAccessExpiresAt());
        assertEquals(43,result.enrollmentToken().length());
    }
    @Test void grantRequiresNewPasswordSigninRegistrationAndSignature() throws Exception {
        var old=auth.signin(new SigninRequest(candidate.getEmail(),"security-test-password"));
        var r=request();var decision=decide(checker,r,true,proof(checker,r,true));
        assertThrows(IllegalArgumentException.class,()->sessions.authenticate(candidate.getId(),sid(old.getAccessToken())));
        var login=auth.signin(new SigninRequest(candidate.getEmail(),"security-test-password"));assertTrue(login.isMfaRequired());
        mvc.perform(get("/api/admin/requests").header("Authorization","Bearer "+login.getAccessToken())).andExpect(status().isForbidden());
        UUID pending=sid(login.getAccessToken());var key=new SoftwareAuthenticator();
        var options=mfa.registrationOptions(candidate.getId(),pending,decision.enrollmentToken());
        mfa.register(candidate.getId(),pending,options.challengeId(),key.registration(options,"http://localhost:3003",true));
        var verified=authenticated(candidate(),key,pending,1);
        mvc.perform(get("/api/admin/requests").header("Authorization","Bearer "+verified.verified().accessToken())).andExpect(status().isOk());
    }
    @Test void changedRoleKeepsKeysButRecallsAllSessionsAndPendingOptions() throws Exception {
        var loginOptions=mfa.authenticationOptions(owner.user().getId(),owner.sid());
        var r=request(current(owner),owner.user().getOperatorPersonId(),UserRole.OPERATOR);
        var result=decide(checker,r,true,proof(checker,r,true));assertNull(result.enrollmentToken());
        assertEquals(UserRole.OPERATOR,current(owner).getRole());
        assertThrows(IllegalArgumentException.class,()->sessions.authenticate(owner.user().getId(),owner.sid()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,owner.key().id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operator_mfa_challenges WHERE id=?",Long.class,loginOptions.challengeId()));
        var login=auth.signin(new SigninRequest(owner.user().getEmail(),"security-test-password"));
        assertNotNull(authenticated(current(owner),owner.key(),sid(login.getAccessToken()),2));
    }
    @Test void secretIsHashedNeverLoggedOrReadBackAndDecisionCannotReplay() throws Exception {
        var r=request();var p=proof(checker,r,true);var result=decide(checker,r,true,p);String secret=result.enrollmentToken();
        assertEquals(OperatorMfaService.sha256Hex(secret),jdbc.queryForObject("SELECT token_hash FROM operator_enrollment WHERE user_id=?",String.class,candidate.getId()));
        assertFalse(result.toString().contains(secret));assertFalse(p.toString().contains("signature"));
        assertFalse(json.writeValueAsString(service.page(0)).contains(secret));
        assertFalse(jdbc.queryForList("SELECT detail FROM security_audit").toString().contains(secret));
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p));
    }
    @Test void rejectionNeedsItsOwnSignatureAndDoesNotChangeTarget() throws Exception {
        var r=request();var result=decide(checker,r,false,proof(checker,r,false));
        assertEquals("REJECTED",result.request().status());assertNull(result.enrollmentToken());
        assertEquals(UserRole.CUSTOMER,candidate().getRole());
        assertThrows(ResponseStatusException.class,()->proof(checker,r,true));
    }
    @Test void recentMfaAndPasswordAloneCannotApprove() {
        var r=request();assertThrows(ResponseStatusException.class,()->decide(checker,r,true,null));
        assertEquals(UserRole.CUSTOMER,candidate().getRole());
    }
    @Test void ordinaryLoginAssertionCannotAuthorizeRole() throws Exception {
        var r=request();var options=mfa.authenticationOptions(checker.user().getId(),checker.sid());
        var p=new OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,2,null));
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p));
    }
    @Test void requestHashAndIdAreBoundToProof() throws Exception {
        var first=request();var p=proof(checker,first,true);var second=request();
        assertThrows(ResponseStatusException.class,()->decide(checker,second,true,p));
        assertThrows(ResponseStatusException.class,()->service.decide(checker.user().getId(),checker.sid(),first.payload().id(),"0".repeat(64),true,p));
        assertEquals("APPROVED",decide(checker,first,true,p).request().status());
    }
    @Test void approveAndRejectProofsAreNotInterchangeable() throws Exception {
        var r=request();var p=proof(checker,r,true);
        assertThrows(ResponseStatusException.class,()->decide(checker,r,false,p));
        var rejected=proof(checker,r,false);
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,rejected));
        assertEquals("REJECTED",decide(checker,r,false,rejected).request().status());
    }
    @Test void proofCannotMoveToAnotherApprover() throws Exception {
        var r=request();var p=proof(checker,r,true);var other=account(UserRole.APPROVER);
        assertThrows(ResponseStatusException.class,()->decide(other,r,true,p));
    }
    @Test void proofCannotMoveToRefreshedSession() throws Exception {
        var r=request();var p=proof(checker,r,true);var refresh=auth.refreshAccessToken(checker.verified().refreshToken());
        assertThrows(ResponseStatusException.class,()->service.decide(checker.user().getId(),sid(refresh.getAccessToken()),r.payload().id(),r.payloadHash(),true,p));
    }
    @Test void expiredChallengeCannotExecute() throws Exception {
        var r=request();var p=proof(checker,r,true);
        jdbc.update("UPDATE operator_authorization_challenges SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",p.challengeId());
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p));assertEquals(UserRole.CUSTOMER,candidate().getRole());
    }
    @Test void newOptionsInvalidatePreviousChallengeAndDoNotExtendSessionMfa() throws Exception {
        var before=tokens.findBySessionId(checker.sid()).orElseThrow();var r=request();var old=proof(checker,r,true);var latest=proof(checker,r,true);
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,old));decide(checker,r,true,latest);
        var after=tokens.findBySessionId(checker.sid()).orElseThrow();assertEquals(before.getMfaVerifiedAt(),after.getMfaVerifiedAt());assertEquals(before.getExpiresAt(),after.getExpiresAt());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"ORIGIN","UV","SIGNATURE","OWNER","COUNTER","CLIENT_CHALLENGE"})
    void actualSignatureValidationRejectsTampering(String mutation) throws Exception {
        var r=request();var options=service.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true).authentication();
        var key=mutation.equals("OWNER")?owner.key():checker.key();
        var node=key.assertion(options,mutation.equals("ORIGIN")?"https://evil.example":"http://localhost:3003",!mutation.equals("UV"),mutation.equals("COUNTER")?1:2,null);
        var response=(tools.jackson.databind.node.ObjectNode)node.path("response");
        if(mutation.equals("SIGNATURE")) response.put("signature",SoftwareAuthenticator.encoded(new byte[64]));
        if(mutation.equals("CLIENT_CHALLENGE")) response.put("clientDataJSON",SoftwareAuthenticator.encoded(json.writeValueAsBytes(Map.of("type","webauthn.get","challenge","AAAA","origin","http://localhost:3003"))));
        var p=new OperatorMfaService.AuthorizationProof(options.challengeId(),node);
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p));assertEquals(UserRole.CUSTOMER,candidate().getRole());
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));
    }
    @Test void fiveInvalidProofsLimitFurtherOptionsAndDecision() throws Exception {
        var r=request();var p=proof(checker,r,true);
        var wrong=new OperatorMfaService.AuthorizationProof(UUID.randomUUID(),p.credential());
        for(int i=0;i<5;i++) assertThrows(ResponseStatusException.class,()->decide(checker,r,true,wrong));
        assertEquals(429,assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p)).getStatusCode().value());
        assertEquals(429,assertThrows(ResponseStatusException.class,()->proof(checker,r,true)).getStatusCode().value());
        assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='MFA' AND detail='VERIFICATION_DENIED'",Long.class,checker.user().getId()));
    }
    @Test void requesterCheckerAndTargetMustBeDifferentActualPeople() {
        assertThrows(ResponseStatusException.class,()->request(current(maker),maker.user().getOperatorPersonId(),UserRole.AUDITOR));
        assertThrows(ResponseStatusException.class,()->request(candidate,maker.user().getOperatorPersonId(),UserRole.OPERATOR));
        var r=request(candidate,checker.user().getOperatorPersonId(),UserRole.OPERATOR);
        assertThrows(ResponseStatusException.class,()->proof(checker,r,true));
        var checkerUser=current(checker);checkerUser.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(checkerUser);
        assertThrows(ResponseStatusException.class,()->proof(checker,request(),true));
    }
    @Test void targetVersionChangeAfterOptionsBlocksEffect() throws Exception {
        var r=request();var p=proof(checker,r,true);var changed=candidate();changed.setUsername("다른 변경");users.saveAndFlush(changed);
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p));assertEquals(UserRole.CUSTOMER,candidate().getRole());
    }
    @Test void changedRequesterIdentityOrRevokedRightsBlockPreviouslySignedApproval() throws Exception {
        var r=request();var p=proof(checker,r,true);var changed=current(maker);changed.setEnabled(false);users.saveAndFlush(changed);
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p));
        changed=current(maker);changed.setEnabled(true);changed.setOperatorPersonId(UUID.randomUUID());users.saveAndFlush(changed);
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p));
    }
    @Test void disabledOrExpiredApproverCannotExecuteSignedRequest() throws Exception {
        var r=request();var p=proof(checker,r,true);var changed=current(checker);changed.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));users.saveAndFlush(changed);
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p));
    }
    @Test void staleAndRevokedSessionCannotBypassWithDirectServiceCalls() {
        var row=tokens.findBySessionId(maker.sid()).orElseThrow();row.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(row);
        assertThrows(ResponseStatusException.class,this::request);
        row=tokens.findBySessionId(maker.sid()).orElseThrow();row.setMfaVerifiedAt(LocalDateTime.now());row.setRevoked(true);tokens.saveAndFlush(row);
        assertThrows(ResponseStatusException.class,this::request);
    }
    @Test void disabledRecoveryLockedAndSameRoleTargetsAreDenied() {
        assertThrows(ResponseStatusException.class,()->request(current(owner),owner.user().getOperatorPersonId(),UserRole.AUDITOR));
        var target=current(owner);target.setOperatorRecoveryRequestId(UUID.randomUUID());users.saveAndFlush(target);
        assertThrows(ResponseStatusException.class,()->request(current(owner),owner.user().getOperatorPersonId(),UserRole.OPERATOR));
        var disabled=candidate();disabled.setEnabled(false);users.saveAndFlush(disabled);assertThrows(ResponseStatusException.class,this::request);
    }
    @Test void existingPersonCannotBeReassignedByRoleChange() {
        assertThrows(ResponseStatusException.class,()->request(current(owner),UUID.randomUUID(),UserRole.OPERATOR));
    }
    @Test void deadlineRoleAndEvidenceAreStrictlyValidated() {
        for(Instant until:List.of(Instant.now().minusSeconds(1),Instant.now().plusSeconds(31*86400)))
            assertThrows(ResponseStatusException.class,()->service.request(maker.user().getId(),maker.sid(),candidate.getId(),candidate.getVersion(),UUID.randomUUID(),UserRole.OPERATOR,until,"사유","IDENTITY_100","NOTICE_100"));
        assertThrows(ResponseStatusException.class,()->request(candidate,UUID.randomUUID(),UserRole.CUSTOMER));
        assertThrows(ResponseStatusException.class,()->service.request(maker.user().getId(),maker.sid(),candidate.getId(),candidate.getVersion(),UUID.randomUUID(),UserRole.OPERATOR,Instant.now().plusSeconds(1000),"사유","신분증 원문","NOTICE_100"));
    }
    @Test void positiveOrLockedBalanceCannotBecomeOperator() {
        jdbc.update("INSERT INTO user_balances(user_id,mint_address,available,locked,created_at,updated_at) VALUES(?,'USDT',1,0,now(),now())",candidate.getId());
        assertThrows(ResponseStatusException.class,this::request);
        jdbc.update("UPDATE user_balances SET available=0,locked=1 WHERE user_id=?",candidate.getId());assertThrows(ResponseStatusException.class,this::request);
    }
    @Test void financialStateCreatedAfterRequestPreventsGrant() throws Exception {
        var r=request();var p=proof(checker,r,true);
        jdbc.update("INSERT INTO user_balances(user_id,mint_address,available,locked,created_at,updated_at) VALUES(?,'BTC',1,0,now(),now())",candidate.getId());
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p));assertEquals(UserRole.CUSTOMER,candidate().getRole());
    }
    @Test void restrictedOrPreviouslyUsedOperatorCandidateCannotBeGranted() {
        var target=candidate();target.setTradingRestricted(true);users.saveAndFlush(target);assertThrows(ResponseStatusException.class,this::request);
        target=candidate();target.setTradingRestricted(false);target.setOperatorPersonId(UUID.randomUUID());users.saveAndFlush(target);assertThrows(ResponseStatusException.class,this::request);
    }
    @Test void concurrentApprovalHasOneWinnerAndOneEnrollment() throws Exception {
        var r=request();var p=proof(checker,r,true);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<Boolean> work=()->{gate.await();try{decide(checker,r,true,p);return true;}catch(ResponseStatusException e){return false;}};
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='ROLE_DECIDE' AND target=? AND outcome='SUCCESS'",Long.class,"request:"+r.payload().id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM operator_enrollment WHERE user_id=?",Long.class,candidate.getId()));
    }
    @Test void competingRequestCannotOverwriteNewRole() throws Exception {
        UUID person=UUID.randomUUID();var first=request(candidate,person,UserRole.OPERATOR);var second=request(candidate,person,UserRole.APPROVER);
        decide(checker,first,true,proof(checker,first,true));assertThrows(ResponseStatusException.class,()->proof(checker,second,true));
        assertEquals(UserRole.OPERATOR,candidate().getRole());
    }
    @Test void auditFailureRollsBackRoleSessionEnrollmentCounterAndProofConsumption() throws Exception {
        var old=auth.signin(new SigninRequest(candidate.getEmail(),"security-test-password"));var r=request();var p=proof(checker,r,true);
        doThrow(new IllegalStateException("역할 감사 장애 주입")).when(audit).record(eq(checker.user().getId()),eq("ROLE_DECIDE"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->decide(checker,r,true,p));assertEquals(UserRole.CUSTOMER,candidate().getRole());
        assertDoesNotThrow(()->sessions.authenticate(candidate.getId(),sid(old.getAccessToken())));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operator_enrollment WHERE user_id=?",Long.class,candidate.getId()));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));
        assertEquals(1,jdbc.queryForObject("SELECT signature_count FROM user_credentials WHERE credential_id=?",Long.class,checker.key().id()));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM operator_role_requests WHERE id=?",String.class,r.payload().id()));
        reset(audit);assertEquals("APPROVED",decide(checker,r,true,p).request().status());
    }
    @Test void requestAuditFailureLeavesNoPendingRequest() {
        long before=jdbc.queryForObject("SELECT count(*) FROM operator_role_requests",Long.class);
        doThrow(new IllegalStateException("요청 감사 장애 주입")).when(audit).record(eq(maker.user().getId()),eq("ROLE_REQUEST"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,this::request);
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM operator_role_requests",Long.class));
    }
    @Test void immutableRequestCannotBeEditedDeletedTruncatedOrReopened() throws Exception {
        var r=request();
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE operator_role_requests SET requested_role='APPROVER' WHERE id=?",r.payload().id()));
        assertThrows(RuntimeException.class,()->jdbc.update("DELETE FROM operator_role_requests WHERE id=?",r.payload().id()));
        assertThrows(RuntimeException.class,()->jdbc.execute("TRUNCATE operator_role_requests"));
        decide(checker,r,false,proof(checker,r,false));
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE operator_role_requests SET status='PENDING' WHERE id=?",r.payload().id()));
    }
    @Test void storedPayloadHashMatchesReturnedMicrosecondPrecisionJson() throws Exception {
        var r=request();assertEquals(OperatorMfaService.sha256Hex(json.writeValueAsString(r.payload())),r.payloadHash());
        var confirmation=service.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true);
        assertEquals(r,confirmation.request());assertTrue(confirmation.approve());
    }
    @Test void expiredRequestCannotIssueOptionsOrExecute() {
        var r=request();UUID expired=UUID.randomUUID();
        // 만료된 불변 요청은 새 fixture로 삽입한다. 운영 트리거를 끄지 않는다.
        jdbc.update("""
            INSERT INTO operator_role_requests SELECT ?,requester_id,requester_person,requester_version,target_id,target_person,target_version,
             previous_role,requested_role,access_until,reason,identity_evidence,notification_evidence,payload_hash,
             clock_timestamp()-interval '1 second',status,approver_id,created_at FROM operator_role_requests WHERE id=?
            """,expired,r.payload().id());
        assertThrows(ResponseStatusException.class,()->service.options(checker.user().getId(),checker.sid(),expired,r.payloadHash(),true));
        assertThrows(ResponseStatusException.class,()->service.decide(checker.user().getId(),checker.sid(),expired,r.payloadHash(),true,null));
    }
    @Test void actualHttpRoleFlowRejectsUnsignedAndUsesNoStore() throws Exception {
        var body=Map.of("targetId",candidate.getId(),"expectedVersion",candidate.getVersion(),"personId",UUID.randomUUID(),"role","AUDITOR",
            "accessUntil",Instant.now().plusSeconds(86400),"reason","직무 배정","identityEvidence","IDENTITY_100","notificationEvidence","NOTICE_100");
        var response=mvc.perform(post("/api/admin/operator-security/role-requests").header("Authorization","Bearer "+maker.verified().accessToken())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn();
        var r=json.treeToValue(json.readTree(response.getResponse().getContentAsString()),OperatorRoleService.Request.class);
        String base="/api/admin/operator-security/role-requests/"+r.payload().id()+"/approve";
        mvc.perform(post(base).header("Authorization","Bearer "+checker.verified().accessToken()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("payloadHash",r.payloadHash(),"password","security-test-password")))).andExpect(status().isBadRequest());
        var options=mvc.perform(post(base+"/options").header("Authorization","Bearer "+checker.verified().accessToken()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("payloadHash",r.payloadHash())))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        var confirmation=json.treeToValue(json.readTree(options.getResponse().getContentAsString()),OperatorRoleService.Confirmation.class);
        var p=new OperatorMfaService.AuthorizationProof(confirmation.authentication().challengeId(),checker.key().assertion(confirmation.authentication(),"http://localhost:3003",true,2,null));
        mvc.perform(post(base).header("Authorization","Bearer "+checker.verified().accessToken()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("payloadHash",r.payloadHash(),"proof",p)))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.enrollmentToken").isNotEmpty());
    }
    @Test void httpRolesDenyCustomerMakerAndAuditorApprovalOptions() throws Exception {
        var r=request();String path="/api/admin/operator-security/role-requests/"+r.payload().id()+"/approve/options";
        for(Account actor:List.of(maker,owner)) mvc.perform(post(path).header("Authorization","Bearer "+actor.verified().accessToken()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("payloadHash",r.payloadHash())))).andExpect(status().isForbidden());
        var login=auth.signin(new SigninRequest(candidate.getEmail(),"security-test-password"));
        mvc.perform(get("/api/admin/operator-security/role-targets/"+candidate.getId()).header("Authorization","Bearer "+login.getAccessToken())).andExpect(status().isForbidden());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/operator-security/role-targets/"+candidate.getId()).header("Authorization","Bearer "+owner.verified().accessToken())).andExpect(status().isOk());
        assertThrows(ResponseStatusException.class,()->service.page(-1));assertThrows(ResponseStatusException.class,()->service.page(10001));
    }
    @Test void requesterRoleRoundTripStillInvalidatesOlderRequest() throws Exception {
        var r=request();var p=proof(checker,r,true);var user=current(maker);
        user.setRole(UserRole.AUDITOR);users.saveAndFlush(user);user=users.findById(user.getId()).orElseThrow();
        user.setRole(UserRole.OPERATOR);users.saveAndFlush(user);
        assertThrows(ResponseStatusException.class,()->decide(checker,r,true,p));
    }
    @Autowired dustin.cex.domains.order.repository.OrderRepository orders;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired org.springframework.transaction.PlatformTransactionManager manager;
    @Autowired TradingAccessPolicy trading;
    @Test void cancelledOrderHistoryCannotBeErasedByZeroBalanceRoleConversion() {
        orders.saveAndFlush(dustin.cex.domains.order.model.entity.Order.builder().userId(candidate.getId())
            .orderType("buy").orderSide("limit").baseMint("BTC").quoteMint("USDT")
            .price(java.math.BigDecimal.ONE).amount(java.math.BigDecimal.ONE).status("cancelled").build());
        assertThrows(ResponseStatusException.class,this::request);
    }
    @Test void zeroCurrentBalanceDoesNotHideHistoricalLedgerEntries() {
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        // 검증용 원장 시작점도 정상 추가만 사용한다. 기존 원장 이력을 수정·삭제하지 않는다.
        jdbc.update("INSERT INTO ledger_transactions(operation_key,kind) VALUES('opening','OPENING') ON CONFLICT DO NOTHING");
        tx.executeWithoutResult(status->ledger.record("role-history:"+candidate.getId(),"TEST",List.of(
            dustin.cex.domains.ledger.LedgerService.p(candidate.getId(),"AVAILABLE","USDT",java.math.BigDecimal.ONE),
            dustin.cex.domains.ledger.LedgerService.p(0,"OPENING","USDT",java.math.BigDecimal.ONE.negate()))));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances WHERE user_id=?",Long.class,candidate.getId()));
        assertThrows(ResponseStatusException.class,this::request);
    }
    @Test void oldCustomerAccessCannotCreateOrderAfterApprovedRoleChange() throws Exception {
        var r=request();decide(checker,r,true,proof(checker,r,true));
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThrows(ResponseStatusException.class,()->tx.executeWithoutResult(status->trading.requireNewOrder(candidate.getId(),"BTC","USDT")));
    }
    @Test void authorizationHelpersCannotRunOutsideBusinessTransaction() {
        assertThrows(IllegalStateException.class,()->mfa.approvalOptions(checker.user().getId(),checker.sid(),OperatorMfaService.ApprovalKind.ROLE,UUID.randomUUID(),"a".repeat(64),true));
        assertThrows(IllegalStateException.class,()->mfa.authorizeApproval(checker.user().getId(),checker.sid(),OperatorMfaService.ApprovalKind.ROLE,UUID.randomUUID(),"a".repeat(64),true,null));
    }

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Test void realHttpServerBindsApprovalAndRejectsOldCustomerSession() throws Exception {
        var login=auth.signin(new SigninRequest(candidate.getEmail(),"security-test-password"));
        var r=request();String base="/api/admin/operator-security/role-requests/"+r.payload().id()+"/approve";
        var options=http(base+"/options",checker.verified().accessToken(),Map.of("payloadHash",r.payloadHash()));assertEquals(200,options.statusCode());
        assertEquals("no-store",options.headers().firstValue("Cache-Control").orElseThrow());
        var confirmation=json.treeToValue(json.readTree(options.body()),OperatorRoleService.Confirmation.class);
        assertEquals(r,confirmation.request());
        var p=new OperatorMfaService.AuthorizationProof(confirmation.authentication().challengeId(),checker.key().assertion(confirmation.authentication(),"http://localhost:3003",true,2,null));
        var result=http(base,checker.verified().accessToken(),Map.of("payloadHash",r.payloadHash(),"proof",p));
        assertEquals(200,result.statusCode());assertFalse(json.readTree(result.body()).path("enrollmentToken").asText().isEmpty());
        var client=java.net.http.HttpClient.newHttpClient();
        var old=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/auth/me"))
            .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+login.getAccessToken()).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(401,old.statusCode());
        assertEquals(409,http(base,checker.verified().accessToken(),Map.of("payloadHash",r.payloadHash(),"proof",p)).statusCode());
    }
    java.net.http.HttpResponse<String> http(String path,String token,Object body) throws Exception {
        return java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+path))
            .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
    }


    // 역할 업무의 이력 조건만 검사하는 합성 자료다. 신원 확인이나 CDD 승인의 증거로 사용하지 않는다.
    void declaredCustomerHistory() {
        jdbc.update("INSERT INTO cdd_declarations(id,user_id,request_id,ciphertext,content_mac,key_id) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID(),candidate.getId(),UUID.randomUUID(),new byte[]{0},"role-fixture","role-fixture");
    }
    @Test void customerDeclarationWithoutFinancialHistoryCannotBecomeOperator() {
        declaredCustomerHistory();assertEquals(409,assertThrows(ResponseStatusException.class,this::request).getStatusCode().value());
        assertEquals(UserRole.CUSTOMER,candidate().getRole());
    }
    @Test void customerIdentityAttemptWithoutFinancialHistoryCannotBecomeOperator() {
        jdbc.update("INSERT INTO kyc_attempts(id,user_id,request_id,application_id,environment,workflow_id,workflow_version,creation_state) VALUES(?,?,?,?,'live',?,1,'READY')",
            UUID.randomUUID(),candidate.getId(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        assertEquals(409,assertThrows(ResponseStatusException.class,this::request).getStatusCode().value());
        assertEquals(UserRole.CUSTOMER,candidate().getRole());
    }
    @Test void newCustomerDeclarationAfterRoleRequestPreventsApprovalWithoutVersionChange() throws Exception {
        var r=request();var authorization=proof(checker,r,true);long version=candidate().getVersion();declaredCustomerHistory();
        assertEquals(version,candidate().getVersion());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->decide(checker,r,true,authorization)).getStatusCode().value());
        assertEquals(UserRole.CUSTOMER,candidate().getRole());
    }
}
