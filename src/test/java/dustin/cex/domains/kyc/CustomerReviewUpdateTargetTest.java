package dustin.cex.domains.kyc;
import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.domains.edd.CustomerReviewUpdateTarget;
import dustin.cex.domains.edd.CustomerReviewUpdateTarget.State;
import java.time.*;
import org.junit.jupiter.api.Test;

/** 실제 대기 없이 후속 목표·과거 지연·최종 종료·시각 경계를 검증한다. */
class CustomerReviewUpdateTargetTest {
    final Instant start=Instant.parse("2026-10-23T00:00:00Z"),due=start.plusSeconds(86400);
    @Test void unansweredUsesReceiptAnchor(){assertEquals(due,CustomerReviewUpdateTarget.assess(start,null,null,0,false,start).targetAt());}
    @Test void waitingBeforeBoundary(){assertEquals(State.WAITING,CustomerReviewUpdateTarget.assess(start,null,null,0,false,due.minusNanos(1)).state());}
    @Test void exactBoundaryIsOverdue(){assertEquals(State.OVERDUE,CustomerReviewUpdateTarget.assess(start,null,null,0,false,due).state());}
    @Test void updateMovesOnlyNextTarget(){var last=due.plusSeconds(1);var a=CustomerReviewUpdateTarget.assess(start,last,null,1,false,last);assertEquals(last.plusSeconds(86400),a.targetAt());assertEquals(1,a.breachedGaps());assertEquals(State.WAITING,a.state());}
    @Test void newOverdueDoesNotChangePastGapCount(){assertEquals(2,CustomerReviewUpdateTarget.assess(start,due,null,2,false,due.plusSeconds(86400)).breachedGaps());}
    @Test void finalApprovalStopsCurrentTimerButPreservesPastGaps(){var a=CustomerReviewUpdateTarget.assess(start,due,due.plusSeconds(1),1,false,due.plusSeconds(10));assertEquals(State.CLOSED,a.state());assertEquals(1,a.breachedGaps());}
    @Test void finalWithoutStatusReplyIsClosed(){assertEquals(State.CLOSED,CustomerReviewUpdateTarget.assess(start,null,due,0,false,due).state());}
    @Test void reversedLogicalTimelineRequiresReview(){assertEquals(State.TIME_REVIEW_REQUIRED,CustomerReviewUpdateTarget.assess(start,due,null,0,true,due).state());}
    @Test void futurePublicTimeRequiresReview(){assertEquals(State.TIME_REVIEW_REQUIRED,CustomerReviewUpdateTarget.assess(start,due,null,0,false,start).state());}
    @Test void finalBeforeLastReplyRequiresReview(){assertEquals(State.TIME_REVIEW_REQUIRED,CustomerReviewUpdateTarget.assess(start,due,start,0,false,due).state());}
    @Test void invalidInputsAreRejected(){assertThrows(IllegalArgumentException.class,()->CustomerReviewUpdateTarget.assess(null,null,null,0,false,start));assertThrows(IllegalArgumentException.class,()->CustomerReviewUpdateTarget.assess(start,null,null,-1,false,start));}
}
