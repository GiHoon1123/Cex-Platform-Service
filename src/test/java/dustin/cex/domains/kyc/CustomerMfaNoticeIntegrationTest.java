package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.security.*;
import dustin.cex.domains.mail.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 키 변경·불변 원본·보안 통지 예약·SMTP·본인 조회와 실패 원자성을 함께 검증한다. */
class CustomerMfaNoticeIntegrationTest extends CustomerMailTestSupport {
    @Autowired CustomerMfaService factor;@Autowired CustomerDeviceService devices;@Autowired CustomerMfaHistory history;
    @Autowired CustomerEmailVerification email;@Autowired CustomerServiceMail preferences;@Autowired CustomerMailRetryService retries;
    record Key(SoftwareAuthenticator browser,UUID change){}
    UUID job(UUID ref){return jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,ref);}
    void verifyEmail(){var r=email.request(customer.getId(),sid,UUID.randomUUID());String token=outbox.open(job(r.challengeId())).token();outbox.finish(outbox.claim(),"SUPPRESSED");email.confirm(customer.getId(),sid,r.challengeId(),token);}
    Key add() throws Exception {var key=new SoftwareAuthenticator();var o=factor.registrationOptions(customer.getId(),sid,"security-test-password");var r=factor.register(customer.getId(),sid,o.challengeId(),key.registration(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true));return new Key(key,r.id());}
    void login(Key key,long count) throws Exception {var r=auth.signin(new SigninRequest(customer.getEmail(),"security-test-password"));sid=UUID.fromString(jwt.verifyAccessToken(r.getAccessToken()).get("sid",String.class));var o=factor.authenticationOptions(customer.getId(),sid);var strong=factor.authenticate(customer.getId(),sid,o.challengeId(),key.browser().assertion(new OperatorMfaService.Options(o.challengeId(),o.publicKey()),"http://localhost:3003",true,count,null));sid=UUID.fromString(jwt.verifyAccessToken(strong.accessToken()).get("sid",String.class));customerAccess=strong.accessToken();}
    void suppress(){for(int i=0;i<20;i++){var lease=outbox.claim();if(lease==null)return;outbox.finish(lease,"SUPPRESSED");}throw new IllegalStateException("격리 예약 정리 한도 초과");}
    @Test void verifiedAddressGetsFixedSecurityNoticeWithoutServiceOptIn() throws Exception {
        assertFalse(preferences.own(customer.getId(),sid).enabled());verifyEmail();var key=add();dispatcher.dispatchOnce();
        assertEquals("SMTP_ACCEPTED",outbox.state(job(key.change())).status());assertTrue(server().waitForIncomingEmail(3000,1));var message=server().getReceivedMessages()[0];assertEquals("계정 보안키 변경 안내",message.getSubject());String body=(String)message.getContent();
        for(String secret:List.of("security-test-password",key.browser().id(),sha(key.browser().id()),"테스트 보안키","token=",sid.toString()))assertFalse(body.contains(secret));assertTrue(body.contains("/account/security"));
    }
    @Test void disabledCustomerStillReceivesSecurityWarning() throws Exception {
        verifyEmail();var key=add();var user=users.findById(customer.getId()).orElseThrow();user.setEnabled(false);users.saveAndFlush(user);dispatcher.dispatchOnce();assertEquals("SMTP_ACCEPTED",outbox.state(job(key.change())).status());
    }
    @Test void changedAddressSuppressesOriginalRecipient() throws Exception {
        verifyEmail();var key=add();var user=users.findById(customer.getId()).orElseThrow();user.setEmail(UUID.randomUUID()+"@changed.invalid");users.saveAndFlush(user);dispatcher.dispatchOnce();assertEquals("SUPPRESSED",outbox.state(job(key.change())).status());assertEquals(0,server().getReceivedMessages().length);
    }
    @Test void reservationFailureRollsBackKeyFactSessionsAndMail() throws Exception {
        verifyEmail();var browser=new SoftwareAuthenticator();var options=factor.registrationOptions(customer.getId(),sid,"security-test-password");var proof=browser.registration(new OperatorMfaService.Options(options.challengeId(),options.publicKey()),"http://localhost:3003",true);
        doThrow(new IllegalStateException("격리 예약 감사 실패")).when(audit).record(any(),eq("CUSTOMER_MAIL_QUEUED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->factor.register(customer.getId(),sid,options.challengeId(),proof));reset(audit);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,browser.id()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_mfa_key_changes WHERE user_id=?",Long.class,customer.getId()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_mfa_enrollments WHERE user_id=?",Long.class,customer.getId()));assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE user_id=? AND kind='MFA_CHANGED'",Long.class,customer.getId()));assertTrue(factor.register(customer.getId(),sid,options.challengeId(),proof).requiresLogin());
    }
    @Test void revokeAuditFailureAlsoRollsBackQueuedSecurityWarning() throws Exception {
        verifyEmail();var a=add();login(a,1);var b=add();login(b,1);suppress();
        doThrow(new IllegalStateException("격리 폐기 감사 실패")).when(audit).record(any(),eq("CUSTOMER_MFA_KEY_REVOKED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->devices.revoke(customer.getId(),sid,sha(a.browser().id())));reset(audit);
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE user_id=? AND kind='MFA_CHANGED'",Long.class,customer.getId()));assertEquals(2,devices.own(customer.getId(),sid).size());assertFalse(tokens.findBySessionId(sid).orElseThrow().getRevoked());
    }
    @Test void ownHistoryIncludesRemovedKeyAndHttpRequiresVerifiedCustomer() throws Exception {
        verifyEmail();var a=add();login(a,1);var b=add();login(b,1);suppress();devices.revoke(customer.getId(),sid,sha(a.browser().id()));dispatcher.dispatchOnce();assertEquals(1,server().getReceivedMessages().length);login(b,2);
        var page=history.own(customer.getId(),sid,null);assertEquals(3,page.items().size());assertEquals(CustomerMfaKeyChanges.Kind.REVOKED,page.items().getFirst().kind());assertEquals(sha(a.browser().id()),page.items().getFirst().fingerprint());
        mvc.perform(get("/api/auth/customer-mfa/changes").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/auth/customer-mfa/changes").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());assertThrows(ResponseStatusException.class,()->history.own(customer.getId(),maker.sid(),null));String raw=json.writeValueAsString(page);for(String field:List.of("sessionId","userId","publicKey",a.browser().id(),customer.getEmail()))assertFalse(raw.contains(field));
    }
    @Test void historyCursorHandlesSameTimeAndRejectsInvalidPositions() throws Exception {
        verifyEmail();var a=add();login(a,1);Instant at=Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        // 테스트 전용 과거 사실 fixture다. 기존 불변 행을 수정하거나 트리거를 끄지 않는다.
        for(int i=0;i<29;i++)jdbc.update("INSERT INTO customer_mfa_key_changes(id,user_id,session_id,kind,credential_fingerprint,created_at) VALUES(?,?,?,'ADDED',?,?)",UUID.randomUUID(),customer.getId(),UUID.randomUUID(),sha(UUID.randomUUID().toString()),Timestamp.from(at));
        var first=history.own(customer.getId(),sid,null);var second=history.own(customer.getId(),sid,first.next());var ids=new HashSet<UUID>();first.items().forEach(x->assertTrue(ids.add(x.id())));second.items().forEach(x->assertTrue(ids.add(x.id())));assertEquals(30,ids.size());assertNull(second.next());
        for(var c:List.of(new CustomerMfaHistory.Cursor(null,UUID.randomUUID()),new CustomerMfaHistory.Cursor(Instant.now().plusSeconds(600),UUID.randomUUID())))assertEquals(400,assertThrows(ResponseStatusException.class,()->history.own(customer.getId(),sid,c)).getStatusCode().value());
    }
    @Test void historyAuditFailureDoesNotReturnFacts() throws Exception {
        verifyEmail();var a=add();login(a,1);doThrow(new IllegalStateException("격리 조회 감사 실패")).when(audit).record(any(),eq("CUSTOMER_MFA_HISTORY_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->history.own(customer.getId(),sid,null));
    }
    @Test void exhaustedKeyWarningCannotUseOperatorExtraRetry() throws Exception {
        verifyEmail();var a=add();UUID job=job(a.change());for(int i=0;i<5;i++){outbox.finish(outbox.claim(),"SMTP_UNKNOWN");jdbc.update("UPDATE customer_mail_delivery SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE job_id=?",job);}
        assertEquals("EXHAUSTED",outbox.state(job).status());assertThrows(ResponseStatusException.class,()->retries.propose(maker.user().getId(),maker.sid(),job,UUID.randomUUID(),checker.user().getId()));
    }

    @Test void unverifiedChangedAddressDoesNotPreventLostKeyRevocation() throws Exception {
        verifyEmail();var a=add();login(a,1);var b=add();login(b,1);suppress();
        var user=users.findById(customer.getId()).orElseThrow();user.setEmail(UUID.randomUUID()+"@unverified.invalid");users.saveAndFlush(user);customer=user;
        // 현재 미검증 주소에는 발송하지 않는다. 살아 있는 다른 키로 분실 키를 제거하는 업무는 계속한다.
        devices.revoke(customer.getId(),sid,sha(a.browser().id()));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE user_id=? AND kind='MFA_CHANGED'",Long.class,customer.getId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_credentials WHERE credential_id=?",Long.class,a.browser().id()));
        login(b,2);assertEquals(3,history.own(customer.getId(),sid,null).items().size());assertEquals(0,server().getReceivedMessages().length);
    }
}
