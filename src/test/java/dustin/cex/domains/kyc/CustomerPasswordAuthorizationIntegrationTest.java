package dustin.cex.domains.kyc;

import dustin.cex.domains.account.CustomerPasswordChange;
import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.security.*;
import dustin.cex.domains.mail.CustomerEmailVerification;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 서명을 정확한 새 비밀번호/고객/세션/일회 업무에 결합하고 금융 원본과 같은 커밋을 확인한다. */
class CustomerPasswordAuthorizationIntegrationTest extends CustomerMailTestSupport {
    static final String OLD="security-test-password",NEW="independent-new-password-2026",OTHER="another-independent-password-2026";
    @Autowired CustomerMfaService factor;@Autowired CustomerEmailVerification email;@Autowired CustomerPasswordChange changes;@Autowired CustomerPasswordAuthorization authorization;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean dustin.cex.domains.cdd.CddCrypto crypto;
    SoftwareAuthenticator browser;
    CustomerPasswordChange.Input input(String next,CustomerPasswordAuthorization.Proof proof){return new CustomerPasswordChange.Input(OLD,next,proof);}
    @BeforeEach void enrollAndSign() throws Exception {
        var mail=email.request(customer.getId(),sid,UUID.randomUUID());UUID job=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,mail.challengeId());String token=outbox.open(job).token();outbox.finish(outbox.claim(),"SUPPRESSED");email.confirm(customer.getId(),sid,mail.challengeId(),token);
        browser=new SoftwareAuthenticator();var options=factor.registrationOptions(customer.getId(),sid,OLD);factor.register(customer.getId(),sid,options.challengeId(),browser.registration(new OperatorMfaService.Options(options.challengeId(),options.publicKey()),"http://localhost:3003",true));login(1);
    }
    void login(long counter) throws Exception {var r=auth.signin(new SigninRequest(customer.getEmail(),OLD));sid=UUID.fromString(jwt.verifyAccessToken(r.getAccessToken()).get("sid",String.class));var options=factor.authenticationOptions(customer.getId(),sid);var signed=factor.authenticate(customer.getId(),sid,options.challengeId(),browser.assertion(new OperatorMfaService.Options(options.challengeId(),options.publicKey()),"http://localhost:3003",true,counter,null));sid=UUID.fromString(jwt.verifyAccessToken(signed.accessToken()).get("sid",String.class));customerAccess=signed.accessToken();}
    CustomerPasswordAuthorization.Options options(){return changes.options(customer.getId(),sid,input(NEW,null));}
    CustomerPasswordAuthorization.Proof proof(CustomerPasswordAuthorization.Options options,long counter) throws Exception {return new CustomerPasswordAuthorization.Proof(options.challengeId(),browser.assertion(new OperatorMfaService.Options(options.challengeId(),options.publicKey()),"http://localhost:3003",true,counter,null));}
    long bindings(){return jdbc.queryForObject("SELECT count(*) FROM customer_password_authorizations WHERE user_id=?",Long.class,customer.getId());}
    void unchanged(){assertTrue(passwords.verify(OLD,users.findById(customer.getId()).orElseThrow().getPasswordHash()));assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());assertEquals(0,bindings());}
    @Test void verifiedLoginAloneCannotChangePassword(){assertEquals(403,assertThrows(ResponseStatusException.class,()->changes.change(customer.getId(),sid,input(NEW,null))).getStatusCode().value());unchanged();}
    @Test void exactSignatureChangesPasswordAndBindsImmutableSource() throws Exception {
        var o=options();String raw=mvc.perform(post("/api/customer/security/password").header("Authorization","Bearer "+customerAccess).contentType("application/json").content(json.writeValueAsString(input(NEW,proof(o,2)))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.requiresLogin").value(true)).andReturn().getResponse().getContentAsString();UUID change=UUID.fromString(json.readTree(raw).path("id").asText());assertTrue(tokens.findBySessionId(sid).orElseThrow().getRevoked());
        var row=jdbc.queryForMap("SELECT * FROM customer_password_authorizations WHERE change_id=?",change);assertEquals(o.challengeId(),row.get("challenge_id"));assertEquals(customer.getId(),((Number)row.get("user_id")).longValue());assertEquals(sha(browser.id()),row.get("credential_fingerprint"));
        var next=auth.signin(new SigninRequest(customer.getEmail(),NEW));assertTrue(next.isMfaRequired());assertThrows(dustin.cex.domains.auth.exception.AuthException.class,()->auth.signin(new SigninRequest(customer.getEmail(),OLD)));
    }
    @Test void proofCannotApplyDifferentNewPasswordAndOriginalStillWorks() throws Exception {
        var o=options();var p=proof(o,2);assertThrows(ResponseStatusException.class,()->changes.change(customer.getId(),sid,input(OTHER,p)));unchanged();
        assertNotNull(changes.change(customer.getId(),sid,input(NEW,p)).id());
    }
    @Test void loginChallengeCannotAuthorizePasswordChange() throws Exception {
        var login=factor.authenticationOptions(customer.getId(),sid);var p=new CustomerPasswordAuthorization.Proof(login.challengeId(),browser.assertion(new OperatorMfaService.Options(login.challengeId(),login.publicKey()),"http://localhost:3003",true,2,null));
        assertThrows(ResponseStatusException.class,()->changes.change(customer.getId(),sid,input(NEW,p)));unchanged();
    }
    @Test void wrongOriginUserVerificationKeyAndExpiredProofAreRejected() throws Exception {
        var o=options();var value=new OperatorMfaService.Options(o.challengeId(),o.publicKey());var other=new SoftwareAuthenticator();
        for(var raw:List.of(browser.assertion(value,"https://evil.test",true,2,null),browser.assertion(value,"http://localhost:3003",false,2,null),other.assertion(value,"http://localhost:3003",true,2,null)))
            assertThrows(ResponseStatusException.class,()->changes.change(customer.getId(),sid,input(NEW,new CustomerPasswordAuthorization.Proof(o.challengeId(),raw))));
        jdbc.update("UPDATE customer_password_challenges SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",o.challengeId());assertThrows(ResponseStatusException.class,()->changes.change(customer.getId(),sid,input(NEW,proof(o,2))));unchanged();
    }
    @Test void newlyVerifiedSessionCannotUsePreviousSessionsOptions() throws Exception {
        var o=options();var p=proof(o,3);login(2);assertThrows(ResponseStatusException.class,()->changes.change(customer.getId(),sid,input(NEW,p)));unchanged();
    }
    @Test void finalAuditFailureRollsBackSignatureChangeSessionsAndMail() throws Exception {
        var o=options();var p=proof(o,2);doThrow(new IllegalStateException("격리 변경 감사 실패")).when(audit).record(any(),eq("CUSTOMER_PASSWORD_CHANGED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->changes.change(customer.getId(),sid,input(NEW,p)));reset(audit);unchanged();
        assertFalse(jdbc.queryForObject("SELECT consumed FROM customer_password_challenges WHERE id=?",Boolean.class,o.challengeId()));assertEquals(1,jdbc.queryForObject("SELECT signature_count FROM user_credentials WHERE credential_id=?",Long.class,browser.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE user_id=? AND kind='PASSWORD_CHANGED'",Long.class,customer.getId()));assertNotNull(changes.change(customer.getId(),sid,input(NEW,p)).id());
    }
    @Test void optionsAuditFailureCreatesNoPendingAuthorization(){
        doThrow(new IllegalStateException("격리 옵션 감사 실패")).when(audit).record(any(),eq("CUSTOMER_PASSWORD_OPTIONS"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::options);reset(audit);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_password_challenges WHERE user_id=?",Long.class,customer.getId()));unchanged();
    }
    @Test void concurrentUseCommitsOneChangeAndOneBinding() throws Exception {
        var o=options();var p=proof(o,2);try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);Callable<Boolean> work=()->{gate.await();try{changes.change(customer.getId(),sid,input(NEW,p));return true;}catch(ResponseStatusException e){return false;}};var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));}assertEquals(1,bindings());
    }
    @Test void fiveFailedProofsAreDurableAndRateLimited(){for(int i=0;i<5;i++)assertThrows(ResponseStatusException.class,()->changes.change(customer.getId(),sid,input(NEW,null)));assertEquals(429,assertThrows(ResponseStatusException.class,()->changes.change(customer.getId(),sid,input(NEW,null))).getStatusCode().value());unchanged();}
    @Test void optionsExposePurposeWithoutPasswordsOrPublicPasswordHashes() throws Exception {
        var response=mvc.perform(post("/api/customer/security/password/options").header("Authorization","Bearer "+customerAccess).contentType("application/json").content(json.writeValueAsString(input(NEW,null))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.purpose").value("PASSWORD_CHANGE")).andReturn().getResponse().getContentAsString();
        String stored=jdbc.queryForMap("SELECT * FROM customer_password_challenges WHERE user_id=?",customer.getId()).toString();for(String secret:List.of(OLD,NEW,sha(NEW))){assertFalse(response.contains(secret));assertFalse(stored.contains(secret));}assertFalse(input(NEW,null).toString().contains(NEW));
    }
    @Test void sourceMustExistAtCommitAndAuthorizationFactsAreImmutable() throws Exception {
        var o=options();var p=proof(o,2);var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        // 서명 검증만 성공시키고 변경 원본을 만들지 않으면 지연 외래키가 커밋을 막는다.
        assertThrows(Exception.class,()->tx.executeWithoutResult(t->authorization.authorize(customer.getId(),sid,NEW,p,UUID.randomUUID())));unchanged();
        assertFalse(jdbc.queryForObject("SELECT consumed FROM customer_password_challenges WHERE id=?",Boolean.class,o.challengeId()));
        changes.change(customer.getId(),sid,input(NEW,p));for(String sql:List.of("UPDATE customer_password_authorizations SET credential_fingerprint='changed'","DELETE FROM customer_password_authorizations","TRUNCATE customer_password_authorizations"))assertThrows(Exception.class,()->jdbc.execute(sql));assertEquals(1,bindings());
    }

    @Test void passwordSetupFailureDoesNotConsumeVerificationBudget(){
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"격리 키 장애")).when(crypto).fingerprint(eq("customer-password-change-v1"),anyString());
        for(int i=0;i<6;i++)assertEquals(503,assertThrows(ResponseStatusException.class,this::options).getStatusCode().value());reset(crypto);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_PASSWORD_AUTHORIZATION' AND outcome='DENIED'",Long.class,customer.getId()));unchanged();assertNotNull(options().challengeId());
    }
    @Test void factorSetupFailureDoesNotConsumeVerificationBudget(){
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"격리 키 장애")).when(crypto).fingerprint(eq("customer-email-exact-v1"),anyString());
        for(int i=0;i<6;i++)assertEquals(503,assertThrows(ResponseStatusException.class,()->factor.registrationOptions(customer.getId(),sid,OLD)).getStatusCode().value());reset(crypto);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_MFA' AND outcome='DENIED'",Long.class,customer.getId()));unchanged();assertNotNull(factor.registrationOptions(customer.getId(),sid,OLD).challengeId());
    }
}
