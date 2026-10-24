package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.web.server.ResponseStatusException;

/** 고객의 여러 줄 사유를 허용하되 길이·제어 문자와 출력 노출의 경계를 검증한다. */
class CustomerExitReviewSubmissionTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","안\u0000쪽","안\u202E쪽"})
    void invalidRationaleIsRejected(String value){assertThrows(ResponseStatusException.class,()->new CustomerExitReviewService.Submission(value).validated());}
    @Test void normalizeMultilineAndPreserveMeaningWithoutPrintingReason(){var value=new CustomerExitReviewService.Submission("  첫째\r\n둘째  ").validated();assertEquals("첫째\n둘째",value.rationale());assertEquals("가",new CustomerExitReviewService.Submission("\u1100\u1161").validated().rationale());assertFalse(value.toString().contains("첫째"));assertFalse(new CustomerExitReviewService.Inspection(null,value.rationale()).toString().contains("첫째"));}
    @Test void exactUtf16LengthLimitIsEnforced(){assertEquals(2000,new CustomerExitReviewService.Submission("가".repeat(2000)).validated().rationale().length());assertThrows(ResponseStatusException.class,()->new CustomerExitReviewService.Submission("가".repeat(2001)).validated());}
}
