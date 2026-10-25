package dustin.cex.domains.valuation;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 표시 반올림 전의 정확한 평가액으로 경계와 부재를 구분한다. 법정 면제 판정은 수행하지 않는다. */
class BitcoinDepositValuationReviewTest {
    @Test void missingIsUnknownRatherThanZeroOrExempt() {
        assertEquals("UNKNOWN", BitcoinDepositValuationReview.band(null));
    }
    @Test void justBelowBoundaryIsNotRoundedUp() {
        assertEquals("BELOW_1M_KRW_REFERENCE", BitcoinDepositValuationReview.band(new BigDecimal("999999.9999999999999999")));
    }
    @Test void exactBoundaryBelongsToUpperReferenceBand() {
        assertEquals("AT_LEAST_1M_KRW_REFERENCE", BitcoinDepositValuationReview.band(new BigDecimal("1000000.0000000000000000")));
    }
    @Test void justAboveBoundaryIsComparedExactly() {
        assertEquals("AT_LEAST_1M_KRW_REFERENCE", BitcoinDepositValuationReview.band(new BigDecimal("1000000.0000000000000001")));
    }
    @Test void nonPositiveValuesCannotLookLikeValidSmallDeposits() {
        assertThrows(IllegalStateException.class, () -> BitcoinDepositValuationReview.band(BigDecimal.ZERO));
        assertThrows(IllegalStateException.class, () -> BitcoinDepositValuationReview.band(new BigDecimal("-1")));
    }
}
