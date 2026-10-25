package dustin.cex.domains.valuation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인가된 운영 조회에서 입금 당시 평가 사실만 읽는다.
 * 금액 구간은 참고 분류이며 법정 면제·개인정보 제공 목적·자금 사용 허가를 결정하지 않는다.
 */
@Service
@DependsOn("bitcoinKrwPriceSchemaInitializer")
public class BitcoinDepositValuationReview {
    private static final BigDecimal REFERENCE_BOUNDARY = new BigDecimal("1000000");
    public static final String ASSURANCE = "REFERENCE_ONLY_PURPOSE_AND_RELEASE_UNDECIDED";

    /** 금액은 JSON 숫자의 정밀도 손실을 피하도록 십진 문자열로 반환한다. 원문 가격 응답과 개인정보는 포함하지 않는다. */
    public record Review(boolean captured, String status, String reason, String policyId,
        Instant referenceAt, Long priceAttemptId, String price, String krwValue,
        String referenceBand, String assurance) {}

    private final JdbcTemplate jdbc;

    public BitcoinDepositValuationReview(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 호출자가 같은 트랜잭션에서 운영 역할·추가 인증·입금 접근 권한을 확인해야 한다.
     * 불변 원본을 읽으므로 현재 가격 수집·재평가·외부 API·엔진 호출은 수행하지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Review read(UUID receipt) {
        var rows = jdbc.queryForList("""
            SELECT v.status,v.reason,v.policy_id,v.reference_at,v.price_attempt_id,v.krw_value,p.price
              FROM bitcoin_deposit_valuations v
              LEFT JOIN btc_krw_price_results p ON p.attempt_id=v.price_attempt_id
             WHERE v.receipt_id=?
            """, receipt);
        // 과거 기록에 평가가 없거나 아직 보류 인식 전인 상태를 0원·소액으로 바꾸지 않는다.
        if (rows.isEmpty()) {
            return new Review(false, "NOT_CAPTURED", "VALUATION_NOT_RECORDED", null,
                null, null, null, null, "UNKNOWN", ASSURANCE);
        }
        Map<String, Object> row = rows.getFirst();
        BigDecimal value = (BigDecimal) row.get("krw_value");
        return new Review(true, (String) row.get("status"), (String) row.get("reason"),
            (String) row.get("policy_id"), ((Timestamp) row.get("reference_at")).toInstant(),
            row.get("price_attempt_id") == null ? null : ((Number) row.get("price_attempt_id")).longValue(),
            decimal((BigDecimal) row.get("price")), decimal(value), band(value), ASSURANCE);
    }

    /** 표시를 위한 반올림 전에 정확한 원화 참고값으로만 비교한다. 경계값 자체는 상위 구간에 포함한다. */
    static String band(BigDecimal value) {
        if (value == null) return "UNKNOWN";
        if (value.signum() <= 0) throw new IllegalStateException("입금 평가액은 양수여야 합니다");
        return value.compareTo(REFERENCE_BOUNDARY) >= 0 ? "AT_LEAST_1M_KRW_REFERENCE" : "BELOW_1M_KRW_REFERENCE";
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
