package dustin.cex.domains.kyc;
import dustin.cex.domains.mail.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 고객 재검토·업무별 보안키 승인·메일 소유 확인부터 로컬 SMTP까지 연결한다. */
class CustomerServiceMailIntegrationTest extends CustomerFinalTestSupport {
    @DynamicPropertySource static void mail(DynamicPropertyRegistry r){CustomerMailTestSupport.mailProperties(r);}
    @Autowired CustomerEmailVerification email;
    @Autowired CustomerServiceMail serviceMail;
    @Autowired CustomerMailOutbox outbox;
    @Autowired CustomerMailDispatcher dispatcher;
    @BeforeEach void mailbox() throws Exception {CustomerMailTestSupport.server().purgeEmailFromAllMailboxes();CustomerMailTestSupport.server().setUser(customer.getEmail(),"sandbox-only");}
    @AfterEach void drain(){
        // 직접 만든 테스트 전용 DB의 운영 임대만 만료시킨다. 불변 업무/감사 기록은 삭제하지 않는다.
        reset(audit);jdbc.update("UPDATE customer_mail_delivery SET lease_until=clock_timestamp()-interval '1 second' WHERE status='SENDING'");jdbc.update("UPDATE customer_mail_delivery SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE status='RETRY'");
        for(int i=0;i<100&&jdbc.queryForObject("SELECT count(*) FROM customer_mail_delivery WHERE status IN ('PENDING','RETRY','SENDING')",Long.class)>0;i++){var lease=outbox.claim();if(lease!=null)outbox.finish(lease,"SUPPRESSED");}
    }
    UUID job(UUID reference){return jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=?",UUID.class,reference);}
    void verifyAddress(){var r=email.request(customer.getId(),sid,UUID.randomUUID());String token=outbox.open(job(r.challengeId())).token();var lease=outbox.claim();outbox.finish(lease,"SUPPRESSED");email.confirm(customer.getId(),sid,r.challengeId(),token);}
    void optIn(){verifyAddress();serviceMail.choose(customer.getId(),sid,UUID.randomUUID(),true);}
    dustin.cex.domains.edd.CustomerReviewReplyService.Reply publish(UUID key){return replies.record(maker.user().getId(),maker.sid(),receipt.id(),key,note.id());}
    long notices(){return jdbc.queryForObject("SELECT count(*) FROM customer_mail_jobs WHERE user_id=? AND kind<>'EMAIL_VERIFICATION'",Long.class,customer.getId());}
    @Test void defaultOffAndUnverifiedCannotEnable(){assertFalse(serviceMail.own(customer.getId(),sid).enabled());assertEquals(409,assertThrows(ResponseStatusException.class,()->serviceMail.choose(customer.getId(),sid,UUID.randomUUID(),true)).getStatusCode().value());publish(UUID.randomUUID());assertEquals(0,notices());}
    @Test void verificationAloneDoesNotSubscribe(){verifyAddress();publish(UUID.randomUUID());assertEquals(0,notices());assertFalse(serviceMail.own(customer.getId(),sid).enabled());}
    @Test void actualStatusMailIsGenericAndCannotLeakInternalReason() throws Exception {optIn();var r=publish(UUID.randomUUID());UUID id=job(r.id());dispatcher.dispatchOnce();assertEquals("SMTP_ACCEPTED",outbox.state(id).status());assertTrue(CustomerMailTestSupport.server().waitForIncomingEmail(3000,1));String body=(String)CustomerMailTestSupport.server().getReceivedMessages()[0].getContent();assertTrue(body.contains("https://exchange.example.test/account/notices"));for(String secret:List.of("비공개","인계 후에도","보고","종료","STR",r.message(),customer.getEmail()))assertFalse(body.contains(secret));assertEquals(1,active());blocked();}
    @Test void finalApprovalReservesOneGenericNotice() throws Exception {optIn();var r=approvedFinal();assertEquals(1,notices());UUID id=job(r.payload().id());assertNull(outbox.open(id).token());dispatcher.dispatchOnce();assertEquals("SMTP_ACCEPTED",outbox.state(id).status());assertEquals(1,active());blocked();}
    @Test void draftAndRejectionNeverReserveMail() throws Exception {optIn();var r=draft();assertEquals(0,notices());finalDecide(r,false,finalProof(r,false));assertEquals(0,notices());}
    @Test void sameReplyKeyCannotReserveTwice(){optIn();UUID key=UUID.randomUUID();var r=publish(key);assertEquals(r,publish(key));assertEquals(1,notices());}
    @Test void unsubscribeBeforeSendSuppressesQueuedMail(){optIn();var r=publish(UUID.randomUUID());serviceMail.choose(customer.getId(),sid,UUID.randomUUID(),false);dispatcher.dispatchOnce();assertEquals("SUPPRESSED",outbox.state(job(r.id())).status());assertEquals(0,CustomerMailTestSupport.server().getReceivedMessages().length);assertEquals(1,notices());}
    @Test void disableBeforeSendSuppressesQueuedMail(){optIn();var r=publish(UUID.randomUUID());var u=users.findById(customer.getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);dispatcher.dispatchOnce();assertEquals("SUPPRESSED",outbox.state(job(r.id())).status());}
    @Test void changedAddressInvalidatesConsentAndSuppressesOldTarget(){optIn();var r=publish(UUID.randomUUID());var u=users.findById(customer.getId()).orElseThrow();u.setEmail(UUID.randomUUID()+"@other.invalid");users.saveAndFlush(u);dispatcher.dispatchOnce();assertEquals("SUPPRESSED",outbox.state(job(r.id())).status());
        // 주소 변경 직후의 확인 요청에는 기존 고객별60초 한도가 적용된다. 새 확인 근거를 별도 과거 표본으로 만들지 않는다.
        assertFalse(email.status(customer.getId(),sid).verified());assertFalse(serviceMail.own(customer.getId(),sid).enabled());}
    @Test void replyAuditFailureRollsBackMailAndBusinessThenRetries(){optIn();UUID key=UUID.randomUUID();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_REVIEW_REPLY_RECORDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->publish(key));reset(audit);assertEquals(0,notices());assertNotNull(publish(key));assertEquals(1,notices());}
    @Test void finalDecisionAuditFailureRestoresSignatureAndMailReservation() throws Exception {optIn();var r=draft();var proof=finalProof(r,true);doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_FINAL_DECIDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->finalDecide(r,true,proof));reset(audit);assertEquals(0,notices());assertEquals("APPROVED",finalDecide(r,true,proof).status());assertEquals(1,notices());}
    @Test void preferenceAuditFailureDoesNotChangeCurrentChoice(){verifyAddress();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_SERVICE_MAIL_CHOSEN"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->serviceMail.choose(customer.getId(),sid,UUID.randomUUID(),true));reset(audit);assertFalse(serviceMail.own(customer.getId(),sid).enabled());}
    @Test void preferenceHistoryReplayDoesNotUndoLaterUnsubscribe(){verifyAddress();UUID key=UUID.randomUUID();var old=serviceMail.choose(customer.getId(),sid,key,true);serviceMail.choose(customer.getId(),sid,UUID.randomUUID(),false);assertEquals(old,serviceMail.choose(customer.getId(),sid,key,true));assertFalse(serviceMail.own(customer.getId(),sid).enabled());assertThrows(ResponseStatusException.class,()->serviceMail.choose(customer.getId(),sid,key,false));}
    @Test void preferenceHistoryIsImmutable(){optIn();for(String sql:List.of("UPDATE customer_service_mail_preferences SET enabled=false","DELETE FROM customer_service_mail_preferences","TRUNCATE customer_service_mail_preferences"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));}
    @Test void httpPreferenceRequiresCustomerAndReturnsCurrentEligibility() throws Exception {verifyAddress();mvc.perform(put("/api/customer/email/preferences").header("Authorization","Bearer "+customerAccess).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));mvc.perform(get("/api/customer/email/preferences").header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));mvc.perform(put("/api/customer/email/preferences").header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isForbidden());}
    @Test void browserPutPreflightAcceptsOnlyConfiguredOrigin() throws Exception {mvc.perform(options("/api/customer/email/preferences").header("Origin","http://localhost:3003").header("Access-Control-Request-Method","PUT").header("Access-Control-Request-Headers","authorization,content-type,idempotency-key")).andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://localhost:3003"));mvc.perform(options("/api/customer/email/preferences").header("Origin","https://foreign.example.test").header("Access-Control-Request-Method","PUT")).andExpect(status().isForbidden());}

}
