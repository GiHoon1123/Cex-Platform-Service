package dustin.cex.domains.ethereum;

import dustin.cex.shared.model.ExactAmount;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 현재 공통 원장 정밀도에서 ETH 지급액과 사용자가 선택한 0.01% 고정 수수료를 계산한다. */
public final class EthereumWithdrawalAmounts {
    public static final String POLICY = "ETH_WITHDRAWAL_FEE_1BPS_V1";
    public static final BigDecimal RATE = new BigDecimal("0.0001");
    private static final BigDecimal MAXIMUM = new BigDecimal("100000000000");

    private EthereumWithdrawalAmounts() {}

    public record Amounts(BigDecimal amount, BigDecimal fee, BigDecimal total) {}

    public static Amounts quote(String text) {
        if (text == null || text.isEmpty() || text.length() > 64 || !text.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw invalid();
        }
        try {
            BigDecimal amount = ExactAmount.requirePositive(new BigDecimal(text), "ETH 지급액");
            if (amount.scale() > 9 || amount.compareTo(MAXIMUM) > 0) throw invalid();
            amount = amount.setScale(9, RoundingMode.UNNECESSARY);
            BigDecimal fee = amount.multiply(RATE).setScale(9, RoundingMode.CEILING);
            BigDecimal total = ExactAmount.requirePositive(amount.add(fee), "ETH 총 차감액");
            if (total.compareTo(MAXIMUM) > 0) throw invalid();
            return new Amounts(amount, fee, total);
        } catch (ArithmeticException | IllegalArgumentException invalid) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("정확한 ETH 지급액과 수수료 포함 범위를 확인하세요");
    }
}
