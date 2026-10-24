package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.CustomerDeclaration;
import dustin.cex.domains.edd.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** 실제 배정·논리 기록·공개 회신·26개 접수로 개인 업무 발견과 원문 경계를 검증한다. */
class CustomerWorkQueueIntegrationTest extends CustomerWorkTestSupport {
    @Autowired CustomerWorkQueueService work;
    CustomerWorkQueueService.Cursor beginning;
    @BeforeEach void cursor(){var rows=jdbc.queryForList("SELECT id,created_at FROM customer_exit_review_requests WHERE id<>? ORDER BY created_at DESC,id DESC LIMIT 1",receipt.id());beginning=rows.isEmpty()?null:new CustomerWorkQueueService.Cursor(((Timestamp)rows.getFirst().get("created_at")).toInstant(),(UUID)rows.getFirst().get("id"));}
    CustomerWorkQueueService.Page queue(Account a){return work.queue(a.user().getId(),a.sid(),beginning);}
    CustomerWorkQueueService.Item item(Account a){return queue(a).items().stream().filter(i->i.requestId().equals(receipt.id())).findFirst().orElseThrow();}
    @Test void bothCurrentPeopleSeeMinimalMetadataWithoutCompletingAnything() throws Exception {
        for(var a:List.of(maker,checker)){var i=item(a);assertEquals(customer.getId(),i.userId());assertEquals(0,i.custodyGeneration());assertEquals(1,i.latestNoteSequence());assertEquals(1,i.latestReplyNoteSequence());assertTrue(i.hasReply());assertFalse(i.hasUnreflectedNote());var page=queue(a);assertEquals(CustomerWorkQueueService.SEMANTICS,page.semantics());assertNotNull(page.observedAt());String raw=json.writeValueAsString(page);for(String secret:List.of("고객 사유","비공개 처리 기록","reasonMac","payloadHash","note_cipher","rationale"))assertFalse(raw.contains(secret));}
        assertEquals("RECEIVED",requests.own(customer.getId(),sid,receipt.id()).status());assertEquals(1,active());blocked();
    }
    @Test void newNoteNeedsReplyAndReadingDoesNotClearIndicator() {
        var n=notes.record(maker.user().getId(),maker.sid(),receipt.id(),UUID.randomUUID(),new CustomerReviewNoteService.Facts(CustomerReviewNoteService.Progress.ADDITIONAL_CHECK_REQUIRED,"추가 확인할 비공개 기록"));assertEquals(2,n.sequence());
        assertTrue(item(maker).hasUnreflectedNote());notes.inspect(checker.user().getId(),checker.sid(),n.id());assertTrue(item(checker).hasUnreflectedNote());
        requests.inspect(checker.user().getId(),checker.sid(),receipt.id());replies.record(checker.user().getId(),checker.sid(),receipt.id(),UUID.randomUUID(),n.id());assertFalse(item(maker).hasUnreflectedNote());assertEquals(2,item(maker).latestReplyNoteSequence());assertEquals(1,active());
    }
    @Test void noNoteAndNoReplyRemainDiscoverableWithoutInventedCompletion() {
        var r=requests.submit(customer.getId(),sid,UUID.randomUUID(),new CustomerExitReviewService.Submission("아직 확인하지 않은 요청"));var i=queue(maker).items().stream().filter(x->x.requestId().equals(r.id())).findFirst().orElseThrow();assertEquals(0,i.latestNoteSequence());assertEquals(0,i.latestReplyNoteSequence());assertFalse(i.hasReply());assertFalse(i.hasUnreflectedNote());
    }
    @Test void normalTransferMovesDiscoveryOnlyAfterApproval() throws Exception {
        assertTrue(queue(nextPrimary).items().isEmpty());assertTrue(queue(independent).items().isEmpty());var r=proposal(maker,UUID.randomUUID());assertFalse(queue(maker).items().isEmpty());assertTrue(queue(nextPrimary).items().isEmpty());decideHandoff(independent,r,true,handoffProof(independent,r,true));
        assertTrue(queue(maker).items().isEmpty());assertTrue(queue(checker).items().isEmpty());assertEquals(1,item(nextPrimary).custodyGeneration());assertEquals(1,item(nextChecker).custodyGeneration());assertTrue(queue(independent).items().isEmpty());
    }
    @Test void orphanRecoveryMakesWorkDiscoverableWithoutGivingRequesterBody() throws Exception {
        disable(maker);disable(checker);var outside=account(UserRole.OPERATOR);assertTrue(queue(outside).items().isEmpty());var r=proposal(outside,UUID.randomUUID());decideHandoff(independent,r,true,handoffProof(independent,r,true));assertEquals(1,item(nextPrimary).custodyGeneration());assertTrue(queue(outside).items().isEmpty());assertThrows(ResponseStatusException.class,()->requests.inspect(outside.user().getId(),outside.sid(),receipt.id()));
    }
    @Test void withdrawalAndDisabledCustomerDoNotHideAssignedHistory() throws Exception {approveWithdrawal(withdraw(UUID.randomUUID()));var u=users.findById(customer.getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);assertEquals(receipt.id(),item(maker).requestId());assertEquals(0,active());}
    @ParameterizedTest @ValueSource(strings={"disabled","expired","recovery","role","identity"})
    void authorityAndActualPersonAreCheckedOnEveryRead(String how) {
        var u=users.findById(maker.user().getId()).orElseThrow();switch(how){case "disabled"->u.setEnabled(false);case "expired"->u.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));case "recovery"->u.setOperatorRecoveryRequestId(UUID.randomUUID());case "role"->u.setRole(UserRole.AUDITOR);case "identity"->u.setOperatorPersonId(UUID.randomUUID());default->throw new AssertionError();}users.saveAndFlush(u);
        // 인물 변경 뒤 계정 자체가 인증 가능해도 과거 인물의 배정을 승계하지 못한다.
        if(how.equals("identity"))assertTrue(queue(maker).items().isEmpty());else assertThrows(ResponseStatusException.class,()->queue(maker));
    }
    @Test void staleRevokedAndForeignSessionsCannotRead() {assertThrows(ResponseStatusException.class,()->work.queue(maker.user().getId(),checker.sid(),beginning));var t=tokens.findBySessionId(maker.sid()).orElseThrow();t.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(t);assertThrows(ResponseStatusException.class,()->queue(maker));t.setMfaVerifiedAt(LocalDateTime.now());t.setRevoked(true);tokens.saveAndFlush(t);assertThrows(ResponseStatusException.class,()->queue(maker));}
    @Test void malformedCursorsAreRejected() {for(var c:List.of(new CustomerWorkQueueService.Cursor(null,UUID.randomUUID()),new CustomerWorkQueueService.Cursor(Instant.now(),null),new CustomerWorkQueueService.Cursor(Instant.EPOCH.minusSeconds(1),UUID.randomUUID()),new CustomerWorkQueueService.Cursor(Instant.now().plusSeconds(60),UUID.randomUUID())))assertThrows(ResponseStatusException.class,()->work.queue(maker.user().getId(),maker.sid(),c));}
    @Test void auditFailureReturnsNoMetadataOrReadReceipt() {long before=jdbc.queryForObject("SELECT count(*) FROM customer_exit_review_views WHERE request_id=?",Long.class,receipt.id());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_WORK_QUEUE_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->queue(maker));reset(audit);assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM customer_exit_review_views WHERE request_id=?",Long.class,receipt.id()));assertNotNull(item(maker));}
    @Test void twentySixRealReceiptsAdvanceAcrossEmptyFilteredPageForWorkAndOrphans() throws Exception {
        // 기존 1개 + 실제 새 고객 9명의 25개 접수다. 원문/불변 표를 직접 조작하지 않는다.
        CustomerExitReviewService.Receipt last=null;
        for(int n=0;n<25;n++){if(n%3==0){nextCustomer();initiated();}last=requests.submit(customer.getId(),sid,UUID.randomUUID(),new CustomerExitReviewService.Submission("페이지 경계를 확인할 고객 요청"));}
        var c=handoffs.context(maker.user().getId(),maker.sid(),last.id());var r=handoffs.propose(maker.user().getId(),maker.sid(),last.id(),c.stamp(),nextPrimary.user().getId(),nextChecker.user().getId(),UUID.randomUUID(),"마지막 접수의 담당자 배정");decideHandoff(independent,r,true,handoffProof(independent,r,true));
        var first=queue(nextPrimary);assertEquals(25,first.scanned());assertTrue(first.items().isEmpty());assertNotNull(first.next());var second=work.queue(nextPrimary.user().getId(),nextPrimary.sid(),first.next());assertEquals(1,second.items().size());assertEquals(last.id(),second.items().getFirst().requestId());assertNull(second.next());
        disable(nextPrimary);disable(nextChecker);var start=beginning==null?null:new CustomerWorkCustodyService.Cursor(beginning.receivedAt(),beginning.requestId());var orphanFirst=handoffs.orphans(independent.user().getId(),independent.sid(),start);assertTrue(orphanFirst.items().isEmpty());assertEquals(25,orphanFirst.scanned());assertNotNull(orphanFirst.next());var orphanSecond=handoffs.orphans(independent.user().getId(),independent.sid(),orphanFirst.next());assertEquals(last.id(),orphanSecond.items().getFirst().reviewId());assertNull(orphanSecond.next());
    }
    void nextCustomer(){customer=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());var token=tokens.saveAndFlush(RefreshToken.builder().userId(customer.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());sid=token.getSessionId();customerAccess=jwt.generateAccessToken(customer.getId(),sid);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));}
    @Test void httpRequiresCurrentOperatorAndReturnsNoStore() throws Exception {String path="/api/admin/compliance/customer-review-work";var q=get(path).header("Authorization","Bearer "+maker.access());if(beginning!=null)q.param("afterReceived",beginning.receivedAt().toString()).param("afterRequest",beginning.requestId().toString());String raw=mvc.perform(q).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();assertTrue(raw.contains(receipt.id().toString()));for(String access:List.of(customerAccess,account(UserRole.AUDITOR).access()))mvc.perform(get(path).header("Authorization","Bearer "+access)).andExpect(status().isForbidden());mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).header("Authorization","Bearer "+maker.access()).param("afterRequest",UUID.randomUUID().toString())).andExpect(status().isBadRequest());}
}
