package dustin.cex.domains.travelrule;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 자동 출처 정보 요청의 내부 목적과 최소 금액 근거를 제한한다.
 * 이 조건을 통과해도 법적 의무·동일인·자금 사용이 확정되는 것은 아니다.
 * 소액 반복 등 별도 위험 검토는 이 자동 경로를 임의로 우회하지 않는다.
 */
@Service
public class CodeVaspTransferPurposePolicy {
    public static final String POLICY = "BTC_ORIGIN_REFERENCE_1M_V1";
    private static final BigDecimal MINIMUM = new BigDecimal("1000000");
    public record Purpose(String policy, String purpose, UUID receiptId, long priceAttemptId,
        Instant referenceAt, BigDecimal krwValue) {}
    private final JdbcTemplate jdbc;

    public CodeVaspTransferPurposePolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 현재가를 다시 읽지 않고 해당 입금의 불변 평가 사실로만 자동 요청 대상을 좁힌다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Purpose require(UUID receipt) {
        var rows = jdbc.queryForList("""
            SELECT price_attempt_id,reference_at,krw_value FROM bitcoin_deposit_valuations
             WHERE receipt_id=? AND policy_id='BTC_PENDING_KRW_REFERENCE_V1'
               AND status='VALUED' AND krw_value>=?
            """, receipt, MINIMUM);
        if (rows.size() != 1) throw held();
        var row = rows.getFirst();
        return new Purpose(POLICY, "DEPOSIT_ORIGIN_VERIFICATION", receipt,
            ((Number) row.get("price_attempt_id")).longValue(),
            ((Timestamp) row.get("reference_at")).toInstant(), (BigDecimal) row.get("krw_value"));
    }

    /** 의도 원본과 목적 근거는 같은 업무 트랜잭션에서 저장하며 감사 실패 시 함께 롤백한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID intent, Purpose purpose) {
        jdbc.update("""
            INSERT INTO codevasp_transfer_purposes(intent_id,receipt_id,policy_id,purpose,price_attempt_id,reference_at,krw_value)
             VALUES(?,?,?,?,?,?,?)
            """, intent, purpose.receiptId(), purpose.policy(), purpose.purpose(), purpose.priceAttemptId(),
            Timestamp.from(purpose.referenceAt()), purpose.krwValue());
    }

    /** 과거 목적 없는 의도를 새 자동 요청에 재사용하지 않는다. 기록을 소급 생성하는 폴백은 없다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireRecorded(UUID intent, UUID receipt) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_purposes WHERE intent_id=? AND receipt_id=? AND policy_id=?", Long.class, intent, receipt, POLICY);
        if (count == null || count != 1) throw held();
    }

    /** 불변 평가 부재·소액은 자동 재시도로 해결되지 않는다. 작업자는 이 유형을 별도 검토로 종료한다. */
    public static final class ReviewRequired extends ResponseStatusException {
        private ReviewRequired() {
            super(HttpStatus.CONFLICT, "입금의 자동 정보 요청 목적과 당시 평가 근거를 확인하세요");
        }
    }
    private static ReviewRequired held() {
        return new ReviewRequired();
    }
}
