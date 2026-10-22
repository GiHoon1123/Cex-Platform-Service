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

/** 실제 공개키 서명으로 세 사람을 준비하고 복구·회수의 상태 전이와 실패 원자성을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperatorLifecycleTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");
        r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;@Autowired RefreshTokenRepository tokens;@Autowired JwtService jwt;
    @Autowired PasswordService passwords;@Autowired AuthService auth;@Autowired OperatorMfaService mfa;
    @Autowired OperatorLifecycleService service;@Autowired OperatorDeviceService devices;@Autowired SessionService sessions;
    @MockitoSpyBean AuditService audit;
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    record Account(User user,SoftwareAuthenticator key,OperatorMfaService.Verified verified,UUID sid) {}
    Account maker,checker,owner;String hash;
    final String enrollment="isolated-enrollment-256-bit-test-secret-at-least-43-characters";
    @BeforeAll void password() {
        hash=passwords.hash("security-test-password");
        // 전체 복구 시나리오도 작은 Long 캐시를 벗어난 실제 DB 사용자로 검증한다.
        jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('users','id'),1000,false)",Long.class);
    }
    @BeforeEach void setup() throws Exception {maker=account(UserRole.OPERATOR);checker=account(UserRole.APPROVER);owner=account(UserRole.AUDITOR);}
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
    OperatorLifecycleService.Request recovery() {return service.request(maker.user().getId(),maker.sid(),owner.user().getId(),current(owner).getVersion(),OperatorLifecycleService.Kind.RECOVER_MFA,null,"분실 신고","IDENTITY_100","NOTICE_100");}
    OperatorLifecycleService.Request review() {return service.request(maker.user().getId(),maker.sid(),owner.user().getId(),current(owner).getVersion(),OperatorLifecycleService.Kind.REVIEW_ACCESS,Instant.now().plusSeconds(7*86400),"권한 재검토","REVIEW_100","NOTICE_100");}
    OperatorLifecycleService.Decision approve(OperatorLifecycleService.Request r) {return service.decide(checker.user().getId(),checker.sid(),r.id(),r.payloadHash(),true);}
    @Test void recoveryImmediatelyBlocksSessionsRefreshSigninAndOldChallenges() throws Exception {
        var challenge=mfa.authenticationOptions(owner.user().getId(),owner.sid());var request=recovery();
        assertEquals(request.id(),current(owner).getOperatorRecoveryRequestId());
        assertThrows(IllegalArgumentException.class,()->sessions.authenticate(owner.user().getId(),owner.sid()));
        assertThrows(RuntimeException.class,()->auth.refreshAccessToken(owner.verified().refreshToken()));
        assertThrows(RuntimeException.class,()->auth.signin(new SigninRequest(owner.user().getEmail(),"security-test-password")));
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(owner.user().getId(),owner.sid(),challenge.challengeId(),owner.key().assertion(challenge,"http://localhost:3003",true,2,null)));
    }
    @Test void approvedRecoveryRequiresPasswordNewRegistrationAndNewSignature() throws Exception {
        var request=recovery();var result=approve(request);assertNotNull(result.enrollmentToken());assertEquals(43,result.enrollmentToken().length());
        assertFalse(result.toString().contains(result.enrollmentToken()));assertNull(current(owner).getOperatorRecoveryRequestId());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,owner.key().id()));
        assertThrows(IllegalArgumentException.class,()->sessions.authenticate(owner.user().getId(),owner.sid()));
        var login=auth.signin(new SigninRequest(owner.user().getEmail(),"security-test-password"));UUID pending=sid(login.getAccessToken());
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+login.getAccessToken())).andExpect(status().isForbidden());
        var key=new SoftwareAuthenticator();var options=mfa.registrationOptions(owner.user().getId(),pending,result.enrollmentToken());
        mfa.register(owner.user().getId(),pending,options.challengeId(),key.registration(options,"http://localhost:3003",true));
        var verified=authenticated(owner.user(),key,pending,1);
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+verified.verified().accessToken())).andExpect(status().isOk());
        assertThrows(ResponseStatusException.class,()->approve(request));
    }
    @Test void approvalResponseContainsSecretOnlyOnceAndReadsNeverExposeIt() throws Exception {
        var request=recovery();var result=approve(request);String secret=result.enrollmentToken();
        String stored=jdbc.queryForObject("SELECT token_hash FROM operator_enrollment WHERE user_id=?",String.class,owner.user().getId());
        assertEquals(OperatorMfaService.sha256Hex(secret),stored);assertNotEquals(secret,stored);
        assertFalse(json.writeValueAsString(service.page(0)).contains(secret));
        assertFalse(jdbc.queryForList("SELECT detail FROM security_audit").toString().contains(secret));
        assertThrows(ResponseStatusException.class,()->approve(request));
    }
    @Test void requesterApproverAndTargetMustBeDifferentPeople() {
        assertThrows(ResponseStatusException.class,()->service.request(maker.user().getId(),maker.sid(),maker.user().getId(),current(maker).getVersion(),OperatorLifecycleService.Kind.RECOVER_MFA,null,"사유","IDENTITY_100","NOTICE_100"));
        var request=recovery();var same=current(checker);same.setOperatorPersonId(current(maker).getOperatorPersonId());users.saveAndFlush(same);
        assertThrows(ResponseStatusException.class,()->approve(request));
        same=users.findById(same.getId()).orElseThrow();same.setOperatorPersonId(current(owner).getOperatorPersonId());users.saveAndFlush(same);
        assertThrows(ResponseStatusException.class,()->approve(request));
    }
    @Test void changedTargetVersionAndRevokedRequesterInvalidateDecision() {
        var request=review();var target=current(owner);target.setUsername("다른 변경");users.saveAndFlush(target);
        assertThrows(ResponseStatusException.class,()->approve(request));
        var next=review();var requester=current(maker);requester.setEnabled(false);users.saveAndFlush(requester);
        assertThrows(ResponseStatusException.class,()->approve(next));
    }
    @Test void expiredRequestAndWrongHashCannotRecover() {
        var request=recovery();
        assertThrows(ResponseStatusException.class,()->service.decide(checker.user().getId(),checker.sid(),request.id(),"0".repeat(64),true));
        // 불변 테이블에는 만료 fixture를 처음부터 삽입한다. 트리거를 끄거나 원본 요청을 수정하지 않는다.
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO operator_security_requests(id,kind,requester_id,requester_person,target_id,target_person,target_version,
             reason,identity_evidence,notification_evidence,payload_hash,expires_at)
            VALUES(?,'REVIEW_ACCESS',?,?,?,?,?,'만료','IDENTITY_100','NOTICE_100',?,clock_timestamp()-interval '1 second')
            """,id,maker.user().getId(),maker.user().getOperatorPersonId(),owner.user().getId(),owner.user().getOperatorPersonId(),current(owner).getVersion(),"a".repeat(64));
        assertThrows(ResponseStatusException.class,()->service.decide(checker.user().getId(),checker.sid(),id,"a".repeat(64),true));
        assertNotNull(current(owner).getOperatorRecoveryRequestId());
    }
    @Test void rejectionDoesNotRestoreReportedCompromisedKeyAccess() {
        var request=recovery();var rejected=service.decide(checker.user().getId(),checker.sid(),request.id(),request.payloadHash(),false);
        assertEquals("REJECTED",rejected.request().status());assertNull(rejected.enrollmentToken());
        assertNotNull(current(owner).getOperatorRecoveryRequestId());
        assertThrows(RuntimeException.class,()->auth.signin(new SigninRequest(owner.user().getEmail(),"security-test-password")));
    }
    @Test void reissuedRecoveryInvalidatesOlderRequest() {
        var first=recovery();var second=recovery();assertNotEquals(first.targetVersion(),second.targetVersion());
        assertThrows(ResponseStatusException.class,()->approve(first));assertNotNull(approve(second).enrollmentToken());
    }
    @Test void recoveryApprovalAuditFailureRollsBackKeysGrantAndAccountState() {
        var request=recovery();
        doThrow(new IllegalStateException("복구 감사 장애 주입")).when(audit).record(eq(checker.user().getId()),eq("OPERATOR_SECURITY_DECIDE"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->approve(request));assertEquals(request.id(),current(owner).getOperatorRecoveryRequestId());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,owner.key().id()));
        assertTrue(jdbc.queryForObject("SELECT consumed FROM operator_enrollment WHERE user_id=?",Boolean.class,owner.user().getId()));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM operator_security_requests WHERE id=?",String.class,request.id()));
    }
    @Test void recoveryRequestAuditFailureDoesNotSuspendAccount() {
        doThrow(new IllegalStateException("요청 감사 장애 주입")).when(audit).record(eq(maker.user().getId()),eq("OPERATOR_SECURITY_REQUEST"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,this::recovery);assertNull(current(owner).getOperatorRecoveryRequestId());
        assertDoesNotThrow(()->sessions.authenticate(owner.user().getId(),owner.sid()));
    }
    @Test void concurrentRecoveryApprovalsHaveOneWinner() throws Exception {
        var request=recovery();try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);Callable<Boolean> work=()->{gate.await();try {approve(request);return true;} catch(ResponseStatusException e){return false;}};
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='OPERATOR_SECURITY_DECIDE' AND outcome='SUCCESS' AND target=?",Long.class,checker.user().getId(),"request:"+request.id()));
    }
    @Test void staleOrRevokedSessionCannotBypassControllerWithDirectServiceCall() {
        var session=tokens.findBySessionId(maker.sid()).orElseThrow();session.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(session);
        assertThrows(ResponseStatusException.class,this::recovery);
        session=tokens.findBySessionId(maker.sid()).orElseThrow();session.setMfaVerifiedAt(LocalDateTime.now());session.setRevoked(true);tokens.saveAndFlush(session);
        assertThrows(ResponseStatusException.class,this::recovery);
    }
    @Test void accessExpiryBlocksSigninRefreshApiAndPendingApproval() throws Exception {
        var request=review();var user=current(checker);user.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));users.saveAndFlush(user);
        assertThrows(RuntimeException.class,()->auth.signin(new SigninRequest(user.getEmail(),"security-test-password")));
        assertThrows(RuntimeException.class,()->auth.refreshAccessToken(checker.verified().refreshToken()));assertThrows(ResponseStatusException.class,()->approve(request));
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+checker.verified().accessToken())).andExpect(status().isUnauthorized());
    }
    @Test void missingOperatorExpiryFailsClosed() {
        var user=current(owner);user.setOperatorAccessExpiresAt(null);users.saveAndFlush(user);
        assertThrows(IllegalArgumentException.class,()->sessions.authenticate(user.getId(),owner.sid()));
        assertThrows(RuntimeException.class,()->auth.signin(new SigninRequest(user.getEmail(),"security-test-password")));
    }
    @Test void approvedReviewRenewsExpiredRightsButRequiresNewSigninAndMfa() {
        var user=current(owner);user.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));users.saveAndFlush(user);
        var request=review();assertNull(approve(request).enrollmentToken());var updated=current(owner);
        assertEquals(request.accessUntil(),updated.getOperatorAccessExpiresAt());assertNotNull(updated.getOperatorLastReviewedAt());
        assertThrows(IllegalArgumentException.class,()->sessions.authenticate(user.getId(),owner.sid()));
        assertTrue(auth.signin(new SigninRequest(user.getEmail(),"security-test-password")).isMfaRequired());
    }
    @Test void reviewExpiryIsBoundedAndSameExpiryReviewsStillChangeVersion() {
        assertThrows(ResponseStatusException.class,()->service.request(maker.user().getId(),maker.sid(),owner.user().getId(),current(owner).getVersion(),OperatorLifecycleService.Kind.REVIEW_ACCESS,Instant.now().plusSeconds(31*86400),"사유","REVIEW_100","NOTICE_100"));
        Instant existing=current(owner).getOperatorAccessExpiresAt();long version=current(owner).getVersion();
        var request=service.request(maker.user().getId(),maker.sid(),owner.user().getId(),version,OperatorLifecycleService.Kind.REVIEW_ACCESS,existing,"재검토","REVIEW_100","NOTICE_100");
        approve(request);assertTrue(current(owner).getVersion()>version);
    }
    @Test void emergencyRevocationDisablesAccountKeysAndSessionsWithoutWaitingForApproval() {
        service.revokeAccess(maker.user().getId(),maker.sid(),owner.user().getId(),current(owner).getVersion(),"퇴직","NOTICE_100");
        assertFalse(current(owner).isEnabled());assertThrows(IllegalArgumentException.class,()->sessions.authenticate(owner.user().getId(),owner.sid()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,owner.key().id()));
        assertThrows(ResponseStatusException.class,this::recovery);assertThrows(ResponseStatusException.class,this::review);
    }
    @Test void revocationAuditFailureRollsBackDisabledStateAndKeys() {
        doThrow(new IllegalStateException("회수 감사 장애 주입")).when(audit).record(eq(maker.user().getId()),eq("OPERATOR_ACCESS_REVOKE"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->service.revokeAccess(maker.user().getId(),maker.sid(),owner.user().getId(),current(owner).getVersion(),"침해","NOTICE_100"));
        assertTrue(current(owner).isEnabled());assertDoesNotThrow(()->sessions.authenticate(owner.user().getId(),owner.sid()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,owner.key().id()));
    }
    @Test void selfOrSamePersonRevocationIsDenied() {
        assertThrows(ResponseStatusException.class,()->service.revokeAccess(maker.user().getId(),maker.sid(),maker.user().getId(),current(maker).getVersion(),"회수","NOTICE_100"));
        var target=current(owner);target.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(target);
        assertThrows(ResponseStatusException.class,()->service.revokeAccess(maker.user().getId(),maker.sid(),owner.user().getId(),current(owner).getVersion(),"회수","NOTICE_100"));
    }
    @Test void lastKeyAndCurrentlyAuthenticatedKeyCannotBeDeleted() {
        String fingerprint=OperatorMfaService.sha256Hex(owner.key().id());
        assertThrows(ResponseStatusException.class,()->devices.revoke(owner.user().getId(),owner.sid(),fingerprint));
        assertThrows(ResponseStatusException.class,()->devices.revoke(owner.user().getId(),owner.sid(),OperatorMfaService.sha256Hex(maker.key().id())));
    }
    Account backupAuthenticated() throws Exception {
        var backup=new SoftwareAuthenticator();var options=mfa.registrationOptions(owner.user().getId(),owner.sid(),null);
        mfa.register(owner.user().getId(),owner.sid(),options.challengeId(),backup.registration(options,"http://localhost:3003",true));
        return authenticated(owner.user(),backup,owner.sid(),1);
    }
    @Test void backupKeyCanRevokeLostKeyAndAllSessionsAreRecalled() throws Exception {
        var backup=backupAuthenticated();assertEquals(2,devices.devices(owner.user().getId(),backup.sid()).size());
        devices.revoke(owner.user().getId(),backup.sid(),OperatorMfaService.sha256Hex(owner.key().id()));
        assertThrows(IllegalArgumentException.class,()->sessions.authenticate(owner.user().getId(),backup.sid()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,owner.key().id()));
        var login=auth.signin(new SigninRequest(owner.user().getEmail(),"security-test-password"));
        UUID pending=sid(login.getAccessToken());var challenge=mfa.authenticationOptions(owner.user().getId(),pending);
        assertThrows(ResponseStatusException.class,()->mfa.authenticate(owner.user().getId(),pending,challenge.challengeId(),owner.key().assertion(challenge,"http://localhost:3003",true,2,null)));
        assertNotNull(authenticated(owner.user(),backup.key(),pending,2));
    }
    @Test void deviceRevocationAuditFailureRestoresKeyAndSession() throws Exception {
        var backup=backupAuthenticated();
        doThrow(new IllegalStateException("기기 감사 장애 주입")).when(audit).record(eq(owner.user().getId()),eq("MFA_DEVICE_REVOKE"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->devices.revoke(owner.user().getId(),backup.sid(),OperatorMfaService.sha256Hex(owner.key().id())));
        assertEquals(2,devices.devices(owner.user().getId(),backup.sid()).size());assertDoesNotThrow(()->sessions.authenticate(owner.user().getId(),backup.sid()));
    }
    @Test void refreshPreservesSigningKeyFingerprint() {
        var updated=auth.refreshAccessToken(owner.verified().refreshToken());var row=tokens.findBySessionId(sid(updated.getAccessToken())).orElseThrow();
        assertEquals(OperatorMfaService.sha256Hex(owner.key().id()),row.getMfaCredentialFingerprint());
    }
    @Test void securityRequestEvidenceAndPayloadCannotBeMutatedOrDeleted() {
        var request=recovery();
        assertThrows(RuntimeException.class,()->jdbc.update("UPDATE operator_security_requests SET identity_evidence='OTHER' WHERE id=?",request.id()));
        assertThrows(RuntimeException.class,()->jdbc.update("DELETE FROM operator_security_requests WHERE id=?",request.id()));
        assertThrows(RuntimeException.class,()->jdbc.execute("TRUNCATE operator_security_requests"));
    }
    @Test void customerCannotBeRecoveryTargetOrReceiveOperatorExpiryRules() {
        var user=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@example.test").passwordHash(hash).build());
        assertFalse(auth.signin(new SigninRequest(user.getEmail(),"security-test-password")).isMfaRequired());
        assertThrows(ResponseStatusException.class,()->service.request(maker.user().getId(),maker.sid(),user.getId(),user.getVersion(),OperatorLifecycleService.Kind.RECOVER_MFA,null,"사유","IDENTITY_100","NOTICE_100"));
    }
    @Test void evidenceMustBePresentAndContainsReferenceRatherThanRawIdentityDocuments() {
        assertThrows(ResponseStatusException.class,()->service.request(maker.user().getId(),maker.sid(),owner.user().getId(),current(owner).getVersion(),OperatorLifecycleService.Kind.RECOVER_MFA,null,"사유","","NOTICE_100"));
        assertThrows(ResponseStatusException.class,()->service.request(maker.user().getId(),maker.sid(),owner.user().getId(),current(owner).getVersion(),OperatorLifecycleService.Kind.RECOVER_MFA,null,"사유","신분증 원문","NOTICE_100"));
    }
    @Test void actualHttpAuthorizationRecoveryAndApprovalReturnNoCachedSecret() throws Exception {
        var request=mvc.perform(post("/api/admin/operator-security/recoveries").header("Authorization","Bearer "+maker.verified().accessToken())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("targetId",owner.user().getId(),"expectedVersion",current(owner).getVersion(),"reason","분실 신고","identityEvidence","IDENTITY_100","notificationEvidence","NOTICE_100"))))
            .andExpect(status().isOk()).andReturn();var r=json.readTree(request.getResponse().getContentAsString());
        mvc.perform(post("/api/admin/operator-security/requests/"+r.path("id").asText()+"/approve").header("Authorization","Bearer "+maker.verified().accessToken())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("payloadHash",r.path("payloadHash").asText())))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/operator-security/requests/"+r.path("id").asText()+"/approve").header("Authorization","Bearer "+checker.verified().accessToken())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("payloadHash",r.path("payloadHash").asText())))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.enrollmentToken").isNotEmpty());
    }
    @Test void requestReadsAreBoundedAndUnknownRoutesRemainDenied() throws Exception {
        assertThrows(ResponseStatusException.class,()->service.page(-1));assertThrows(ResponseStatusException.class,()->service.page(10001));
        mvc.perform(get("/api/admin/operator-security/operators/"+owner.user().getId()).header("Authorization","Bearer "+maker.verified().accessToken())).andExpect(status().isOk());
        mvc.perform(post("/api/admin/operator-security/unreviewed").header("Authorization","Bearer "+maker.verified().accessToken())).andExpect(status().isForbidden());
    }
    @Test void currentKeyCannotDeleteItselfEvenWhenBackupExists() throws Exception {
        var backup=backupAuthenticated();
        assertThrows(ResponseStatusException.class,()->devices.revoke(owner.user().getId(),backup.sid(),OperatorMfaService.sha256Hex(backup.key().id())));
        assertEquals(2,devices.devices(owner.user().getId(),backup.sid()).size());
    }
    @Test void revokedTargetCannotBeRestoredByPreviouslyApprovedIntent() {
        var request=review();service.revokeAccess(maker.user().getId(),maker.sid(),owner.user().getId(),current(owner).getVersion(),"회수","NOTICE_100");
        assertThrows(ResponseStatusException.class,()->approve(request));assertFalse(current(owner).isEnabled());
    }
    @Test void samePersonsSeparateRequesterAndTargetAccountsAreDenied() {
        var target=current(owner);target.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(target);
        assertThrows(ResponseStatusException.class,this::recovery);
    }
    @Test void makerIdentityChangeAndCheckerSessionRevocationInvalidateApproval() {
        var request=review();var requester=current(maker);requester.setOperatorPersonId(UUID.randomUUID());users.saveAndFlush(requester);
        assertThrows(ResponseStatusException.class,()->approve(request));
        var next=review();var session=tokens.findBySessionId(checker.sid()).orElseThrow();session.setRevoked(true);tokens.saveAndFlush(session);
        assertThrows(ResponseStatusException.class,()->approve(next));
    }
    @Test void reviewAuditFailureDoesNotExtendRightsOrRevokeExistingSession() {
        var until=current(owner).getOperatorAccessExpiresAt();var request=review();
        doThrow(new IllegalStateException("재검토 감사 장애 주입")).when(audit).record(eq(checker.user().getId()),eq("OPERATOR_SECURITY_DECIDE"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->approve(request));assertEquals(until,current(owner).getOperatorAccessExpiresAt());
        assertDoesNotThrow(()->sessions.authenticate(owner.user().getId(),owner.sid()));
    }
    @Test void regularServerHasNoBootstrapRunnerOrManagementConfiguration() {
        // 전용 관리 설정이 일반 앱의 컴포넌트 탐색에 들어오면 별도 실행 경계가 사라진다.
        assertTrue(context.getBeansOfType(OperatorBootstrapService.class).isEmpty());
        assertTrue(context.getBeansOfType(dustin.operator.OperatorAdminApplication.class).isEmpty());
        assertFalse(context.containsBean("operatorBootstrapRunner"));
    }
    @Autowired org.springframework.context.ApplicationContext context;

}
