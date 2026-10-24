package dustin.cex.domains.kyc;
import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.domains.edd.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 실제 불변 회신과 SQL 윈도 집계, 내부 메모/고객 확인의 의미를 검증한다. */
class CustomerUpdateTimelineIntegrationTest extends CustomerFinalTestSupport {
    @Autowired CustomerReviewUpdateTimeline timeline;@Autowired CustomerWorkQueueService work;
    CustomerReviewUpdateTarget.Assessment assess(Instant received,Instant finalized){return timeline.assess(receipt.id(),received,finalized,Instant.now());}
    @Test void actualPublishedReplyStartsNextTarget(){var a=assess(receipt.receivedAt(),null);assertEquals(reply.observedAt(),a.latestPublicAt());assertEquals(reply.observedAt().plusSeconds(86400),a.targetAt());assertEquals(0,a.breachedGaps());}
    @Test void realSqlGapCountPersistsAfterLaterReply(){var original=assess(receipt.receivedAt().minusSeconds(172800),null);assertEquals(1,original.breachedGaps());var n=notes.record(maker.user().getId(),maker.sid(),receipt.id(),UUID.randomUUID(),new CustomerReviewNoteService.Facts(CustomerReviewNoteService.Progress.INVESTIGATING,"후속 회신의 실제 근거"));var second=replies.record(maker.user().getId(),maker.sid(),receipt.id(),UUID.randomUUID(),n.id());var a=assess(receipt.receivedAt().minusSeconds(172800),null);assertEquals(1,a.breachedGaps());assertEquals(second.observedAt(),a.latestPublicAt());}
    @Test void noteAndViewDoNotRestartPublicTimer(){var before=assess(receipt.receivedAt(),null);requests.inspect(maker.user().getId(),maker.sid(),receipt.id());notes.record(maker.user().getId(),maker.sid(),receipt.id(),UUID.randomUUID(),new CustomerReviewNoteService.Facts(CustomerReviewNoteService.Progress.INVESTIGATING,"비공개 후속 메모"));assertEquals(before,assess(receipt.receivedAt(),null));}
    @Test void finalAndAckKeepClosedTimerAndHistory() throws Exception {var r=approvedFinal();var finalAt=finals.ownFinal(customer.getId(),sid,receipt.id()).decidedAt();var before=assess(receipt.receivedAt(),finalAt);finals.acknowledge(customer.getId(),sid,receipt.id(),r.payload().id(),UUID.randomUUID());assertEquals(CustomerReviewUpdateTarget.State.CLOSED,before.state());assertEquals(before,assess(receipt.receivedAt(),finalAt));}
    @Test void sqlDetectsFinalBeforeLogicalReply(){assertEquals(CustomerReviewUpdateTarget.State.TIME_REVIEW_REQUIRED,assess(receipt.receivedAt(),receipt.receivedAt()).state());}
    @Test void privateQueueIncludesSameActualAssessment(){CustomerWorkQueueService.Cursor cursor=null;do{var page=work.queue(maker.user().getId(),maker.sid(),cursor);for(var i:page.items())if(i.requestId().equals(receipt.id())){assertEquals(assess(receipt.receivedAt(),null),i.updateTarget());return;}cursor=page.next();}while(cursor!=null);fail("배정 접수를 찾지 못했습니다");}
}
