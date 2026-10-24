package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.model.dto.*;
import dustin.cex.domains.auth.exception.AuthException;
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

/** 실제 기존 키 서명·새 주소 토큰·격리 SMTP와 DB로 주소 변경 전체를 검증한다. */
class CustomerEmailChangeIntegrationTest extends CustomerEmailChangeTestSupport {
    @Test void signedRequestLeavesCurrentAddressAndStoresOnlyBoundFingerprints() throws Exception {
        initial();var receipt=request();unchanged();var row=jdbc.queryForMap("SELECT * FROM customer_email_change_requests WHERE id=?",receipt.challengeId());assertEquals(sha(browser.id()),row.get("credential_fingerprint"));String raw=row.toString();for(String value:List.of(password,destination,customer.getEmail(),secret(receipt)))assertFalse(raw.contains(value));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE reference_id=?",Long.class,receipt.challengeId()));
    }
    @Test void exactAddressConfirmationChangesLoginRevokesSessionsAndNotifiesBothAddresses() throws Exception {
        initial();String old=customer.getEmail();UUID previous=sid;var receipt=request();String token=secret(receipt);suppress();var changed=changes.confirm(customer.getId(),sid,receipt.challengeId(),token);assertTrue(changed.requiresLogin());assertEquals(destination,users.findById(customer.getId()).orElseThrow().getEmail());assertTrue(tokens.findBySessionId(previous).orElseThrow().getRevoked());assertEquals(1,count());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_email_confirmations WHERE challenge_id=?",Long.class,receipt.challengeId()));assertEquals(previous,jdbc.queryForObject("SELECT session_id FROM customer_email_confirmation_sessions WHERE challenge_id=?",UUID.class,receipt.challengeId()));
        assertThrows(AuthException.class,()->auth.signin(new SigninRequest(old,password)));login();assertTrue(email.status(customer.getId(),sid).verified());dispatcher.dispatchOnce();dispatcher.dispatchOnce();assertEquals(2,server().getReceivedMessages().length);Set<String> recipients=new HashSet<>();for(var message:server().getReceivedMessages()){recipients.add(message.getAllRecipients()[0].toString());assertEquals("이메일 변경 완료 안내",message.getSubject());String body=(String)message.getContent();for(String value:List.of(token,password,old,destination,browser.id()))assertFalse(body.contains(value));}assertEquals(Set.of(old,destination),recipients);
        var replay=changes.confirm(customer.getId(),sid,receipt.challengeId(),token);assertEquals(changed,replay);assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());assertEquals(1,count());
    }
    @Test void actualSmtpDeliversNewConfirmationAndOldPendingWarning() throws Exception {
        initial();var receipt=request();dispatcher.dispatchOnce();dispatcher.dispatchOnce();assertTrue(server().waitForIncomingEmail(3000,2));String token=null;
        for(var message:server().getReceivedMessages()){String body=(String)message.getContent();if(message.getSubject().equals("새 이메일 주소 확인")){assertEquals(destination,message.getAllRecipients()[0].toString());token=body.substring(body.indexOf("&token=")+7).strip();assertFalse(body.contains(customer.getEmail()));}else{assertEquals("이메일 변경 요청 안내",message.getSubject());assertEquals(customer.getEmail(),message.getAllRecipients()[0].toString());assertFalse(body.contains(destination));assertFalse(body.contains("token="));}}
        assertNotNull(token);assertEquals(secret(receipt),token);assertTrue(changes.confirm(customer.getId(),sid,receipt.challengeId(),token).requiresLogin());
    }
    @Test void changedDestinationCannotReuseSignatureAndOriginalCanRetry() throws Exception {
        initial();var o=options();var p=proof(o);assertThrows(ResponseStatusException.class,()->changes.request(customer.getId(),sid,"different@new-address.invalid",p));unchanged();assertNotNull(changes.request(customer.getId(),sid,destination,p).challengeId());
    }
    @Test void passwordFreshnessAndExistingMfaAreRequired() throws Exception {
        initial();assertThrows(ResponseStatusException.class,()->changes.options(customer.getId(),sid,"wrong",destination));var session=tokens.findBySessionId(sid).orElseThrow();session.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(session);assertThrows(ResponseStatusException.class,this::options);unchanged();
    }
    @Test void passwordOnlyCustomerCannotRequestEmailChange(){assertThrows(ResponseStatusException.class,()->changes.options(customer.getId(),sid,password,"new@address.invalid"));unchanged();}
    @Test void malformedSameAndHeaderInjectionAddressesAreRejected() throws Exception {
        initial();for(String value:List.of(customer.getEmail(),"not-an-email","Name <user@example.test>","x@example.test\r\nBcc: secret@example.test"))assertEquals(400,assertThrows(ResponseStatusException.class,()->changes.options(customer.getId(),sid,password,value)).getStatusCode().value());unchanged();
    }
    @Test void loginChallengeCannotAuthorizeEmailChange() throws Exception {
        initial();var o=factor.authenticationOptions(customer.getId(),sid);var p=new CustomerEmailChangeService.Proof(o.challengeId(),browser.assertion(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true,++counter,null));assertThrows(ResponseStatusException.class,()->changes.request(customer.getId(),sid,destination,p));unchanged();
    }
    @Test void wrongOriginMissingUserVerificationAndExpiredOptionsAreRejected() throws Exception {
        initial();var o=options();var standard=new OperatorMfaService.Options(o.challengeId(),o.publicKey());for(boolean uv:List.of(false,true)){var raw=browser.assertion(standard,uv?"https://evil.test":"http://localhost:3003",uv,++counter,null);assertThrows(ResponseStatusException.class,()->changes.request(customer.getId(),sid,destination,new CustomerEmailChangeService.Proof(o.challengeId(),raw)));}jdbc.update("UPDATE customer_email_change_challenges SET expires_at=clock_timestamp()-interval '1 second' WHERE id=?",o.challengeId());assertThrows(ResponseStatusException.class,()->changes.request(customer.getId(),sid,destination,proof(o)));unchanged();
    }
    @Test void duplicateAndConcurrentRequestReturnOneReceiptAndTwoMails() throws Exception {
        initial();var o=options();var p=proof(o);try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);Callable<CustomerEmailChallenges.Receipt> work=()->{gate.await();return changes.request(customer.getId(),sid,destination,p);};var a=pool.submit(work);var b=pool.submit(work);gate.countDown();var first=a.get(20,TimeUnit.SECONDS);assertEquals(first,b.get(20,TimeUnit.SECONDS));assertEquals(first,changes.request(customer.getId(),sid,destination,p));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE reference_id=?",Long.class,first.challengeId()));}unchanged();
    }
    @Test void wrongTokenForeignSessionAndUnrelatedChallengeCannotConfirm() throws Exception {
        initial();var r=request();assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),sid,r.challengeId(),token()));assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),maker.sid(),r.challengeId(),secret(r)));assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),sid,UUID.randomUUID(),secret(r)));unchanged();
    }
    @Test void currentAddressConfirmationApiCannotApplyPendingNewAddress() throws Exception {
        initial();var r=request();assertThrows(ResponseStatusException.class,()->email.confirm(customer.getId(),sid,r.challengeId(),secret(r)));unchanged();assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_email_confirmations WHERE challenge_id=?",Long.class,r.challengeId()));
    }
    @Test void accountVersionChangeInvalidatesSignedIntent() throws Exception {
        initial();var r=request();var user=users.findById(customer.getId()).orElseThrow();user.setUsername("격리 계정 변경");users.saveAndFlush(user);assertEquals(409,assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),sid,r.challengeId(),secret(r))).getStatusCode().value());assertFalse(outbox.eligible(outbox.open(job(r.challengeId(),CustomerMailOutbox.Kind.EMAIL_CHANGE_VERIFICATION))));unchanged();
    }
    @Test void finalAuditFailureRestoresAddressConfirmationSessionsAndNotices() throws Exception {
        initial();var r=request();String token=secret(r);suppress();long before=jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE user_id=?",Long.class,customer.getId());doThrow(new IllegalStateException("격리 변경 감사 실패")).when(audit).record(any(),eq("CUSTOMER_EMAIL_CHANGED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->changes.confirm(customer.getId(),sid,r.challengeId(),token));reset(audit);unchanged();assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_email_confirmations WHERE challenge_id=?",Long.class,r.challengeId()));assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE user_id=?",Long.class,customer.getId()));assertTrue(changes.confirm(customer.getId(),sid,r.challengeId(),token).requiresLogin());
    }
    @Test void requestReservationFailureRestoresSignatureNonceAndNoMail() throws Exception {
        initial();var o=options();var p=proof(o);doThrow(new IllegalStateException("격리 예약 실패")).when(audit).record(any(),eq("CUSTOMER_MAIL_QUEUED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->changes.request(customer.getId(),sid,destination,p));reset(audit);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_email_change_requests WHERE user_id=?",Long.class,customer.getId()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM customer_email_change_challenges WHERE id=?",Long.class,o.challengeId()));assertNotNull(changes.request(customer.getId(),sid,destination,p).challengeId());
    }
    @Test void simultaneousConfirmationHasOneChange() throws Exception {
        initial();var r=request();String token=secret(r);try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);Callable<Boolean> work=()->{gate.await();try{changes.confirm(customer.getId(),sid,r.challengeId(),token);return true;}catch(ResponseStatusException e){return false;}};var a=pool.submit(work);var b=pool.submit(work);gate.countDown();assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));}assertEquals(1,count());assertEquals(destination,users.findById(customer.getId()).orElseThrow().getEmail());
    }
    @Test void signupAndChangeCannotOwnTheSameAddress() throws Exception {
        initial();var r=request();String token=secret(r);try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var change=pool.submit(()->{gate.await();try{changes.confirm(customer.getId(),sid,r.challengeId(),token);return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}});var signup=pool.submit(()->{gate.await();try{auth.signup(new SignupRequest(destination,"independent-signup-password","격리 신규 고객"));return true;}catch(AuthException e){return false;}});gate.countDown();assertNotEquals(change.get(20,TimeUnit.SECONDS),signup.get(20,TimeUnit.SECONDS));}assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM users WHERE email=?",Long.class,destination));
    }
    @Test void pendingIntentAndCompletionFactsAreImmutable() throws Exception {
        initial();var r=request();changes.confirm(customer.getId(),sid,r.challengeId(),secret(r));for(String table:List.of("customer_email_change_requests","customer_email_changes"))for(String sql:List.of("UPDATE "+table+" SET user_id=user_id","DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(Exception.class,()->jdbc.execute(sql));assertEquals(1,count());
    }
    @Test void actualHttpChangesOnlyWithScopedSignatureAndToken() throws Exception {
        initial();var o=options();var p=proof(o);String raw=mvc.perform(post("/api/customer/security/email/requests").header("Authorization","Bearer "+customerAccess).contentType("application/json").content(json.writeValueAsString(new CustomerEmailChangeController.Request(destination,p)))).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();var r=json.readValue(raw,CustomerEmailChallenges.Receipt.class);assertFalse(raw.contains(secret(r)));mvc.perform(post("/api/customer/security/email/confirmations").header("Authorization","Bearer "+customerAccess).contentType("application/json").content(json.writeValueAsString(new CustomerEmailChangeController.Confirmation(r.challengeId(),secret(r))))).andExpect(status().isOk()).andExpect(jsonPath("$.requiresLogin").value(true));
        mvc.perform(post("/api/customer/security/email/options").header("Authorization","Bearer "+maker.access()).contentType("application/json").content("{}")).andExpect(status().isForbidden());mvc.perform(post("/api/customer/security/email/options").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }
    @Test void fiveInvalidTokensThenRateLimitPreserveOriginalAddress() throws Exception {
        initial();var r=request();for(int i=0;i<5;i++)assertEquals(403,assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),sid,r.challengeId(),token())).getStatusCode().value());assertEquals(429,assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),sid,r.challengeId(),secret(r))).getStatusCode().value());unchanged();
    }
    @Test void newAddressAlreadyOwnedIsRejectedOnlyAfterItsTokenIsProved() throws Exception {
        initial();destination=maker.user().getEmail();var r=request();assertEquals(403,assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),sid,r.challengeId(),token())).getStatusCode().value());assertEquals(409,assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),sid,r.challengeId(),secret(r))).getStatusCode().value());unchanged();
    }
    @Test void serverKeyFailureDoesNotConsumeCustomerFailureBudget() throws Exception {
        initial();doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"격리 키 장애")).when(crypto).fingerprint(eq("customer-email-exact-v1"),anyString());for(int i=0;i<6;i++)assertEquals(503,assertThrows(ResponseStatusException.class,this::options).getStatusCode().value());reset(crypto);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_EMAIL_CHANGE' AND outcome='DENIED'",Long.class,customer.getId()));assertNotNull(options());
    }
    @Test void recentCurrentAddressVerificationSharesChangeMailRateLimit() throws Exception {
        var r=email.request(customer.getId(),sid,UUID.randomUUID());String token=outbox.open(jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,r.challengeId())).token();suppress();email.confirm(customer.getId(),sid,r.challengeId(),token);enroll();var o=options();var p=proof(o);assertEquals(429,assertThrows(ResponseStatusException.class,()->changes.request(customer.getId(),sid,destination,p)).getStatusCode().value());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_email_change_requests WHERE user_id=?",Long.class,customer.getId()));unchanged();
    }
    @Test void expiredPendingTokenCannotChangeAddressOrReadMailPayload() throws Exception {
        initial();UUID id=UUID.randomUUID();String token=token();
        // 만료된 과거 접수 표본을 새로 만든다. 기존 불변 요청의 기한을 변경하지 않는다.
        jdbc.update("INSERT INTO customer_email_challenges(id,user_id,request_key,email_mac,rate_mac,key_id,token_hash,expires_at,created_at) VALUES(?,?,?,?,?,?,?,clock_timestamp()-interval '1 minute',clock_timestamp()-interval '16 minutes')",id,customer.getId(),UUID.randomUUID(),recipients.addressMac(destination),crypto.fingerprint("customer-email-rate-v1",destination.toLowerCase(Locale.ROOT)),crypto.keyId(),sha(id+":"+token));
        jdbc.update("INSERT INTO customer_email_change_requests(id,user_id,session_id,authorization_id,user_version,credential_fingerprint,old_email_mac,new_email_mac,key_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,clock_timestamp()-interval '16 minutes')",id,customer.getId(),UUID.randomUUID(),UUID.randomUUID(),users.findById(customer.getId()).orElseThrow().getVersion(),sha(browser.id()),recipients.addressMac(customer.getEmail()),recipients.addressMac(destination),crypto.keyId());
        assertEquals(410,assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),sid,id,token)).getStatusCode().value());unchanged();
    }
    @Test void revokedOriginalSigningKeyInvalidatesPendingChange() throws Exception {
        initial();var original=browser;var backup=new SoftwareAuthenticator();var options=factor.registrationOptions(customer.getId(),sid,password);factor.register(customer.getId(),sid,options.challengeId(),backup.registration(new OperatorMfaService.Options(options.challengeId(),options.publicKey()),"http://localhost:3003",true));login();var r=request();
        browser=backup;counter=0;login();devices.revoke(customer.getId(),sid,sha(original.id()));login();assertEquals(409,assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),sid,r.challengeId(),secret(r))).getStatusCode().value());unchanged();
    }
    @Test void disabledCustomerSuppressesNewAddressTokenButStillWarnsOldAddress() throws Exception {
        initial();var r=request();var user=users.findById(customer.getId()).orElseThrow();user.setEnabled(false);users.saveAndFlush(user);assertThrows(ResponseStatusException.class,()->changes.confirm(customer.getId(),sid,r.challengeId(),secret(r)));dispatcher.dispatchOnce();dispatcher.dispatchOnce();assertEquals(1,server().getReceivedMessages().length);assertEquals("이메일 변경 요청 안내",server().getReceivedMessages()[0].getSubject());assertEquals(customer.getEmail(),server().getReceivedMessages()[0].getAllRecipients()[0].toString());unchanged();
    }

}
