package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.security.*;
import dustin.cex.domains.mail.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 두 소프트웨어 인증기와 격리 DB/SMTPS로 복구·재사용·동시 실행·실패 원자성을 확인한다. */
class CustomerRecoveryIntegrationTest extends CustomerMailTestSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean dustin.cex.domains.cdd.CddCrypto crypto;
    @Autowired CustomerRecoveryService recovery;
    @Autowired CustomerMfaKeyChanges keyChanges;
    @Autowired CustomerMfaService mfa;
    @Autowired CustomerEmailVerification email;
    @Autowired CustomerMailRetryService retries;
    final Map<String,Long> counters=new HashMap<>();
    final String password="security-test-password";
    OperatorMfaService.Options option(CustomerRecoveryService.Options o){return new OperatorMfaService.Options(o.challengeId(),o.publicKey());}
    void suppress(){for(int i=0;i<30;i++){var lease=outbox.claim();if(lease==null)return;outbox.finish(lease,"SUPPRESSED");}fail("격리 예약 정리 초과");}
    void pending(){var r=auth.signin(new SigninRequest(customer.getEmail(),password));sid=UUID.fromString(jwt.verifyAccessToken(r.getAccessToken()).get("sid",String.class));customerAccess=r.getAccessToken();}
    void login(SoftwareAuthenticator key) throws Exception {
        pending();var o=mfa.authenticationOptions(customer.getId(),sid);
        var v=mfa.authenticate(customer.getId(),sid,o.challengeId(),key.assertion(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true,counters.merge(key.id(),1L,Long::sum),null));
        sid=UUID.fromString(jwt.verifyAccessToken(v.accessToken()).get("sid",String.class));customerAccess=v.accessToken();
    }
    SoftwareAuthenticator initial() throws Exception {
        counters.clear();var e=email.request(customer.getId(),sid,UUID.randomUUID());UUID job=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,e.challengeId());String token=outbox.open(job).token();suppress();email.confirm(customer.getId(),sid,e.challengeId(),token);
        var key=new SoftwareAuthenticator();var o=mfa.registrationOptions(customer.getId(),sid,password);mfa.register(customer.getId(),sid,o.challengeId(),key.registration(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true));login(key);suppress();return key;
    }
    CustomerRecoveryService.Proof signed(CustomerRecoveryService.Options o,SoftwareAuthenticator key) throws Exception {
        return new CustomerRecoveryService.Proof(o.challengeId(),key.assertion(option(o),"http://localhost:3003",true,counters.merge(key.id(),1L,Long::sum),null));
    }
    CustomerRecoveryService.Code issue(SoftwareAuthenticator key) throws Exception {return recovery.replaceCode(customer.getId(),sid,signed(recovery.codeOptions(customer.getId(),sid,password),key));}
    CustomerRecoveryService.Proof registration(CustomerRecoveryService.Options o,SoftwareAuthenticator key) throws Exception {return new CustomerRecoveryService.Proof(o.challengeId(),key.registration(option(o),"http://localhost:3003",true));}
    long events(){return jdbc.queryForObject("SELECT count(*) FROM customer_recovery_events WHERE user_id=?",Long.class,customer.getId());}
    long keyCount(){return jdbc.queryForObject("SELECT count(*) FROM user_credentials c JOIN user_entities u ON u.id=c.user_entity_user_id WHERE u.name=?",Long.class,customer.getId().toString());}
    @Test void signedCodeIsReturnedOnceAndOnlySaltedHashIsStored() throws Exception {
        var key=initial();assertFalse(recovery.status(customer.getId(),sid).configured());var code=issue(key);assertTrue(code.recoveryCode().matches("[A-Za-z0-9_-]{43}"));assertFalse(code.requiresLogin());assertTrue(recovery.status(customer.getId(),sid).configured());
        String hash=jdbc.queryForObject("SELECT code_hash FROM customer_recovery_codes WHERE user_id=?",String.class,customer.getId());assertTrue(hash.startsWith("$argon2"));assertTrue(passwords.verify(code.recoveryCode(),hash));assertFalse(code.toString().contains(code.recoveryCode()));
        String facts=jdbc.queryForList("SELECT * FROM customer_recovery_events WHERE user_id=?",customer.getId()).toString();String auditRows=jdbc.queryForList("SELECT * FROM security_audit WHERE actor_id=?",customer.getId()).toString();assertFalse(facts.contains(code.recoveryCode()));assertFalse(auditRows.contains(code.recoveryCode()));
    }
    @Test void codeIssuanceNeedsCurrentPasswordAndFreshExistingKey() throws Exception {
        initial();assertThrows(ResponseStatusException.class,()->recovery.codeOptions(customer.getId(),sid,"wrong"));assertThrows(ResponseStatusException.class,()->recovery.replaceCode(customer.getId(),sid,null));
        var s=tokens.findBySessionId(sid).orElseThrow();s.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(s);assertThrows(ResponseStatusException.class,()->recovery.codeOptions(customer.getId(),sid,password));assertEquals(0,events());assertFalse(recovery.status(customer.getId(),sid).configured());
    }
    @Test void loginSignatureCannotIssueCodeAndOptionsCannotBeReplayed() throws Exception {
        var key=initial();var login=mfa.authenticationOptions(customer.getId(),sid);var p=signed(new CustomerRecoveryService.Options(login.challengeId(),"AUTHENTICATE",login.publicKey()),key);assertThrows(ResponseStatusException.class,()->recovery.replaceCode(customer.getId(),sid,p));
        var o=recovery.codeOptions(customer.getId(),sid,password);var proof=signed(o,key);recovery.replaceCode(customer.getId(),sid,proof);assertThrows(ResponseStatusException.class,()->recovery.replaceCode(customer.getId(),sid,proof));assertEquals(1,events());
    }
    @Test void replacingCodeInvalidatesPreviousWithoutRevealingNewOnStatus() throws Exception {
        var key=initial();var old=issue(key);var next=issue(key);assertNotEquals(old.recoveryCode(),next.recoveryCode());pending();assertThrows(ResponseStatusException.class,()->recovery.recoveryOptions(customer.getId(),sid,password,old.recoveryCode()));assertNotNull(recovery.recoveryOptions(customer.getId(),sid,password,next.recoveryCode()).challengeId());assertEquals(2,events());
    }
    @Test void validRecoveryReplacesLostKeysRevokesEverySessionAndRequiresActualNewKeyLogin() throws Exception {
        var old=initial();var code=issue(old);UUID strong=sid;pending();UUID waiting=sid;var next=new SoftwareAuthenticator();var o=recovery.recoveryOptions(customer.getId(),sid,password,code.recoveryCode());
        var result=recovery.recover(customer.getId(),sid,registration(o,next));assertTrue(result.requiresLogin());assertNotEquals(code.recoveryCode(),result.recoveryCode());assertEquals(1,keyCount());assertTrue(tokens.findBySessionId(strong).orElseThrow().getRevoked());assertTrue(tokens.findBySessionId(waiting).orElseThrow().getRevoked());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_mfa_enrollments WHERE user_id=?",Long.class,customer.getId()));
        assertThrows(ResponseStatusException.class,()->login(old));login(next);assertTrue(recovery.status(customer.getId(),sid).configured());assertEquals(2,events());assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM customer_recovery_key_changes WHERE user_id=?",Long.class,customer.getId()));pending();assertThrows(ResponseStatusException.class,()->recovery.recoveryOptions(customer.getId(),sid,password,code.recoveryCode()));assertNotNull(recovery.recoveryOptions(customer.getId(),sid,password,result.recoveryCode()).challengeId());
    }
    @Test void invalidCodePasswordAndForeignSessionsPreserveExistingKey() throws Exception {
        var key=initial();var code=issue(key);pending();assertThrows(ResponseStatusException.class,()->recovery.recoveryOptions(customer.getId(),sid,"wrong",code.recoveryCode()));assertThrows(ResponseStatusException.class,()->recovery.recoveryOptions(customer.getId(),sid,password,token()));assertThrows(ResponseStatusException.class,()->recovery.recoveryOptions(customer.getId(),maker.sid(),password,code.recoveryCode()));assertThrows(ResponseStatusException.class,()->recovery.recoveryOptions(maker.user().getId(),maker.sid(),password,code.recoveryCode()));assertEquals(1,keyCount());assertEquals(1,events());
    }
    @Test void newKeyMustHaveCorrectOriginAndUserVerification() throws Exception {
        var key=initial();var code=issue(key);pending();var o=recovery.recoveryOptions(customer.getId(),sid,password,code.recoveryCode());
        for(boolean uv:List.of(false,true)){var newKey=new SoftwareAuthenticator();var raw=newKey.registration(option(o),uv?"https://evil.test":"http://localhost:3003",uv);assertThrows(ResponseStatusException.class,()->recovery.recover(customer.getId(),sid,new CustomerRecoveryService.Proof(o.challengeId(),raw)));}
        assertEquals(1,keyCount());assertEquals(1,events());assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());assertNotNull(recovery.recover(customer.getId(),sid,registration(o,new SoftwareAuthenticator())).recoveryCode());
    }
    @Test void expiredAndPreviousSessionChallengeCannotRecover() throws Exception {
        var key=initial();var code=issue(key);pending();var o=recovery.recoveryOptions(customer.getId(),sid,password,code.recoveryCode());var p=registration(o,new SoftwareAuthenticator());jdbc.update("UPDATE customer_recovery_challenges SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",o.challengeId());assertThrows(ResponseStatusException.class,()->recovery.recover(customer.getId(),sid,p));
        var next=recovery.recoveryOptions(customer.getId(),sid,password,code.recoveryCode());var other=registration(next,new SoftwareAuthenticator());pending();assertThrows(ResponseStatusException.class,()->recovery.recover(customer.getId(),sid,other));assertEquals(1,events());
    }
    @Test void recoveryAuditFailureRestoresKeysCodeChallengeSessionsAndNotices() throws Exception {
        var key=initial();var code=issue(key);suppress();pending();var o=recovery.recoveryOptions(customer.getId(),sid,password,code.recoveryCode());var next=new SoftwareAuthenticator();var p=registration(o,next);long mails=jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE user_id=?",Long.class,customer.getId());
        doThrow(new IllegalStateException("격리 복구 감사 실패")).when(audit).record(any(),eq("CUSTOMER_RECOVERY_COMPLETED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->recovery.recover(customer.getId(),sid,p));reset(audit);
        assertEquals(1,events());assertEquals(1,keyCount());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,next.id()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_recovery_challenges WHERE id=?",Long.class,o.challengeId()));assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());assertEquals(mails,jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE user_id=?",Long.class,customer.getId()));assertTrue(recovery.recover(customer.getId(),sid,p).requiresLogin());
    }
    @Test void codeReservationFailureRollsBackSignatureAndGeneration() throws Exception {
        var key=initial();var o=recovery.codeOptions(customer.getId(),sid,password);var p=signed(o,key);doThrow(new IllegalStateException("격리 예약 실패")).when(audit).record(any(),eq("CUSTOMER_MAIL_QUEUED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->recovery.replaceCode(customer.getId(),sid,p));reset(audit);assertEquals(0,events());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_recovery_codes WHERE user_id=?",Long.class,customer.getId()));assertNotNull(recovery.replaceCode(customer.getId(),sid,p).recoveryCode());
    }
    @Test void simultaneousRecoveryConsumesCodeOnce() throws Exception {
        var key=initial();var code=issue(key);pending();var o=recovery.recoveryOptions(customer.getId(),sid,password,code.recoveryCode());var p=registration(o,new SoftwareAuthenticator());
        try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);Callable<Boolean> work=()->{gate.await();try{recovery.recover(customer.getId(),sid,p);return true;}catch(ResponseStatusException e){return false;}};var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}assertEquals(2,events());assertEquals(1,keyCount());
    }
    @Test void fiveInvalidCodesAreDurableAndThenRateLimited() throws Exception {
        var key=initial();issue(key);pending();for(int i=0;i<5;i++)assertEquals(403,assertThrows(ResponseStatusException.class,()->recovery.recoveryOptions(customer.getId(),sid,password,token())).getStatusCode().value());assertEquals(429,assertThrows(ResponseStatusException.class,()->recovery.recoveryOptions(customer.getId(),sid,password,token())).getStatusCode().value());assertEquals(1,events());
    }
    @Test void infrastructureFailureDoesNotSpendVerificationBudget() throws Exception {
        initial();doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"격리 키 장애")).when(crypto).fingerprint(eq("customer-email-exact-v1"),anyString());for(int i=0;i<6;i++)assertEquals(503,assertThrows(ResponseStatusException.class,()->recovery.codeOptions(customer.getId(),sid,password)).getStatusCode().value());reset(crypto);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_RECOVERY' AND outcome='DENIED'",Long.class,customer.getId()));assertNotNull(recovery.codeOptions(customer.getId(),sid,password));
    }
    @Test void securityMailContainsNoRecoverySecretAndIgnoresServiceOptOut() throws Exception {
        var key=initial();var code=issue(key);dispatcher.dispatchOnce();assertTrue(server().waitForIncomingEmail(3000,1));var message=server().getReceivedMessages()[0];assertEquals("계정 복구 보안 안내",message.getSubject());String body=(String)message.getContent();for(String secret:List.of(code.recoveryCode(),password,key.id(),sid.toString(),"token="))assertFalse(body.contains(secret));
    }
    @Test void recoveryFactsCannotBeChangedOrErased() throws Exception {
        var key=initial();issue(key);for(String sql:List.of("UPDATE customer_recovery_events SET kind='CODE_REPLACED'","DELETE FROM customer_recovery_events","TRUNCATE customer_recovery_events","UPDATE customer_recovery_key_changes SET event_id=event_id","DELETE FROM customer_recovery_key_changes","TRUNCATE customer_recovery_key_changes","UPDATE customer_recovery_codes SET code_hash='changed'","DELETE FROM customer_recovery_codes","TRUNCATE customer_recovery_codes"))assertThrows(Exception.class,()->jdbc.execute(sql));assertEquals(1,events());
    }
    @Test void httpEnforcesStrongCodeManagementAndAllowsOnlyScopedPendingRecovery() throws Exception {
        var key=initial();var code=issue(key);mvc.perform(get("/api/auth/customer-recovery/status").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.configured").value(true));pending();
        mvc.perform(get("/api/auth/customer-recovery/status").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
        var raw=mvc.perform(post("/api/auth/customer-recovery/register/options").header("Authorization","Bearer "+customerAccess).contentType("application/json").content(json.writeValueAsString(new CustomerRecoveryController.Recovery(password,code.recoveryCode())))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        var tree=json.readTree(raw);var o=new CustomerRecoveryService.Options(UUID.fromString(tree.path("challengeId").asText()),tree.path("purpose").asText(),tree.path("publicKey"));var p=registration(o,new SoftwareAuthenticator());
        mvc.perform(post("/api/auth/customer-recovery/register").header("Authorization","Bearer "+customerAccess).contentType("application/json").content(json.writeValueAsString(p))).andExpect(status().isOk()).andExpect(jsonPath("$.requiresLogin").value(true)).andExpect(jsonPath("$.recoveryCode").isString());
        mvc.perform(post("/api/auth/customer-recovery/register/options").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());assertFalse(new CustomerRecoveryController.Recovery(password,code.recoveryCode()).toString().contains(code.recoveryCode()));
    }
    @Test void disabledAccountCannotRecoverButAlreadyReservedWarningStillSends() throws Exception {
        var key=initial();var code=issue(key);pending();var user=users.findById(customer.getId()).orElseThrow();user.setEnabled(false);users.saveAndFlush(user);
        assertThrows(ResponseStatusException.class,()->recovery.recoveryOptions(customer.getId(),sid,password,code.recoveryCode()));dispatcher.dispatchOnce();assertTrue(server().waitForIncomingEmail(3000,1));assertEquals("계정 복구 보안 안내",server().getReceivedMessages()[0].getSubject());assertEquals(1,events());
    }
    @Test void changedAddressSuppressesReservedRecoveryWarning() throws Exception {
        var key=initial();issue(key);var user=users.findById(customer.getId()).orElseThrow();user.setEmail(UUID.randomUUID()+"@changed.invalid");users.saveAndFlush(user);dispatcher.dispatchOnce();assertEquals(0,server().getReceivedMessages().length);
        UUID job=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE user_id=? AND kind='ACCOUNT_RECOVERY'",UUID.class,customer.getId());assertEquals("SUPPRESSED",outbox.state(job).status());
    }

    @Test void codeManagementHttpSignsExactPurposeAndNeverReReadsSecret() throws Exception {
        var key=initial();var raw=mvc.perform(post("/api/auth/customer-recovery/code/options").header("Authorization","Bearer "+customerAccess).contentType("application/json").content(json.writeValueAsString(new CustomerRecoveryController.Password(password))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.purpose").value("CODE_REPLACE")).andReturn().getResponse().getContentAsString();
        var tree=json.readTree(raw);var options=new CustomerRecoveryService.Options(UUID.fromString(tree.path("challengeId").asText()),tree.path("purpose").asText(),tree.path("publicKey"));
        String issued=mvc.perform(post("/api/auth/customer-recovery/code").header("Authorization","Bearer "+customerAccess).contentType("application/json").content(json.writeValueAsString(signed(options,key))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.requiresLogin").value(false)).andReturn().getResponse().getContentAsString();
        String code=json.readTree(issued).path("recoveryCode").asText();assertEquals(43,code.length());String state=mvc.perform(get("/api/auth/customer-recovery/status").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertFalse(state.contains(code));assertFalse(state.contains("code_hash"));
    }
    @Test void unenrolledCustomerCannotCreateRecoveryOrUseItToEnroll() {
        assertFalse(recovery.status(customer.getId(),sid).configured());
        assertThrows(ResponseStatusException.class,()->recovery.codeOptions(customer.getId(),sid,password));
        assertThrows(ResponseStatusException.class,()->recovery.recoveryOptions(customer.getId(),sid,password,token()));assertEquals(0,events());
    }

    @Test void multiKeyRecoveryKeepsAllFactsButSendsOneAggregateWarning() throws Exception {
        var first=initial();var second=new SoftwareAuthenticator();var options=mfa.registrationOptions(customer.getId(),sid,password);mfa.register(customer.getId(),sid,options.challengeId(),second.registration(new OperatorMfaService.Options(options.challengeId(),options.publicKey()),"http://localhost:3003",true));login(first);
        var code=issue(first);suppress();pending();var o=recovery.recoveryOptions(customer.getId(),sid,password,code.recoveryCode());recovery.recover(customer.getId(),sid,registration(o,new SoftwareAuthenticator()));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM customer_recovery_key_changes WHERE user_id=?",Long.class,customer.getId()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_mail_delivery d JOIN customer_mail_jobs j ON j.id=d.job_id WHERE j.user_id=? AND d.status='PENDING'",Long.class,customer.getId()));dispatcher.dispatchOnce();assertEquals(1,server().getReceivedMessages().length);assertEquals("계정 복구 보안 안내",server().getReceivedMessages()[0].getSubject());
    }
    @Test void recoveryKeyFactWithoutSourceCannotCommit() throws Exception {
        initial();long before=jdbc.queryForObject("SELECT count(*) FROM customer_mfa_key_changes WHERE user_id=?",Long.class,customer.getId());var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThrows(Exception.class,()->tx.executeWithoutResult(t->keyChanges.appendForRecovery(customer.getId(),sid,CustomerMfaKeyChanges.Kind.REVOKED,sha("not-a-real-key"),UUID.randomUUID())));
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM customer_mfa_key_changes WHERE user_id=?",Long.class,customer.getId()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_recovery_key_changes WHERE user_id=?",Long.class,customer.getId()));
    }

    @Test void exhaustedRecoveryWarningCannotUseOperatorAdditionalRetry() throws Exception {
        var key=initial();issue(key);UUID job=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE user_id=? AND kind='ACCOUNT_RECOVERY'",UUID.class,customer.getId());
        for(int i=0;i<5;i++){outbox.finish(outbox.claim(),"SMTP_UNKNOWN");jdbc.update("UPDATE customer_mail_delivery SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE job_id=?",job);}
        assertEquals("EXHAUSTED",outbox.state(job).status());assertThrows(ResponseStatusException.class,()->retries.propose(maker.user().getId(),maker.sid(),job,UUID.randomUUID(),checker.user().getId()));
    }

}
