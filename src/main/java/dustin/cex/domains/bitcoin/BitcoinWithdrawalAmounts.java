package dustin.cex.domains.bitcoin;

import dustin.cex.shared.model.ExactAmount;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 사용자가 선택한 고정 출금 요율을 BTC의 최소 단위에 결합한다. 실제 채굴자 수수료와 같은 값이 아니다. */
public final class BitcoinWithdrawalAmounts {
    public static final String POLICY = "BTC_WITHDRAWAL_FEE_1BPS_V1";
    public static final BigDecimal RATE = new BigDecimal("0.0001");
    private static final BigDecimal MAXIMUM = new BigDecimal("21000000");

    private BitcoinWithdrawalAmounts() {}

    // 지급액·고객 수수료·총 차감액을 함께 반환해 호출부가 서로 다른 반올림으로 합계를 만들지 않게 한다.
    public record Amounts(BigDecimal amount, BigDecimal fee, BigDecimal total) {}

    public static Amounts quote(String text) {
        // 지수/부호/무제한 소수를 먼저 거부한다. 고객 입력을 이진 부동소수점이나 임의 반올림으로 바꾸지 않는다.
        if (text == null || text.isEmpty() || text.length() > 64
                || !text.matches("[0-9]+(?:\\.[0-9]+)?")) {
            throw invalid();
        }
        final BigDecimal amount;
        try {
            var value = ExactAmount.requirePositive(new BigDecimal(text), "BTC 지급액");
            if (value.scale() > 8 || value.compareTo(MAXIMUM) > 0) {
                throw invalid();
            }
            amount = value.setScale(8, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | IllegalArgumentException error) {
            throw invalid();
        }
        // 0.01%는 비율 0.0001이다. 1사토시 미만을 버리지 않고 올리며 최종 금액을 사전 확인 대상으로 사용한다.
        BigDecimal fee = amount.multiply(RATE).setScale(8, RoundingMode.CEILING);
        BigDecimal total = amount.add(fee);
        if (total.compareTo(MAXIMUM) > 0) {
            throw invalid();
        }
        ExactAmount.requirePositive(total, "BTC 총 차감액");
        return new Amounts(amount, fee, total);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("정확한 BTC 지급액과 수수료 포함 범위를 확인하세요");
    }
}
