package dustin.cex.domains.kyc;
import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.domains.edd.CustomerReviewCompletionTarget;
import dustin.cex.domains.edd.CustomerReviewCompletionTarget.State;
import java.time.*;
import org.junit.jupiter.api.Test;

/** 금융 판단과 다른 미종결 감독의 고정 시간 경계를 검증한다. */
class CustomerReviewCompletionTargetTest {
    final Instant start=Instant.parse("2026-10-23T00:00:00Z"),due=start.plusSeconds(86400);
    @Test void openBeforeTargetWaits(){assertEquals(State.OPEN_WAITING,CustomerReviewCompletionTarget.assess(start,null,due.minusNanos(1)).state());}
    @Test void exactOpenTargetNeedsReview(){assertEquals(State.OPEN_REVIEW_DUE,CustomerReviewCompletionTarget.assess(start,null,due).state());}
    @Test void repeatedLaterObservationDoesNotMoveTarget(){assertEquals(due,CustomerReviewCompletionTarget.assess(start,null,due.plusSeconds(86400)).targetAt());}
    @Test void exactFinalTargetIsNotExceeded(){var a=CustomerReviewCompletionTarget.assess(start,due,due);assertEquals(State.CLOSED,a.state());assertFalse(a.reviewTargetExceeded());}
    @Test void lateFinalClosesButKeepsExceededFlag(){var a=CustomerReviewCompletionTarget.assess(start,due.plusNanos(1),due.plusSeconds(1));assertEquals(State.CLOSED,a.state());assertTrue(a.reviewTargetExceeded());}
    @Test void observationBeforeReceiptRequiresReview(){assertEquals(State.TIME_REVIEW_REQUIRED,CustomerReviewCompletionTarget.assess(start,null,start.minusNanos(1)).state());}
    @Test void finalBeforeReceiptRequiresReview(){assertEquals(State.TIME_REVIEW_REQUIRED,CustomerReviewCompletionTarget.assess(start,start.minusNanos(1),due).state());}
    @Test void futureFinalRequiresReview(){assertEquals(State.TIME_REVIEW_REQUIRED,CustomerReviewCompletionTarget.assess(start,due,start).state());}
    @Test void requiredTimesCannotBeMissing(){assertThrows(IllegalArgumentException.class,()->CustomerReviewCompletionTarget.assess(null,null,start));assertThrows(IllegalArgumentException.class,()->CustomerReviewCompletionTarget.assess(start,null,null));}
}
