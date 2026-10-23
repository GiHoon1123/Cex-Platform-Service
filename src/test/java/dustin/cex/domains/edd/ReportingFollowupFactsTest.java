package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.web.server.ResponseStatusException;

/** 외부 결과는 검증되지 않은 직원 관찰이다. 형식 검증이 사실 인증으로 오인되지 않게 경계를 시험한다. */
class ReportingFollowupFactsTest {
    ReportingFollowupService.Facts f(ReportingFollowupService.Outcome outcome,Instant at,String reference,String notes) {return new ReportingFollowupService.Facts(outcome,at,reference,notes);}
    @ParameterizedTest @EnumSource(ReportingFollowupService.Outcome.class)
    void knownOutcomesPreserveMeaningWithoutPrintingPrivateText(ReportingFollowupService.Outcome outcome) {
        var r=f(outcome,Instant.now().minusSeconds(1),"가상 민감 번호","검토 메모").validated();assertEquals(outcome,r.outcome());assertFalse(r.toString().contains("검토 메모"));assertFalse(r.toString().contains("가상 민감 번호"));
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","\t","안\n쪽","안\u202E쪽","안\u200B쪽"})
    void invalidNotesNeverBecomeObservation(String notes) {assertThrows(ResponseStatusException.class,()->f(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,Instant.now().minusSeconds(1),"",notes).validated());}
    @Test void receiptNeedsReferenceButUnknownAndRejectionMayHaveNoReference() {
        for(var o:new ReportingFollowupService.Outcome[]{ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,ReportingFollowupService.Outcome.REJECTION_OBSERVED})assertEquals("",f(o,Instant.now().minusSeconds(1),"","결과 확인 필요").validated().externalReference());
        assertThrows(ResponseStatusException.class,()->f(ReportingFollowupService.Outcome.RECEIPT_REFERENCE_OBSERVED,Instant.now().minusSeconds(1)," ","관찰").validated());
    }
    @Test void missingFutureAndPreEpochTimeAreRejected() {for(var at:java.util.Arrays.asList(null,Instant.now().plusSeconds(60),Instant.EPOCH.minusSeconds(1)))assertThrows(ResponseStatusException.class,()->f(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,at,"","관찰").validated());}
    @Test void missingOutcomeAndReferenceAreRejected() {assertThrows(ResponseStatusException.class,()->f(null,Instant.now(),"","관찰").validated());assertThrows(ResponseStatusException.class,()->f(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,Instant.now(),null,"관찰").validated());}
    @Test void lengthAndControlBoundariesAreExplicit() {
        assertEquals(2000,f(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,Instant.now().minusSeconds(1),"가".repeat(200),"나".repeat(2000)).validated().notes().length());
        assertThrows(ResponseStatusException.class,()->f(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,Instant.now(),"가".repeat(201),"관찰").validated());assertThrows(ResponseStatusException.class,()->f(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,Instant.now(),"","나".repeat(2001)).validated());assertThrows(ResponseStatusException.class,()->f(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,Instant.now(),"참\n조","관찰").validated());
    }
    @Test void normalizationAndMicrosecondsDoNotInventNewTime() {var time=Instant.parse("2020-01-02T03:04:05.123456789Z");var r=f(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,time," 참조 "," 사유 ").validated();assertEquals("참조",r.externalReference());assertEquals("사유",r.notes());assertEquals(Instant.parse("2020-01-02T03:04:05.123456Z"),r.occurredAt());}
}
