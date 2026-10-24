package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.security.*;
import dustin.cex.domains.mail.CustomerMailOutbox;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 서명 요청/실제 완료의 본인 조회·동일 시각 순회·비밀 비노출·발송 장애 독립성을 확인한다. */
class CustomerEmailChangeHistoryIntegrationTest extends CustomerEmailChangeTestSupport {
    @Autowired CustomerEmailChangeHistory history;
    void seed(int count,Instant at){
        // 조회 전용 과거 접수 표본이다. 기존 불변 원본을 수정하거나 실제 완료로 꾸미지 않는다.
        for(int i=0;i<count;i++){
            UUID id=UUID.randomUUID();String token=token();
            jdbc.update("INSERT INTO customer_email_challenges(id,user_id,request_key,email_mac,rate_mac,key_id,token_hash,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?)",id,customer.getId(),UUID.randomUUID(),recipients.addressMac(destination),crypto.fingerprint("customer-email-rate-v1",destination.toLowerCase(Locale.ROOT)),crypto.keyId(),sha(id+":"+token),Timestamp.from(at.plusSeconds(900)),Timestamp.from(at));
            jdbc.update("INSERT INTO customer_email_change_requests(id,user_id,session_id,authorization_id,user_version,credential_fingerprint,old_email_mac,new_email_mac,key_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,customer.getId(),UUID.randomUUID(),UUID.randomUUID(),users.findById(customer.getId()).orElseThrow().getVersion(),sha(browser.id()),recipients.addressMac(customer.getEmail()),recipients.addressMac(destination),crypto.keyId(),Timestamp.from(at));
        }
    }
    @Test void emptyHistoryCreatesNoRequestOrCompletion(){var page=history.own(customer.getId(),sid,null);assertTrue(page.items().isEmpty());assertNull(page.next());assertEquals(0,count());}
    @Test void signedRequestIsNotReportedAsCompleted() throws Exception {
        initial();var r=request();var page=history.own(customer.getId(),sid,null);assertEquals(1,page.items().size());var item=page.items().getFirst();assertEquals(r.challengeId(),item.id());assertEquals(r.expiresAt(),item.expiresAt());assertNull(item.completed());assertNull(page.next());assertEquals(0,count());
    }
    @Test void actualCompletionIsLinkedAfterNewLoginAndOldSessionCannotRead() throws Exception {
        initial();var r=request();String old=customerAccess;var changed=changes.confirm(customer.getId(),sid,r.challengeId(),secret(r));mvc.perform(get("/api/customer/security/email-change-history").header("Authorization","Bearer "+old)).andExpect(status().isUnauthorized());login();var page=history.own(customer.getId(),sid,null);assertEquals(1,page.items().size());var item=page.items().getFirst();assertEquals(r.challengeId(),item.id());assertEquals(changed.id(),item.completed().id());assertNotNull(item.completed().changedAt());assertEquals(1,count());
    }
    @Test void sameTimestampRequestsHaveNoPaginationLoss() throws Exception {
        initial();request();seed(29,Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS));var first=history.own(customer.getId(),sid,null);assertEquals(25,first.items().size());assertNotNull(first.next());var second=history.own(customer.getId(),sid,first.next());assertEquals(5,second.items().size());assertNull(second.next());Set<UUID> ids=new HashSet<>();first.items().forEach(item->assertTrue(ids.add(item.id())));second.items().forEach(item->assertTrue(ids.add(item.id())));assertEquals(30,ids.size());
    }
    @Test void exactPageDoesNotProduceEmptyNextPage() throws Exception {initial();request();seed(24,Instant.now().minusSeconds(1));var page=history.own(customer.getId(),sid,null);assertEquals(25,page.items().size());assertNull(page.next());}
    @Test void partialAndFutureCursorAreRejectedAndAudited() throws Exception {
        initial();for(var cursor:List.of(new CustomerEmailChangeHistory.Cursor(null,UUID.randomUUID()),new CustomerEmailChangeHistory.Cursor(Instant.now(),null),new CustomerEmailChangeHistory.Cursor(Instant.now().plusSeconds(600),UUID.randomUUID())))assertEquals(400,assertThrows(ResponseStatusException.class,()->history.own(customer.getId(),sid,cursor)).getStatusCode().value());
        mvc.perform(get("/api/customer/security/email-change-history").header("Authorization","Bearer "+customerAccess).param("afterId",UUID.randomUUID().toString())).andExpect(status().isBadRequest());assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_EMAIL_CHANGE_HISTORY' AND outcome='DENIED'",Long.class,customer.getId()));
    }
    @Test void foreignOperatorAndPendingSessionsCannotRead() throws Exception {
        initial();request();assertThrows(ResponseStatusException.class,()->history.own(customer.getId(),maker.sid(),null));assertThrows(ResponseStatusException.class,()->history.own(maker.user().getId(),maker.sid(),null));mvc.perform(get("/api/customer/security/email-change-history").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());var r=auth.signin(new SigninRequest(customer.getEmail(),password));sid=UUID.fromString(jwt.verifyAccessToken(r.getAccessToken()).get("sid",String.class));customerAccess=r.getAccessToken();assertThrows(ResponseStatusException.class,()->history.own(customer.getId(),sid,null));mvc.perform(get("/api/customer/security/email-change-history").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
    }
    @Test void auditFailureDoesNotReturnHistory() throws Exception {initial();request();doThrow(new IllegalStateException("격리 조회 감사 실패")).when(audit).record(any(),eq("CUSTOMER_EMAIL_CHANGE_HISTORY_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->history.own(customer.getId(),sid,null));}
    @Test void httpExposesOnlyMetadataWithoutFreshSigningPrompt() throws Exception {
        initial();var r=request();var session=tokens.findBySessionId(sid).orElseThrow();session.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(session);String body=mvc.perform(get("/api/customer/security/email-change-history").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andExpect(jsonPath("$.items.length()").value(1)).andReturn().getResponse().getContentAsString();for(String value:List.of(secret(r),password,destination,customer.getEmail(),sid.toString(),browser.id(),"newEmail","oldEmail","fingerprint","sessionId","publicKey","tokenHash","userId"))assertFalse(body.contains(value));
    }
    @Test void mailKeyFailureDoesNotHideSignedRequestFacts() throws Exception {
        initial();var r=request();suppress();
        // 보존 기한 안의 암호문은 DB가 삭제를 거부해야 한다. 원본을 유지한 채 발송 키 장애만 주입한다.
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM customer_mail_payloads WHERE job_id=?",job(r.challengeId(),CustomerMailOutbox.Kind.EMAIL_CHANGE_VERIFICATION)));
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"격리 발송 키 장애")).when(crypto).fingerprint(anyString(),anyString());
        try{assertEquals(r.challengeId(),history.own(customer.getId(),sid,null).items().getFirst().id());}finally{reset(crypto);}
    }
    @Test void anotherCustomersActualRequestIsNeverIncluded() throws Exception {
        initial();var mine=request();var original=customer;UUID originalSid=sid;String originalAccess=customerAccess;
        customer=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@other-customer.invalid").passwordHash(hash).build());var token=tokens.saveAndFlush(RefreshToken.builder().userId(customer.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());sid=token.getSessionId();initial();var other=request();
        customer=original;sid=originalSid;customerAccess=originalAccess;var page=history.own(customer.getId(),sid,null);assertEquals(List.of(mine.challengeId()),page.items().stream().map(CustomerEmailChangeHistory.Entry::id).toList());assertFalse(page.items().stream().anyMatch(item->item.id().equals(other.challengeId())));
    }
}
