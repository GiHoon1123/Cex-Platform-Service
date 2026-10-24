package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.EddService;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 보고 여부와 관계 종료 판단은 각각 별개다. 두 업무가 공유하는 실제 승인 회부의 현재 근거만 검증한다. */
@Component
public class ComplianceReferralBasis {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    private final DiditWebhookVerifier verifier;private final EddDossier dossiers;
    public ComplianceReferralBasis(JdbcTemplate jdbc,ObjectMapper json,DiditWebhookVerifier verifier,EddDossier dossiers) {
        this.jdbc=jdbc;this.json=json;this.verifier=verifier;this.dossiers=dossiers;
    }
    public record Referral(long user,UUID caseId,UUID assessment,long revision,String hash,EddDossier.Summary dossier) {}
    /** 확정된 회부의 본문·정책·결론을 검증한다. 과거 이력의 읽기와 현재 판단에 쓸 자격은 분리한다. */
    public Referral read(UUID id) {
        transaction();
        var rows=jdbc.queryForList("SELECT r.payload,r.payload_hash FROM edd_reports r JOIN edd_decisions d ON d.report_id=r.id AND d.approved=true WHERE r.id=?",id);
        if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String raw=(String)row.get("payload");
        if(!verifier.digest(json.readTree(raw)).equals(row.get("payload_hash")))throw new IllegalStateException("회부 근거 무결성 검증 실패");
        var p=json.readValue(raw,EddService.Payload.class);
        if(!EddService.REVIEW_POLICY.equals(p.reviewPolicy())||p.finding()!=EddService.Finding.COMPLIANCE_ESCALATION||!p.id().equals(id))throw denied(HttpStatus.CONFLICT);
        return new Referral(p.userId(),p.caseId(),p.assessmentId(),p.caseRevision(),(String)row.get("payload_hash"),p.dossier());
    }
    /**
     * 호출 서비스가 고객 행을 먼저 잠근 같은 트랜잭션에서 검사한다. 사건 행도 잠가
     * 새 신고·새 결론·새 자료가 들어온 뒤에 옛 평가와 전체 근거를 승인하지 못하게 한다.
     * 실제 업무 역할·지정 인물·추가 인증은 이 부품이 아니라 호출 서비스가 검사한다.
     */
    public long current(Referral r) {
        transaction();
        var rows=jdbc.queryForList("SELECT c.revision,c.state,c.latest_assessment,p.assessment_id FROM edd_cases c JOIN cdd_profiles p ON p.user_id=c.user_id WHERE c.id=? AND c.user_id=? FOR UPDATE OF c",r.caseId(),r.user());
        if(rows.isEmpty())throw denied(HttpStatus.CONFLICT);var row=rows.getFirst();long revision=((Number)row.get("revision")).longValue();
        if(!"REFERRED".equals(row.get("state"))||revision!=r.revision()+1||!r.assessment().equals(row.get("latest_assessment"))||!r.assessment().equals(row.get("assessment_id")))throw denied(HttpStatus.CONFLICT);
        dossiers.verify(r.dossier(),r.caseId(),r.user());if(!r.dossier().digest().equals(dossiers.liveDigest(r.caseId(),r.user())))throw denied(HttpStatus.CONFLICT);return revision;
    }

    private void transaction() {if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("회부 근거 검증에는 업무 트랜잭션이 필요합니다");}
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"현재 준법 회부 근거를 확인하세요");}
}
