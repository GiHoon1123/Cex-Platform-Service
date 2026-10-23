package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.web.server.ResponseStatusException;

/** 비공개 판단 사유의 형식과 로그 노출 방지를 검증한다. 법적 판단의 진실성을 자동 평가하지 않는다. */
class ReportingFactsTest {
    @Test void normalizesOuterWhitespaceWithoutDroppingReason() {
        var f=new ReportingReviewService.Facts(ReportingReviewService.Conclusion.REPORT_REQUIRED,"  구체적인 근거  ").validated();assertEquals("구체적인 근거",f.rationale());assertFalse(f.toString().contains("구체적인 근거"));
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","\t","사유\n줄","사유\u202E역전","사유\u200B숨김"})
    void emptyControlOrHiddenDirectionIsRejected(String reason) {assertThrows(ResponseStatusException.class,()->new ReportingReviewService.Facts(ReportingReviewService.Conclusion.REPORT_REQUIRED,reason).validated());}
    @Test void noConclusionCannotBecomeDecision() {assertThrows(ResponseStatusException.class,()->new ReportingReviewService.Facts(null,"판단 근거").validated());}
    @Test void reasonHasExplicitTwoThousandCharacterBoundary() {
        assertEquals(2000,new ReportingReviewService.Facts(ReportingReviewService.Conclusion.NO_REPORT_ON_CURRENT_FACTS,"가".repeat(2000)).validated().rationale().length());assertThrows(ResponseStatusException.class,()->new ReportingReviewService.Facts(ReportingReviewService.Conclusion.REPORT_REQUIRED,"가".repeat(2001)).validated());
    }
}
