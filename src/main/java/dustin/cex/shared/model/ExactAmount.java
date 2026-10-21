package dustin.cex.shared.model;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 현재 PostgreSQL NUMERIC(30,9)와 Rust Decimal 양쪽에 정확히 표현되는 금액 계약.
 * Decimal은 소수점 위치와 별개로 96비트 정수 계수를 사용한다. 따라서 DB에 들어가는
 * 30자리 수가 모두 엔진에서 표현되지는 않는다. 반올림하거나 저장 후 발견하지 않는다.
 * 자산별 자릿수 설정·환율·수수료 정책은 이 클래스의 책임이 아니다.
 */
public final class ExactAmount {
    private static final BigInteger DECIMAL_MAX_COEFFICIENT = BigInteger.ONE.shiftLeft(96).subtract(BigInteger.ONE);

    private ExactAmount() {}

    public static BigDecimal requirePositive(BigDecimal value, String field) {
        BigDecimal normalized = requireNonNegative(value, field);
        if (normalized.signum() == 0) {
            throw new IllegalArgumentException(field + "는 양수여야 합니다");
        }
        return normalized;
    }

    /** 잔액과 예약 결과에도 같은 검사를 적용한다. NULL·음수를 0으로 숨기지 않는다. */
    public static BigDecimal requireNonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(field + "는 0 이상의 금액이어야 합니다");
        }
        try {
            BigDecimal normalized = value.stripTrailingZeros();
            // 큰 지수 입력을 setScale로 먼저 확장하지 않는다. 범위를 통과한 뒤에만
            // 음수 scale을 0으로 맞추므로 최대 21자리 정수만 잠깐 구성한다.
            long integerDigits = (long) normalized.precision() - normalized.scale();
            if (normalized.scale() > 9 || integerDigits > 21) {
                throw new ArithmeticException("NUMERIC(30,9) range exceeded");
            }
            BigDecimal coefficientValue = normalized.scale() < 0 ? normalized.setScale(0) : normalized;
            if (coefficientValue.unscaledValue().compareTo(DECIMAL_MAX_COEFFICIENT) > 0) {
                throw new ArithmeticException("Rust Decimal coefficient exceeded");
            }
            return normalized;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + "는 DB와 엔진에 정확히 표현되어야 합니다", exception);
        }
    }
}
