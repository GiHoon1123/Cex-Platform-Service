package dustin.cex.domains.bitcoin;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
/** 합계가 영이라는 조건만으로 잘못된 입금 소유자/금액을 통과시키지 않는지 확인한다. */
class BitcoinDepositReconciliationTest {
    BitcoinDepositReconciliationService.Entry e(long owner,String bucket,String asset,String amount){return new BitcoinDepositReconciliationService.Entry(owner,bucket,asset,new BigDecimal(amount));}
    @Test void exactPairMatchesRegardlessOfDecimalScale(){assertTrue(BitcoinDepositReconciliationService.matchesEntries(7,"BTC",new BigDecimal("0.1"),List.of(e(7,"PENDING","BTC","0.100"),e(0,"CUSTODY","BTC","-0.10"))));}
    @Test void balancedWrongAmountDoesNotMatch(){assertFalse(BitcoinDepositReconciliationService.matchesEntries(7,"BTC",BigDecimal.ONE,List.of(e(7,"PENDING","BTC","2"),e(0,"CUSTODY","BTC","-2"))));}
    @Test void wrongOwnerBucketOrAssetDoesNotMatch(){for(var wrong:List.of(e(8,"PENDING","BTC","1"),e(7,"AVAILABLE","BTC","1"),e(7,"PENDING","ETH","1")))assertFalse(BitcoinDepositReconciliationService.matchesEntries(7,"BTC",BigDecimal.ONE,List.of(wrong,e(0,"CUSTODY","BTC","-1"))));}
    @Test void MissingExtraOrDuplicatePostingDoesNotMatch(){var customer=e(7,"PENDING","BTC","1");var custody=e(0,"CUSTODY","BTC","-1");for(var entries:List.of(List.of(customer),List.of(customer,customer),List.of(customer,custody,custody)))assertFalse(BitcoinDepositReconciliationService.matchesEntries(7,"BTC",BigDecimal.ONE,entries));}
}
