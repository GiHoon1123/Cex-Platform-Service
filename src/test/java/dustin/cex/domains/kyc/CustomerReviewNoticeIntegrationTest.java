package dustin.cex.domains.kyc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.edd.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** 진행/최종 원본의 통합·식별자/커서·본인 경계·구 경로 삭제를 검증한다. */
class CustomerReviewNoticeIntegrationTest extends CustomerFinalTestSupport {
    @Autowired CustomerReviewNoticeService notices;
    CustomerReviewNoticeService.Page list(){return notices.list(customer.getId(),sid,null);}
    @Test void existingStatusReplyIsDiscoverableWithoutFinalApproval(){var page=list();assertEquals(1,page.items().size());var i=page.items().getFirst();assertEquals(CustomerReviewNoticeService.Kind.STATUS,i.kind());assertEquals(reply.id(),i.noticeId());assertEquals(receipt.id(),i.reviewId());assertNull(i.finalAcknowledgedAt());assertEquals(reply.message(),replies.own(customer.getId(),sid,i.reviewId(),0).getFirst().message());}
    @Test void finalAndStatusRemainDistinctAndLatestFirst() throws Exception {var r=approvedFinal();var rows=list().items();assertEquals(2,rows.size());assertEquals(CustomerReviewNoticeService.Kind.FINAL,rows.getFirst().kind());assertEquals(r.payload().id(),rows.getFirst().noticeId());assertEquals(CustomerReviewNoticeService.Kind.STATUS,rows.getLast().kind());assertEquals(reply.id(),rows.getLast().noticeId());}
    @Test void exactCompositeCursorReturnsOlderStatusWithoutFinalDuplicate() throws Exception {approvedFinal();var first=list().items().getFirst();var page=notices.list(customer.getId(),sid,new CustomerReviewNoticeService.Cursor(first.occurredAt(),first.kind(),first.noticeId()));assertEquals(1,page.items().size());assertEquals(reply.id(),page.items().getFirst().noticeId());assertNull(page.next());}
    @Test void repeatedReadsNeverCreateCustomerConfirmation() throws Exception {approvedFinal();assertEquals(list(),list());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_review_final_acknowledgments WHERE user_id=?",Long.class,customer.getId()));}
    @Test void finalConfirmationAppliesOnlyToFinalItem() throws Exception {var r=approvedFinal();finals.acknowledge(customer.getId(),sid,receipt.id(),r.payload().id(),UUID.randomUUID());for(var i:list().items())assertEquals(i.kind()==CustomerReviewNoticeService.Kind.FINAL,i.finalAcknowledgedAt()!=null);assertThrows(ResponseStatusException.class,()->finals.acknowledge(customer.getId(),sid,receipt.id(),reply.id(),UUID.randomUUID()));}
    @Test void pendingOrRejectedFinalDoesNotHideRealStatus() throws Exception {var r=draft();assertEquals(CustomerReviewNoticeService.Kind.STATUS,list().items().getFirst().kind());finalDecide(r,false,finalProof(r,false));assertEquals(1,list().items().size());}
    @Test void metadataContainsNeitherPublicBodyNorInternalMaterial() throws Exception {approvedFinal();String raw=json.writeValueAsString(list());for(String secret:List.of(explanation().text(),reply.message(),"비공개 처리 기록","payloadHash","explanationMac","reasonMac"))assertFalse(raw.contains(secret));}
    @Test void completeCursorIsRequiredIncludingKind(){for(var c:List.of(new CustomerReviewNoticeService.Cursor(Instant.now(),null,UUID.randomUUID()),new CustomerReviewNoticeService.Cursor(null,CustomerReviewNoticeService.Kind.STATUS,UUID.randomUUID()),new CustomerReviewNoticeService.Cursor(Instant.now(),CustomerReviewNoticeService.Kind.FINAL,null)))assertThrows(ResponseStatusException.class,()->notices.list(customer.getId(),sid,c));}
    @Test void otherSessionAndOperatorCannotReadCustomerList(){assertThrows(ResponseStatusException.class,()->notices.list(customer.getId(),maker.sid(),null));assertThrows(ResponseStatusException.class,()->notices.list(maker.user().getId(),maker.sid(),null));}
    @Test void auditFailureReturnsNoStatusOrFinal() throws Exception {approvedFinal();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_REVIEW_NOTICES_LISTED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::list);reset(audit);assertEquals(2,list().items().size());}
    @Test void oldFinalOnlyRouteIsRemovedAndNewRouteIsCustomerOnly() throws Exception {String path="/api/customer/relationship/review-notices";mvc.perform(get(path).header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));mvc.perform(get("/api/customer/relationship/final-notices").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());mvc.perform(get(path).header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).header("Authorization","Bearer "+customerAccess).param("afterKind","UNKNOWN")).andExpect(status().isBadRequest());}
}
