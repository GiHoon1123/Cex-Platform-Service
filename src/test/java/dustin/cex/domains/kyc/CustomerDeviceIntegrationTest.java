package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.security.*;
import dustin.cex.domains.mail.CustomerEmailVerification;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 키 두 개의 서명·최근 인증·동시 폐기와 원본/감사/세션 롤백을 검증한다. */
class CustomerDeviceIntegrationTest extends CustomerMailTestSupport {
    @Autowired CustomerMfaService customerMfa;@Autowired CustomerDeviceService devices;@Autowired CustomerEmailVerification email;
    final Map<String,Long> counts=new HashMap<>();
    OperatorMfaService.Options option(CustomerMfaService.Options x){return new OperatorMfaService.Options(x.challengeId(),x.publicKey());}
    long eventCount(){return jdbc.queryForObject("SELECT count(*) FROM customer_mfa_key_changes WHERE user_id=?",Long.class,customer.getId());}
    void login(SoftwareAuthenticator key) throws Exception {
        var r=auth.signin(new SigninRequest(customer.getEmail(),"security-test-password"));sid=UUID.fromString(jwt.verifyAccessToken(r.getAccessToken()).get("sid",String.class));
        var o=customerMfa.authenticationOptions(customer.getId(),sid);long count=counts.merge(key.id(),1L,Long::sum);
        var v=customerMfa.authenticate(customer.getId(),sid,o.challengeId(),key.assertion(option(o),"http://localhost:3003",true,count,null));
        sid=UUID.fromString(jwt.verifyAccessToken(v.accessToken()).get("sid",String.class));customerAccess=v.accessToken();
    }
    SoftwareAuthenticator initial() throws Exception {
        counts.clear();var r=email.request(customer.getId(),sid,UUID.randomUUID());UUID job=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,r.challengeId());
        String token=outbox.open(job).token();outbox.finish(outbox.claim(),"SUPPRESSED");email.confirm(customer.getId(),sid,r.challengeId(),token);
        var key=new SoftwareAuthenticator();add(key);login(key);return key;
    }
    void add(SoftwareAuthenticator key) throws Exception {var o=customerMfa.registrationOptions(customer.getId(),sid,"security-test-password");customerMfa.register(customer.getId(),sid,o.challengeId(),key.registration(option(o),"http://localhost:3003",true));}
    String fingerprint(SoftwareAuthenticator key){return sha(key.id());}
    @Test void backupRequiresVerifiedAndFreshExistingKey() throws Exception {
        var a=initial();var pending=auth.signin(new SigninRequest(customer.getEmail(),"security-test-password"));UUID waiting=UUID.fromString(jwt.verifyAccessToken(pending.getAccessToken()).get("sid",String.class));
        assertThrows(ResponseStatusException.class,()->customerMfa.registrationOptions(customer.getId(),waiting,"security-test-password"));
        var session=tokens.findBySessionId(sid).orElseThrow();session.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(session);
        assertThrows(ResponseStatusException.class,()->customerMfa.registrationOptions(customer.getId(),sid,"security-test-password"));
        assertEquals(1,eventCount());
    }
    @Test void backupRevokesAllAndBothKeysCanAuthenticate() throws Exception {
        var a=initial();var b=new SoftwareAuthenticator();UUID old=sid;add(b);assertTrue(tokens.findBySessionId(old).orElseThrow().getRevoked());
        login(b);login(a);assertEquals(2,devices.own(customer.getId(),sid).size());assertEquals(2,eventCount());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_mfa_enrollments WHERE user_id=?",Long.class,customer.getId()));
    }
    @Test void deviceHttpReturnsOnlyOwnMetadata() throws Exception {
        var a=initial();var response=mvc.perform(get("/api/auth/customer-mfa/credentials").header("Authorization","Bearer "+customerAccess))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        assertTrue(response.contains(fingerprint(a)));for(String secret:List.of(a.id(),"publicKey","attestation","sessionId","userId"))assertFalse(response.contains(secret));
        mvc.perform(get("/api/auth/customer-mfa/credentials").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());
    }
    @Test void backupKeyCanRevokeLostKeyAndEndsEverySession() throws Exception {
        var a=initial();var b=new SoftwareAuthenticator();add(b);login(b);UUID old=sid;devices.revoke(customer.getId(),sid,fingerprint(a));
        assertTrue(tokens.findBySessionId(old).orElseThrow().getRevoked());assertEquals(3,eventCount());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,a.id()));
        login(b);assertEquals(List.of(fingerprint(b)),devices.own(customer.getId(),sid).stream().map(CustomerDeviceService.Device::fingerprint).toList());
    }
    @Test void lastKeyAndCurrentlySigningKeyCannotBeRemoved() throws Exception {
        var a=initial();assertThrows(ResponseStatusException.class,()->devices.revoke(customer.getId(),sid,fingerprint(a)));
        var b=new SoftwareAuthenticator();add(b);login(a);assertThrows(ResponseStatusException.class,()->devices.revoke(customer.getId(),sid,fingerprint(a)));assertEquals(2,eventCount());
    }
    @Test void concurrentRevokeHasOneEffect() throws Exception {
        var a=initial();var b=new SoftwareAuthenticator();add(b);login(b);
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);Callable<Boolean> work=()->{gate.await();try{devices.revoke(customer.getId(),sid,fingerprint(a));return true;}catch(ResponseStatusException e){return false;}};
            var first=pool.submit(work);var second=pool.submit(work);gate.countDown();assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        }
        assertEquals(3,eventCount());
    }
    @Test void revokeAuditFailureRestoresKeySessionsAndHistory() throws Exception {
        var a=initial();var b=new SoftwareAuthenticator();add(b);login(b);
        doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(eq(customer.getId()),eq("CUSTOMER_MFA_KEY_REVOKED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->devices.revoke(customer.getId(),sid,fingerprint(a)));reset(audit);
        assertEquals(2,eventCount());assertEquals(2,devices.own(customer.getId(),sid).size());assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());
    }
    @Test void backupAuditFailureRestoresOriginalKeyAndHistory() throws Exception {
        var a=initial();var b=new SoftwareAuthenticator();var o=customerMfa.registrationOptions(customer.getId(),sid,"security-test-password");var proof=b.registration(option(o),"http://localhost:3003",true);
        doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(eq(customer.getId()),eq("CUSTOMER_MFA_REGISTER"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->customerMfa.register(customer.getId(),sid,o.challengeId(),proof));reset(audit);
        assertEquals(1,eventCount());assertEquals(1,devices.own(customer.getId(),sid).size());assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());
    }
    @Test void foreignAndOperatorSessionsCannotManageCustomerKeys() throws Exception {
        var a=initial();assertThrows(ResponseStatusException.class,()->devices.own(customer.getId(),maker.sid()));
        assertThrows(ResponseStatusException.class,()->devices.own(maker.user().getId(),maker.sid()));
        assertThrows(ResponseStatusException.class,()->devices.revoke(customer.getId(),maker.sid(),fingerprint(a)));
    }
    @Test void fiveKeyLimitCannotBeExceeded() throws Exception {
        var a=initial();for(int i=0;i<4;i++){add(new SoftwareAuthenticator());login(a);}
        assertEquals(5,devices.own(customer.getId(),sid).size());assertThrows(ResponseStatusException.class,()->customerMfa.registrationOptions(customer.getId(),sid,"security-test-password"));assertEquals(5,eventCount());
    }
    @Test void keyChangeFactsCannotBeRewrittenOrErased() throws Exception {
        initial();for(String sql:List.of("UPDATE customer_mfa_key_changes SET kind='REVOKED'","DELETE FROM customer_mfa_key_changes","TRUNCATE customer_mfa_key_changes"))assertThrows(Exception.class,()->jdbc.execute(sql));assertEquals(1,eventCount());
    }
}
