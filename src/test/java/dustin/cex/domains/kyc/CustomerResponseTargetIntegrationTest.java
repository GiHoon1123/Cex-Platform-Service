package dustin.cex.domains.kyc;
import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.domains.edd.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 실제 공개 회신/최종 승인과 내부 기록·고객 확인의 의미를 구분한다. */
class CustomerResponseTargetIntegrationTest extends CustomerFinalTestSupport {
    @Autowired CustomerWorkQueueService work;
    CustomerWorkQueueService.Item item(UUID id){CustomerWorkQueueService.Cursor cursor=null;do{var page=work.queue(maker.user().getId(),maker.sid(),cursor);for(var i:page.items())if(i.requestId().equals(id))return i;cursor=page.next();}while(cursor!=null);throw new AssertionError("접수를 찾지 못했습니다");}
    @Test void actualPublicReplyIsResponseButNotFinalResolution(){var i=item(receipt.id());assertEquals(CustomerReviewResponseTarget.State.RESPONDED_ON_TIME,i.responseTarget().state());assertNotNull(i.responseTarget().firstPublicResponseAt());assertNull(i.finalReviewId());assertEquals(1,active());}
    @Test void reasonViewAndInternalNoteDoNotCountAsPublicResponse(){var r=requests.submit(customer.getId(),sid,UUID.randomUUID(),new CustomerExitReviewService.Submission("새 고객 요청"));requests.inspect(maker.user().getId(),maker.sid(),r.id());notes.record(maker.user().getId(),maker.sid(),r.id(),UUID.randomUUID(),new CustomerReviewNoteService.Facts(CustomerReviewNoteService.Progress.INVESTIGATING,"비공개 내부 기록"));assertEquals(CustomerReviewResponseTarget.State.WAITING,item(r.id()).responseTarget().state());assertNull(item(r.id()).responseTarget().firstPublicResponseAt());}
    @Test void laterFinalApprovalAndCustomerAckDoNotResetFirstPublicTime() throws Exception {var before=item(receipt.id()).responseTarget();var r=approvedFinal();finals.acknowledge(customer.getId(),sid,receipt.id(),r.payload().id(),UUID.randomUUID());var after=item(receipt.id()).responseTarget();assertEquals(before.firstPublicResponseAt(),after.firstPublicResponseAt());assertEquals(before.targetAt(),after.targetAt());}
    @Test void custodyTransferDoesNotResetOriginalTarget() throws Exception {var before=item(receipt.id()).responseTarget();transfer();CustomerWorkQueueService.Cursor cursor=null;CustomerWorkQueueService.Item found=null;do{var page=work.queue(nextPrimary.user().getId(),nextPrimary.sid(),cursor);for(var i:page.items())if(i.requestId().equals(receipt.id()))found=i;cursor=page.next();}while(cursor!=null&&found==null);assertNotNull(found);assertEquals(before,found.responseTarget());}
}
