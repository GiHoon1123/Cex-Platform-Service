package dustin.cex.domains.kyc;
import static org.junit.jupiter.api.Assertions.*;
import dustin.cex.domains.edd.CustomerReviewResponseTarget;
import dustin.cex.domains.edd.CustomerReviewResponseTarget.State;
import java.time.*;
import org.junit.jupiter.api.Test;

/** 고정 시각으로 경계·달력 시간·시계 이상을 검증한다. 실제 시간을 기다리지 않는다. */
class CustomerReviewResponseTargetTest {
    final Instant start=Instant.parse("2026-10-23T00:00:00Z"),due=start.plusSeconds(86400);
    @Test void beforeTargetIsWaiting(){assertEquals(State.WAITING,CustomerReviewResponseTarget.assess(start,null,null,due.minusNanos(1)).state());}
    @Test void exactOpenTargetIsOverdue(){assertEquals(State.OVERDUE,CustomerReviewResponseTarget.assess(start,null,null,due).state());}
    @Test void afterTargetIsOverdue(){assertEquals(State.OVERDUE,CustomerReviewResponseTarget.assess(start,null,null,due.plusNanos(1)).state());}
    @Test void responseExactlyAtTargetMeetsGoal(){assertEquals(State.RESPONDED_ON_TIME,CustomerReviewResponseTarget.assess(start,due,null,due).state());}
    @Test void responseOneNanosecondLateRemainsLate(){assertEquals(State.RESPONDED_LATE,CustomerReviewResponseTarget.assess(start,due.plusNanos(1),null,due.plusSeconds(1)).state());}
    @Test void finalApprovalCountsWithoutStatusReply(){assertEquals(State.RESPONDED_ON_TIME,CustomerReviewResponseTarget.assess(start,null,start.plusSeconds(20),due).state());}
    @Test void earliestStatusReplyWinsEvenAfterLaterFinal(){var result=CustomerReviewResponseTarget.assess(start,start.plusSeconds(30),due.plusSeconds(10),due.plusSeconds(20));assertEquals(start.plusSeconds(30),result.firstPublicResponseAt());assertEquals(State.RESPONDED_ON_TIME,result.state());}
    @Test void earliestFinalApprovalWinsOverLaterStatus(){assertEquals(start.plusSeconds(10),CustomerReviewResponseTarget.assess(start,start.plusSeconds(20),start.plusSeconds(10),due).firstPublicResponseAt());}
    @Test void oldLateResponseDoesNotBecomeTimelyAfterAnotherResponse(){assertEquals(State.RESPONDED_LATE,CustomerReviewResponseTarget.assess(start,due.plusSeconds(1),due.plusSeconds(2),due.plusSeconds(3)).state());}
    @Test void weekendsAndTimezoneDoNotPauseCalendarTarget(){var friday=ZonedDateTime.of(2026,10,23,18,0,0,0,ZoneId.of("Asia/Seoul")).toInstant();assertEquals(friday.plusSeconds(86400),CustomerReviewResponseTarget.assess(friday,null,null,friday).targetAt());}
    @Test void eventBeforeReceiptRequiresTimeReview(){assertEquals(State.TIME_REVIEW_REQUIRED,CustomerReviewResponseTarget.assess(start,start.minusNanos(1),null,due).state());}
    @Test void futureEventRequiresTimeReview(){assertEquals(State.TIME_REVIEW_REQUIRED,CustomerReviewResponseTarget.assess(start,due,null,start).state());}
    @Test void observationBeforeReceiptRequiresTimeReview(){assertEquals(State.TIME_REVIEW_REQUIRED,CustomerReviewResponseTarget.assess(start,null,null,start.minusNanos(1)).state());}
    @Test void missingRequiredTimesAreRejected(){assertThrows(IllegalArgumentException.class,()->CustomerReviewResponseTarget.assess(null,null,null,start));assertThrows(IllegalArgumentException.class,()->CustomerReviewResponseTarget.assess(start,null,null,null));}
}
