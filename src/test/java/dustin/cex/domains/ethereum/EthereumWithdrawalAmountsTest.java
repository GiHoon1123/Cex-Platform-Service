package dustin.cex.domains.ethereum;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** ETH 출금 고정 요율이 입력 표현과 무관하게 아홉 자리 최소 단위에서 보수적으로 계산되는지 검증한다. */
class EthereumWithdrawalAmountsTest {
    @Test
    void calculatesOneBasisPointAndTotalWithoutBinaryFloatingPoint() {
        var amounts = EthereumWithdrawalAmounts.quote("1.23456789");
        assertEquals(0, new BigDecimal("1.234567890").compareTo(amounts.amount()));
        assertEquals(0, new BigDecimal("0.000123457").compareTo(amounts.fee()));
        assertEquals(0, new BigDecimal("1.234691347").compareTo(amounts.total()));
    }

    @Test
    void roundsFeeUpButNeverRoundsCustomerAmount() {
        assertEquals("0.000000001", EthereumWithdrawalAmounts.quote("0.000000001").fee().toPlainString());
        assertThrows(IllegalArgumentException.class, () -> EthereumWithdrawalAmounts.quote("0.0000000001"));
        assertThrows(IllegalArgumentException.class, () -> EthereumWithdrawalAmounts.quote("1e-3"));
        assertThrows(IllegalArgumentException.class, () -> EthereumWithdrawalAmounts.quote("-1"));
    }
}
