package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.security.*;
import dustin.cex.domains.mail.CustomerEmailVerification;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 이메일 확인·표준 P-256 서명·세션·HTTP 권한과 등록/인증 원자성을 검증한다. */
class CustomerMfaIntegrationTest extends CustomerMailTestSupport {
    @Autowired CustomerMfaService customerMfa;
    @Autowired CustomerMfaPolicy mfaPolicy;
    @Autowired CustomerSessionPolicy sessions;
    @Autowired CustomerEmailVerification email;
    void verifiedEmail(){
        var r=email.request(customer.getId(),sid,UUID.randomUUID());
        UUID job=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,r.challengeId());
        String token=outbox.open(job).token();outbox.finish(outbox.claim(),"SUPPRESSED");
        email.confirm(customer.getId(),sid,r.challengeId(),token);
    }
    OperatorMfaService.Options option(CustomerMfaService.Options x){return new OperatorMfaService.Options(x.challengeId(),x.publicKey());}
    CustomerMfaService.Options registration(){return customerMfa.registrationOptions(customer.getId(),sid,"security-test-password");}
    SoftwareAuthenticator enroll() throws Exception {
        verifiedEmail();var key=new SoftwareAuthenticator();var r=registration();
        customerMfa.register(customer.getId(),sid,r.challengeId(),key.registration(option(r),"http://localhost:3003",true));return key;
    }
    dustin.cex.domains.auth.model.dto.SigninResponse login(){
        var r=auth.signin(new SigninRequest(customer.getEmail(),"security-test-password"));
        sid=UUID.fromString(jwt.verifyAccessToken(r.getAccessToken()).get("sid",String.class));customerAccess=r.getAccessToken();return r;
    }
    CustomerMfaService.Verified sign(SoftwareAuthenticator key) throws Exception {
        var r=customerMfa.authenticationOptions(customer.getId(),sid);
        return customerMfa.authenticate(customer.getId(),sid,r.challengeId(),key.assertion(option(r),"http://localhost:3003",true,1,null));
    }
    @Test void firstRegistrationNeedsVerifiedAddressAndCurrentPassword(){
        assertThrows(ResponseStatusException.class,this::registration);verifiedEmail();
        assertThrows(ResponseStatusException.class,()->customerMfa.registrationOptions(customer.getId(),sid,"wrong"));
        assertFalse(mfaPolicy.required(customer.getId()));
    }
    @Test void registrationRevokesAllAndNextPasswordLoginIsPending() throws Exception {
        UUID old=sid;var key=enroll();assertTrue(tokens.findBySessionId(old).orElseThrow().getRevoked());
        assertTrue(mfaPolicy.required(customer.getId()));var pending=login();assertTrue(pending.isMfaRequired());
        assertTrue(tokens.findBySessionId(sid).orElseThrow().getExpiresAt().isBefore(LocalDateTime.now().plusMinutes(6)));
        mvc.perform(post("/api/cex/orders").header("Authorization","Bearer "+customerAccess).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        assertThrows(ResponseStatusException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(t->sessions.require(customer.getId(),sid)));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_mfa_enrollments WHERE user_id=?",Long.class,customer.getId()));
    }
    @Test void actualSignatureRotatesSessionAndOpensCustomerHttpOnly() throws Exception {
        var key=enroll();login();UUID pending=sid;String old=customerAccess;var r=sign(key);
        UUID verified=UUID.fromString(jwt.verifyAccessToken(r.accessToken()).get("sid",String.class));
        assertNotEquals(pending,verified);assertTrue(tokens.findBySessionId(pending).orElseThrow().getRevoked());
        mvc.perform(get("/api/customer/security/password-changes").header("Authorization","Bearer "+r.accessToken())).andExpect(status().isOk());
        mvc.perform(get("/api/admin/audit").header("Authorization","Bearer "+r.accessToken())).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+old)).andExpect(status().isUnauthorized());
        assertTrue(mfaPolicy.satisfied(customer.getId(),tokens.findBySessionId(verified).orElseThrow()));
    }
    @Test void registrationRejectsForeignOriginAndMissingUserVerification() throws Exception {
        verifiedEmail();var r=registration();var key=new SoftwareAuthenticator();
        for(var proof:List.of(key.registration(option(r),"https://evil.test",true),key.registration(option(r),"http://localhost:3003",false)))
            assertThrows(ResponseStatusException.class,()->customerMfa.register(customer.getId(),sid,r.challengeId(),proof));
        assertFalse(mfaPolicy.required(customer.getId()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,key.id()));
    }
    @Test void authenticationRejectsForeignOriginAndMissingUserVerification() throws Exception {
        var key=enroll();login();var r=customerMfa.authenticationOptions(customer.getId(),sid);
        for(var proof:List.of(key.assertion(option(r),"https://evil.test",true,1,null),key.assertion(option(r),"http://localhost:3003",false,1,null)))
            assertThrows(ResponseStatusException.class,()->customerMfa.authenticate(customer.getId(),sid,r.challengeId(),proof));
        assertFalse(tokens.findBySessionId(sid).orElseThrow().hasVerifiedMfa());
    }
    @Test void foreignKeyHandlePurposeAndExpiredChallengeAreRejected() throws Exception {
        var key=enroll();login();var r=customerMfa.authenticationOptions(customer.getId(),sid);var other=new SoftwareAuthenticator();
        var bad=other.assertion(option(r),"http://localhost:3003",true,1,null).deepCopy();
        ((tools.jackson.databind.node.ObjectNode)bad).put("id",key.id()).put("rawId",key.id());
        assertThrows(ResponseStatusException.class,()->customerMfa.authenticate(customer.getId(),sid,r.challengeId(),bad));
        assertThrows(ResponseStatusException.class,()->customerMfa.authenticate(customer.getId(),sid,r.challengeId(),key.assertion(option(r),"http://localhost:3003",true,1,"d3Jvbmc")));
        assertThrows(ResponseStatusException.class,()->customerMfa.register(customer.getId(),sid,r.challengeId(),json.createObjectNode()));
        jdbc.update("UPDATE customer_mfa_challenges SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",r.challengeId());
        assertThrows(ResponseStatusException.class,()->customerMfa.authenticate(customer.getId(),sid,r.challengeId(),key.assertion(option(r),"http://localhost:3003",true,1,null)));
    }
    @Test void concurrentSignatureConsumptionCommitsOnce() throws Exception {
        var key=enroll();login();var r=customerMfa.authenticationOptions(customer.getId(),sid);var proof=key.assertion(option(r),"http://localhost:3003",true,1,null);
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);Callable<Boolean> work=()->{gate.await();try{customerMfa.authenticate(customer.getId(),sid,r.challengeId(),proof);return true;}catch(ResponseStatusException e){return false;}};
            var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_MFA_AUTHENTICATE' AND outcome='SUCCESS'",Long.class,customer.getId()));
    }
    @Test void authenticationAuditFailureRollsBackCounterChallengeAndSession() throws Exception {
        var key=enroll();login();var r=customerMfa.authenticationOptions(customer.getId(),sid);var proof=key.assertion(option(r),"http://localhost:3003",true,1,null);
        doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(eq(customer.getId()),eq("CUSTOMER_MFA_AUTHENTICATE"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->customerMfa.authenticate(customer.getId(),sid,r.challengeId(),proof));reset(audit);
        assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());assertFalse(jdbc.queryForObject("SELECT consumed FROM customer_mfa_challenges WHERE id=?",Boolean.class,r.challengeId()));
        assertEquals(0,jdbc.queryForObject("SELECT signature_count FROM user_credentials WHERE credential_id=?",Long.class,key.id()));
        assertNotNull(customerMfa.authenticate(customer.getId(),sid,r.challengeId(),proof).accessToken());
    }
    @Test void registrationAuditFailureRollsBackKeyFactAndRevocation() throws Exception {
        verifiedEmail();var r=registration();var key=new SoftwareAuthenticator();var proof=key.registration(option(r),"http://localhost:3003",true);
        doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(eq(customer.getId()),eq("CUSTOMER_MFA_REGISTER"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->customerMfa.register(customer.getId(),sid,r.challengeId(),proof));reset(audit);
        assertFalse(mfaPolicy.required(customer.getId()));assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,key.id()));
        assertTrue(customerMfa.register(customer.getId(),sid,r.challengeId(),proof).requiresLogin());
    }
    @Test void addressChangeInvalidatesEnrollmentOptions() throws Exception {
        verifiedEmail();var r=registration();var key=new SoftwareAuthenticator();var u=users.findById(customer.getId()).orElseThrow();u.setEmail(UUID.randomUUID()+"@changed.invalid");users.saveAndFlush(u);
        assertThrows(ResponseStatusException.class,()->customerMfa.register(customer.getId(),sid,r.challengeId(),key.registration(option(r),"http://localhost:3003",true)));
        assertFalse(mfaPolicy.required(customer.getId()));
    }
    @Test void fiveFailuresAreDurableAndBoundFurtherProofs(){
        verifiedEmail();for(int i=0;i<5;i++)assertThrows(ResponseStatusException.class,()->customerMfa.registrationOptions(customer.getId(),sid,"wrong"));
        assertEquals(429,assertThrows(ResponseStatusException.class,this::registration).getStatusCode().value());
        assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_MFA' AND detail='VERIFICATION_DENIED'",Long.class,customer.getId()));
    }
    @Test void pendingRefreshFailsAndVerifiedRefreshDoesNotExtendMfaTime() throws Exception {
        var key=enroll();var pending=login();assertThrows(dustin.cex.domains.auth.exception.AuthException.class,()->auth.refreshAccessToken(pending.getRefreshToken()));
        var verified=sign(key);UUID old=UUID.fromString(jwt.verifyAccessToken(verified.accessToken()).get("sid",String.class));var at=tokens.findBySessionId(old).orElseThrow().getMfaVerifiedAt();
        var refreshed=auth.refreshAccessToken(verified.refreshToken());UUID next=UUID.fromString(jwt.verifyAccessToken(refreshed.getAccessToken()).get("sid",String.class));
        assertEquals(at,tokens.findBySessionId(next).orElseThrow().getMfaVerifiedAt());assertTrue(tokens.findBySessionId(old).orElseThrow().getRevoked());
    }
    @Test void operatorForeignAndDisabledSessionsCannotEnroll(){
        assertThrows(ResponseStatusException.class,()->customerMfa.registrationOptions(maker.user().getId(),maker.sid(),"security-test-password"));
        assertThrows(ResponseStatusException.class,()->customerMfa.registrationOptions(customer.getId(),maker.sid(),"security-test-password"));
        var u=users.findById(customer.getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);assertThrows(ResponseStatusException.class,this::registration);
    }
    @Test void enrollmentFactIsImmutableAndMissingKeyDoesNotRestorePasswordAccess() throws Exception {
        var key=enroll();login();var strong=sign(key);UUID session=UUID.fromString(jwt.verifyAccessToken(strong.accessToken()).get("sid",String.class));
        for(String sql:List.of("UPDATE customer_mfa_enrollments SET credential_fingerprint='changed'","DELETE FROM customer_mfa_enrollments","TRUNCATE customer_mfa_enrollments"))assertThrows(Exception.class,()->jdbc.execute(sql));
        // 테스트 전용 키 유실을 주입한다. 등록 원본을 삭제하거나 다중 인증을 해제하지 않는다.
        jdbc.update("DELETE FROM user_credentials WHERE credential_id=?",key.id());assertTrue(mfaPolicy.required(customer.getId()));assertFalse(mfaPolicy.satisfied(customer.getId(),tokens.findBySessionId(session).orElseThrow()));
        mvc.perform(get("/api/customer/security/password-changes").header("Authorization","Bearer "+strong.accessToken())).andExpect(status().isForbidden());
    }
    @Test void actualHttpOptionsRequireAuthenticationAndDoNotExposePassword() throws Exception {
        verifiedEmail();mvc.perform(post("/api/auth/customer-mfa/register/options").contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/customer-mfa/register/options").header("Authorization","Bearer "+maker.access()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentPassword","security-test-password")))).andExpect(status().isForbidden());
        var result=mvc.perform(post("/api/auth/customer-mfa/register/options").header("Authorization","Bearer "+customerAccess).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("currentPassword","security-test-password"))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        assertFalse(result.contains("security-test-password"));assertTrue(result.contains("required"));assertFalse(new CustomerMfaController.Enrollment("secret").toString().contains("secret"));
    }

    @Test void expiredPendingSessionAndOldMfaTimeCannotAuthorizeCustomer() throws Exception {
        var key=enroll();login();var pending=tokens.findBySessionId(sid).orElseThrow();
        pending.setExpiresAt(LocalDateTime.now().minusSeconds(1));tokens.saveAndFlush(pending);
        assertThrows(ResponseStatusException.class,()->customerMfa.authenticationOptions(customer.getId(),sid));
        login();var signed=sign(key);UUID strong=UUID.fromString(jwt.verifyAccessToken(signed.accessToken()).get("sid",String.class));
        var session=tokens.findBySessionId(strong).orElseThrow();
        // 토큰 수명만 남기고 서명 시각을 만료시켜 JWT 유효성으로 인증 수준을 우회하지 못하는지 본다.
        session.setMfaVerifiedAt(LocalDateTime.now().minusHours(13));tokens.saveAndFlush(session);
        assertFalse(mfaPolicy.satisfied(customer.getId(),session));
        mvc.perform(get("/api/customer/security/password-changes").header("Authorization","Bearer "+signed.accessToken())).andExpect(status().isForbidden());
        assertThrows(dustin.cex.domains.auth.exception.AuthException.class,()->auth.refreshAccessToken(signed.refreshToken()));
    }

    @Test void passwordOnlyReloginPreservesStrongSessionUntilActualSignature() throws Exception {
        var key=enroll();login();var strong=sign(key);UUID strongId=UUID.fromString(jwt.verifyAccessToken(strong.accessToken()).get("sid",String.class));
        login();UUID firstPending=sid;login();assertTrue(tokens.findBySessionId(firstPending).orElseThrow().getRevoked());
        assertFalse(tokens.findBySessionId(strongId).orElseThrow().getRevoked());
        mvc.perform(get("/api/customer/security/password-changes").header("Authorization","Bearer "+strong.accessToken())).andExpect(status().isOk());
        var request=customerMfa.authenticationOptions(customer.getId(),sid);
        customerMfa.authenticate(customer.getId(),sid,request.challengeId(),key.assertion(option(request),"http://localhost:3003",true,2,null));
        assertTrue(tokens.findBySessionId(strongId).orElseThrow().getRevoked());
    }
}
