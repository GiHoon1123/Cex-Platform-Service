package dustin.cex.domains.kyc;
import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.domains.edd.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 실제 감독 지표와 지표 간 독립성을 검증한다. 오래된 시각은 원본을 바꾸지 않고 고정 값으로 평가한다. */
class CustomerOpenCaseIntegrationTest extends CustomerFinalTestSupport {
    CustomerReviewSupervisionService.Item current(){return supervision.queue(independent.user().getId(),independent.sid(),null).items().stream().filter(i->i.requestId().equals(receipt.id())).findFirst().orElseGet(()->find());}
    CustomerReviewSupervisionService.Item find(){CustomerReviewSupervisionService.Cursor cursor=null;do{var page=supervision.queue(independent.user().getId(),independent.sid(),cursor);for(var i:page.items())if(i.requestId().equals(receipt.id()))return i;cursor=page.next();}while(cursor!=null);throw new AssertionError("접수를 찾지 못했습니다");}
    @Test void timelyPublicReplyDoesNotBecomeFinal(){var i=current();assertEquals(CustomerReviewResponseTarget.State.RESPONDED_ON_TIME,i.responseTarget().state());assertEquals(CustomerReviewCompletionTarget.State.OPEN_WAITING,i.completionReview().state());assertEquals(CustomerReviewUpdateTarget.State.WAITING,i.updateTarget().state());assertNull(i.finalReviewId());}
    @Test void completionReviewCannotBeHiddenByFreshUpdate(){Instant start=Instant.EPOCH,now=start.plusSeconds(172800);var i=new CustomerReviewSupervisionService.Item(UUID.randomUUID(),customer.getId(),original.payload().id(),start,null,List.of(),false,CustomerReviewResponseTarget.assess(start,start.plusSeconds(1),null,now),null,null,null,CustomerReviewUpdateTarget.assess(start,now,null,1,false,now),CustomerReviewCompletionTarget.assess(start,null,now));assertEquals(CustomerReviewUpdateTarget.State.WAITING,i.updateTarget().state());assertTrue(i.requiresAttention());}
    @Test void periodicOverdueIsIndependentlyVisible(){Instant now=Instant.now(),start=now.minusSeconds(86400);var i=new CustomerReviewSupervisionService.Item(UUID.randomUUID(),customer.getId(),original.payload().id(),start,null,List.of(),false,CustomerReviewResponseTarget.assess(start,start,null,now),null,null,null,CustomerReviewUpdateTarget.assess(start,start,null,0,false,now),CustomerReviewCompletionTarget.assess(now,null,now));assertTrue(i.requiresAttention());}
    @Test void finalApprovalStopsBothOpenTimers() throws Exception {var r=approvedFinal();finals.acknowledge(customer.getId(),sid,receipt.id(),r.payload().id(),UUID.randomUUID());var i=current();assertEquals(CustomerReviewCompletionTarget.State.CLOSED,i.completionReview().state());assertEquals(CustomerReviewUpdateTarget.State.CLOSED,i.updateTarget().state());assertFalse(i.requiresAttention());assertEquals(1,active());}
    @Test void transferDoesNotResetOriginalCompletionTarget() throws Exception {var before=current().completionReview();transfer();assertEquals(before,current().completionReview());}
}
