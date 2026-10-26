package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import java.math.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 고정0.01%와 사토시 올림 경계의 정확한 총 차감액을 검증한다. 실제 체인 비용 계산이 아니다. */
class BitcoinWithdrawalAmountsTest {
    void check(String amount,String fee,String total){var result=BitcoinWithdrawalAmounts.quote(amount);assertEquals(0,new BigDecimal(fee).compareTo(result.fee()));assertEquals(0,new BigDecimal(total).compareTo(result.total()));assertEquals(8,result.amount().scale());assertEquals(8,result.fee().scale());assertEquals(8,result.total().scale());}
    @Test void oneBasisPointUsesTheFixedUserChosenRate(){assertEquals(new BigDecimal("0.0001"),BitcoinWithdrawalAmounts.RATE);check("1","0.00010000","1.00010000");check("0.01","0.00000100","0.01000100");}
    @Test void fractionalSatoshiFeeIsRoundedUpBeforeComputingTheTotal(){check("0.00010000","0.00000001","0.00010001");check("0.00010001","0.00000002","0.00010003");check("0.00012345","0.00000002","0.00012347");}
    @Test void oneSatoshiQuoteStillDisclosesItsWholeSatoshiFee(){check("0.00000001","0.00000001","0.00000002");}
    @Test void equivalentTrailingZeroNotationHasTheSameAmounts(){var expected=BitcoinWithdrawalAmounts.quote("0.01");for(String raw:List.of("00.0100000000","0.01000000","0000.01"))assertEquals(expected,BitcoinWithdrawalAmounts.quote(raw));}
    @Test void malformedPrecisionAndRangeNeverBecomeRoundedPaymentAmounts(){for(String raw:List.of("","0","-1","+1"," 1","1 ","1e-8","1E+2",".1","1.","1_000","0.000000001","0.123456789","21000000","21000001","1".repeat(65))){assertThrows(IllegalArgumentException.class,()->BitcoinWithdrawalAmounts.quote(raw),raw);}assertThrows(IllegalArgumentException.class,()->BitcoinWithdrawalAmounts.quote(null));}
    @Test void protocolSupplyBoundIncludesTheFee(){long totalSatoshis=2_100_000_000_000_000L;long amountSatoshis=BigInteger.valueOf(totalSatoshis).multiply(BigInteger.valueOf(10000)).divide(BigInteger.valueOf(10001)).longValueExact();var valid=BitcoinWithdrawalAmounts.quote(BigDecimal.valueOf(amountSatoshis,8).toPlainString());assertTrue(valid.total().compareTo(new BigDecimal("21000000"))<=0);assertThrows(IllegalArgumentException.class,()->BitcoinWithdrawalAmounts.quote(BigDecimal.valueOf(amountSatoshis+1,8).toPlainString()));}
    @Test void manyExactIntegerSatoshiInputsPreserveAmountPlusFee(){for(long sats:List.of(1L,9L,9999L,10000L,10001L,123456789L,10000000000L)){var result=BitcoinWithdrawalAmounts.quote(BigDecimal.valueOf(sats,8).toPlainString());long expectedFee=(sats+9999)/10000;assertEquals(expectedFee,result.fee().movePointRight(8).longValueExact());assertEquals(sats+expectedFee,result.total().movePointRight(8).longValueExact());}}
}
