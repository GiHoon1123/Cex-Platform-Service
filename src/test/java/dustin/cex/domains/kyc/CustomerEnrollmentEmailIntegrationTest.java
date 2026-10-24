package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.mail.*;
import dustin.cex.domains.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 단순 주소 확인과 이번 세션의 최근 소유 확인을 구분하며 원본 재생·다른 세션·만료 우회를 검사한다. */
class CustomerEnrollmentEmailIntegrationTest extends CustomerMailTestSupport {
    @Autowired CustomerEmailVerification email;@Autowired CustomerMailRecipientPolicy recipients;
    @Autowired CustomerMfaService factor;@Autowired CddCrypto crypto;
    record Confirmed(UUID challenge,String token,UUID requestKey){}
    Confirmed fresh(){UUID key=UUID.randomUUID();var r=email.request(customer.getId(),sid,key);UUID job=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,r.challengeId());String token=outbox.open(job).token();outbox.finish(outbox.claim(),"SUPPRESSED");email.confirm(customer.getId(),sid,r.challengeId(),token);return new Confirmed(r.challengeId(),token,key);}
    Confirmed historical(int minutes,boolean bind){
        // 실행 소유 DB에 과거 확인 상태를 넣는 fixture다. 기존 사실/시각을 수정하거나 불변 트리거를 끄지 않는다.
        UUID id=UUID.randomUUID(),key=UUID.randomUUID();String token=token();
        jdbc.update("INSERT INTO customer_email_challenges(id,user_id,request_key,email_mac,rate_mac,key_id,token_hash,expires_at,created_at) VALUES(?,?,?,?,?,?,?,clock_timestamp()-interval '5 minutes',clock_timestamp()-interval '20 minutes')",id,customer.getId(),key,recipients.addressMac(customer.getEmail()),crypto.fingerprint("customer-email-rate-v1",customer.getEmail().toLowerCase(Locale.ROOT)),crypto.keyId(),sha(id+":"+token));
        jdbc.update("INSERT INTO customer_email_confirmations(challenge_id,user_id,email_mac,key_id,confirmed_at) VALUES(?,?,?,?,clock_timestamp()-(? * interval '1 minute'))",id,customer.getId(),recipients.addressMac(customer.getEmail()),crypto.keyId(),minutes);
        if(bind)jdbc.update("INSERT INTO customer_email_confirmation_sessions(challenge_id,session_id) VALUES(?,?)",id,sid);
        return new Confirmed(id,token,key);
    }
    CustomerMfaService.Options options(){return factor.registrationOptions(customer.getId(),sid,"security-test-password");}
    void relogin(){var r=auth.signin(new SigninRequest(customer.getEmail(),"security-test-password"));sid=UUID.fromString(jwt.verifyAccessToken(r.getAccessToken()).get("sid",String.class));customerAccess=r.getAccessToken();}
    @Test void historicalVerificationCannotBootstrapFirstKey(){historical(11,true);assertTrue(email.status(customer.getId(),sid).verified());assertFalse(recipients.recentlyVerified(customer,sid));assertThrows(ResponseStatusException.class,this::options);}
    @Test void newConfirmationAllowsFirstKeyWithoutRewritingOldFacts() throws Exception {
        historical(11,true);fresh();var o=options();var browser=new SoftwareAuthenticator();assertTrue(factor.register(customer.getId(),sid,o.challengeId(),browser.registration(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true)).requiresLogin());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM customer_email_confirmations WHERE user_id=?",Long.class,customer.getId()));
    }
    @Test void consumedOldTokenDoesNotRenewConfirmationTime(){var old=historical(11,true);assertTrue(email.confirm(customer.getId(),sid,old.challenge(),old.token()).verified());assertFalse(recipients.recentlyVerified(customer,sid));assertThrows(ResponseStatusException.class,this::options);}
    @Test void anotherSessionCannotReuseConfirmedPossession() throws Exception {
        fresh();UUID original=sid;
        mvc.perform(get("/api/customer/email").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true)).andExpect(jsonPath("$.recentlyVerified").value(true));
        relogin();assertNotEquals(original,sid);assertTrue(email.status(customer.getId(),sid).verified());assertFalse(recipients.recentlyVerified(customer,sid));
        mvc.perform(get("/api/customer/email").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true)).andExpect(jsonPath("$.recentlyVerified").value(false));
        assertThrows(ResponseStatusException.class,this::options);
    }
    @Test void idempotentRequestOrConfirmationCannotRebindOldProof(){
        var proof=fresh();UUID original=sid;relogin();var replay=email.request(customer.getId(),sid,proof.requestKey());assertEquals(proof.challenge(),replay.challengeId());assertTrue(email.confirm(customer.getId(),sid,proof.challenge(),proof.token()).verified());
        assertEquals(original,jdbc.queryForObject("SELECT session_id FROM customer_email_confirmation_sessions WHERE challenge_id=?",UUID.class,proof.challenge()));assertFalse(recipients.recentlyVerified(customer,sid));assertThrows(ResponseStatusException.class,this::options);
    }
    @Test void confirmationAuditFailureRollsBackPossessionBinding(){
        var r=email.request(customer.getId(),sid,UUID.randomUUID());UUID job=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,r.challengeId());String token=outbox.open(job).token();outbox.finish(outbox.claim(),"SUPPRESSED");
        doThrow(new IllegalStateException("격리 확인 감사 실패")).when(audit).record(any(),eq("CUSTOMER_EMAIL_VERIFIED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->email.confirm(customer.getId(),sid,r.challengeId(),token));reset(audit);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_email_confirmation_sessions WHERE challenge_id=?",Long.class,r.challengeId()));assertFalse(email.status(customer.getId(),sid).verified());assertTrue(email.confirm(customer.getId(),sid,r.challengeId(),token).verified());assertNotNull(options().challengeId());
    }
    @Test void possessionSessionBindingIsImmutable(){
        var r=fresh();for(String sql:List.of("UPDATE customer_email_confirmation_sessions SET session_id=session_id","DELETE FROM customer_email_confirmation_sessions","TRUNCATE customer_email_confirmation_sessions"))assertThrows(Exception.class,()->jdbc.execute(sql));assertEquals(sid,jdbc.queryForObject("SELECT session_id FROM customer_email_confirmation_sessions WHERE challenge_id=?",UUID.class,r.challenge()));
    }
    @Test void nineMinuteConfirmationIsStillFresh(){historical(9,true);assertTrue(recipients.recentlyVerified(customer,sid));assertNotNull(options().challengeId());}
    @Test void historicalFactWithoutSessionIsNotInferred(){historical(9,false);assertTrue(email.status(customer.getId(),sid).verified());assertFalse(recipients.recentlyVerified(customer,sid));assertThrows(ResponseStatusException.class,this::options);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_email_confirmation_sessions s JOIN customer_email_confirmations c ON c.challenge_id=s.challenge_id WHERE c.user_id=?",Long.class,customer.getId()));}
}
