package dustin.cex.domains.travelrule;

import dustin.cex.domains.security.AuditService;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수신한 정보가 요청 당시 봉인한 고객 정보와 일관되는지만 분류한다.
 * 동일인 확정·현재 고객 적격성·체인 안전·법정 심사·자금 사용 허가는 별도 업무다.
 */
@Service
public class CodeVaspTransferDetailAssessment {
    public static final String POLICY = "BTC_REQUEST_DETAILS_V1";
    public record Decision(String status, String reason) {}
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CodeVaspTransferDetailAssessment(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** 응답 원본·분류·감사는 같은 완료 트랜잭션에 넣는다. 실패하면 시작 기록만 남고 자동 재전송하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID attempt, CodeVaspTransferIntents.Prepared prepared, CodeVaspTransferDataClient.Data data) {
        Decision decision = compare(prepared.request().recipientName(), LocalDate.parse(prepared.dateOfBirth()),
            prepared.request().query().beneficiaryAddress(), data);
        jdbc.update("INSERT INTO codevasp_transfer_detail_assessments(attempt_id,policy_id,status,reason) VALUES(?,?,?,?)",
            attempt, POLICY, decision.status(), decision.reason());
        // 성명·생일·주소·공급사 원문은 기존 암호화 원본에만 둔다. 감사에는 참조와 고정 분류 코드만 남긴다.
        audit.record(null, "CODE_TRANSFER_DETAILS_ASSESSED", "codevasp-attempt:" + attempt,
            "SUCCESS", "POLICY:" + POLICY + ":STATUS:" + decision.status() + ":REASON:" + decision.reason());
    }

    /** 첫 불일치만 고정 코드로 반환한다. 비교 대상 자료는 암호화 보존하며 이 사유를 모든 위험의 목록으로 해석하지 않는다. */
    static Decision compare(String name, LocalDate birth, String address, CodeVaspTransferDataClient.Data data) {
        if (data == null || data.beneficiary() == null || data.originator() == null) return review("PARTIES_INCOMPLETE");
        String reason = person(name, birth, data.beneficiary(), "BENEFICIARY");
        if (reason != null) return review(reason);
        if (data.beneficiary().accounts().size() != 1 || !address.equals(data.beneficiary().accounts().getFirst())) return review("BENEFICIARY_ACCOUNT_MISMATCH");
        reason = person(name, birth, data.originator(), "ORIGINATOR");
        return reason == null ? new Decision("CONSISTENT", "DETAILS_CONSISTENT") : review(reason);
    }

    private static String person(String expected, LocalDate birth, CodeVaspTransferDataClient.Person person, String role) {
        // 현재 확인된 이름은 전체 이름 하나다. 성/이름이 분리된 응답은 순서를 추측해 합치지 않는다.
        if (person.secondaryName() != null && !person.secondaryName().isBlank()) return role + "_STRUCTURED_NAME";
        if (expected == null || person.primaryName() == null || expected.isBlank() || person.primaryName().isBlank()
            || !canonical(expected).equals(canonical(person.primaryName()))) return role + "_NAME_MISMATCH";
        if (birth == null || person.dateOfBirth() == null) return role + "_BIRTH_DATE_MISSING";
        return birth.equals(person.dateOfBirth()) ? null : role + "_BIRTH_DATE_MISMATCH";
    }

    private static String canonical(String name) {
        // 같은 문자의 유니코드 조합 표현과 양끝 공백만 정리한다. 음역·대소문자·이름 순서·내부 공백을 추측하지 않는다.
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
    }

    private static Decision review(String reason) {
        return new Decision("REVIEW_REQUIRED", reason);
    }
}
