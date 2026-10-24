package dustin.cex.domains.kyc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.edd.*;
import dustin.cex.domains.auth.model.entity.UserRole;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** 고객 공개·명시적 확인·응답 유실·감사/경합과 개인 목록의 상태 구분을 검증한다. */
class CustomerFinalReceiptIntegrationTest extends CustomerFinalTestSupport {
    @Autowired CustomerWorkQueueService work;
    CustomerFinalReviewService.FinalReceipt own(){return finals.ownFinal(customer.getId(),sid,receipt.id());}
    CustomerFinalReviewService.Acknowledgment ack(UUID id,UUID key){return finals.acknowledge(customer.getId(),sid,receipt.id(),id,key);}
    long ackCount(){return jdbc.queryForObject("SELECT count(*) FROM customer_review_final_acknowledgments WHERE user_id=?",Long.class,customer.getId());}
    @Test void pendingAndRejectedDraftsNeverReachCustomer() throws Exception {var r=draft();assertThrows(ResponseStatusException.class,this::own);finalDecide(r,false,finalProof(r,false));assertThrows(ResponseStatusException.class,this::own);assertEquals(0,ackCount());}
    @Test void approvedCustomerGetsExactPublicTextWithoutInternalMaterial() throws Exception {var r=approvedFinal();var result=own();assertEquals(r.payload().id(),result.finalId());assertEquals(explanation().text(),result.explanation());assertEquals(CustomerReviewReplyService.Status.EXIT_INITIATION_ACTIVE,result.observedStatus());String raw=json.writeValueAsString(result);for(String forbidden:List.of("기존 담당자의 비공개 처리 기록","reasonMac","custodyStamp","requester","noteId"))assertFalse(raw.contains(forbidden));assertFalse(result.toString().contains(explanation().text()));assertEquals(1,active());blocked();}
    @Test void sameKeyCannotAcknowledgeAnotherOwnedFinal() throws Exception {
        var first=approvedFinal();UUID key=UUID.randomUUID();ack(first.payload().id(),key);
        var second=requests.submit(customer.getId(),sid,UUID.randomUUID(),new CustomerExitReviewService.Submission("별도 접수의 추가 검토 요청"));requests.inspect(maker.user().getId(),maker.sid(),second.id());
        var n=notes.record(maker.user().getId(),maker.sid(),second.id(),UUID.randomUUID(),new CustomerReviewNoteService.Facts(CustomerReviewNoteService.Progress.INVESTIGATING,"별도 고객 접수의 내부 근거"));
        var r=finals.propose(maker.user().getId(),maker.sid(),second.id(),UUID.randomUUID(),n.id(),explanation());finalDecide(r,true,finalProof(r,true));
        assertThrows(ResponseStatusException.class,()->finals.acknowledge(customer.getId(),sid,second.id(),r.payload().id(),key));assertEquals(1,ackCount());
    }
    @Test void repeatedGetsAfterApprovalDoNotCreateAcknowledgment() throws Exception {approvedFinal();assertNull(own().acknowledgedAt());assertNull(own().acknowledgedAt());assertEquals(0,ackCount());}
    @Test void explicitAckReplaysOnlySameKeyAndResult() throws Exception {var r=approvedFinal();UUID key=UUID.randomUUID();var a=ack(r.payload().id(),key);assertEquals(a,ack(r.payload().id(),key));assertEquals(a.acknowledgedAt(),own().acknowledgedAt());assertEquals(1,ackCount());assertThrows(ResponseStatusException.class,()->ack(r.payload().id(),UUID.randomUUID()));assertThrows(ResponseStatusException.class,()->ack(UUID.randomUUID(),key));}
    @Test void wrongCustomerAndOperatorCannotReadOrAcknowledge() throws Exception {var r=approvedFinal();var otherUser=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());var otherToken=tokens.saveAndFlush(dustin.cex.domains.auth.model.entity.RefreshToken.builder().userId(otherUser.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(java.time.LocalDateTime.now().plusHours(1)).build());assertThrows(ResponseStatusException.class,()->finals.ownFinal(otherUser.getId(),otherToken.getSessionId(),receipt.id()));assertThrows(ResponseStatusException.class,()->finals.acknowledge(otherUser.getId(),otherToken.getSessionId(),receipt.id(),r.payload().id(),UUID.randomUUID()));assertThrows(ResponseStatusException.class,()->finals.ownFinal(maker.user().getId(),maker.sid(),receipt.id()));}
    @Test void foreignOrRevokedCustomerSessionCannotRead() throws Exception {approvedFinal();assertThrows(ResponseStatusException.class,()->finals.ownFinal(customer.getId(),maker.sid(),receipt.id()));var t=tokens.findBySessionId(sid).orElseThrow();t.setRevoked(true);tokens.saveAndFlush(t);assertThrows(ResponseStatusException.class,this::own);}
    @Test void provideAuditFailureReturnsNoPublicText() throws Exception {approvedFinal();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_FINAL_PROVIDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::own);reset(audit);assertEquals(explanation().text(),own().explanation());}
    @Test void ackAuditFailureRollsBackAndSameKeyRetries() throws Exception {var r=approvedFinal();UUID key=UUID.randomUUID();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_FINAL_ACKNOWLEDGED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->ack(r.payload().id(),key));reset(audit);assertEquals(0,ackCount());assertNotNull(ack(r.payload().id(),key));}
    @Test void laterFinancialRescissionDoesNotRewriteHistoricalAnswer() throws Exception {approvedFinal();var before=own();approveWithdrawal(withdraw(UUID.randomUUID()));assertEquals(before,own());assertEquals(0,active());}
    @Test void laterCustodyTransferDoesNotRewritePublicAnswer() throws Exception {approvedFinal();var before=own();transfer();assertEquals(before,own());}
    CustomerWorkQueueService.Item workItem(){
        // 같은 DB에 보존한 다른 테스트 이력을 지우지 않고 실제 커서 계약으로 내 접수를 찾는다.
        CustomerWorkQueueService.Cursor cursor=null;do{var page=work.queue(maker.user().getId(),maker.sid(),cursor);for(var i:page.items())if(i.requestId().equals(receipt.id()))return i;cursor=page.next();}while(cursor!=null);throw new AssertionError("배정 접수를 찾지 못했습니다");
    }
    @Test void queueSeparatesFinalApprovalFromCustomerAcknowledgment() throws Exception {var r=approvedFinal();var before=workItem();assertEquals(r.payload().id(),before.finalReviewId());assertNotNull(before.finalDecidedAt());assertNull(before.customerAcknowledgedAt());assertFalse(before.hasUnreflectedNote());ack(r.payload().id(),UUID.randomUUID());var after=workItem();assertNotNull(after.customerAcknowledgedAt());assertEquals(before.finalDecidedAt(),after.finalDecidedAt());}
    @Test void concurrentSameKeyAckCommitsOneReceipt() throws Exception {var r=approvedFinal();UUID key=UUID.randomUUID();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->{start.await();return ack(r.payload().id(),key);});var b=pool.submit(()->{start.await();return ack(r.payload().id(),key);});start.countDown();assertEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertEquals(1,ackCount());}finally{pool.shutdownNow();}}
    @Test void ackHistoryRejectsUpdateDeleteAndTruncate() throws Exception {var r=approvedFinal();ack(r.payload().id(),UUID.randomUUID());for(String command:List.of("UPDATE customer_review_final_acknowledgments SET id=id","DELETE FROM customer_review_final_acknowledgments","TRUNCATE customer_review_final_acknowledgments"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(command));}
    @Test void httpOwnFinalAndExplicitAckAreNoStoreAndCustomerOnly() throws Exception {var r=approvedFinal();String path="/api/customer/relationship/review-requests/"+receipt.id()+"/final";mvc.perform(get(path).header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));mvc.perform(post(path+"/acknowledgments").header("Authorization","Bearer "+customerAccess).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(json.writeValueAsString(Map.of("finalId",r.payload().id())))).andExpect(status().isOk());mvc.perform(get(path).header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());mvc.perform(get(path)).andExpect(status().isUnauthorized());}
}
