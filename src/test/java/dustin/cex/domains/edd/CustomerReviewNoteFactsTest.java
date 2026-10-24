package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 내부 처리 의견은 기존 고객 텍스트 규칙을 재사용하며 진행 구분을 반드시 명시한다. */
class CustomerReviewNoteFactsTest {
    @Test void progressAndNonEmptyNoteAreRequired(){assertThrows(ResponseStatusException.class,()->new CustomerReviewNoteService.Facts(null,"의견").validated());assertThrows(ResponseStatusException.class,()->new CustomerReviewNoteService.Facts(CustomerReviewNoteService.Progress.INVESTIGATING," ").validated());}
    @Test void multilineNormalizationAndSafePrinting(){var f=new CustomerReviewNoteService.Facts(CustomerReviewNoteService.Progress.INVESTIGATING," 첫째\r\n둘째 ").validated();assertEquals("첫째\n둘째",f.note());assertFalse(f.toString().contains("첫째"));assertFalse(new CustomerReviewNoteService.Inspection(null,f).toString().contains("첫째"));}
}
