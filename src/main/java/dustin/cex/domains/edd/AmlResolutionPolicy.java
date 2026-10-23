package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * AML 검토만 있는 사건에서 공급사의 새 오탐 판정과 후보별 조사를 검증한다.
 * 점수 임계값·수동 면제·임의 외부 근거 번호로 다른 위험을 해소하지 않는다.
 */
@Component
public class AmlResolutionPolicy {
    public static final String POLICY="EDD_AML_FALSE_POSITIVE_V1";
    public record SourceRef(UUID id,String contentMac) {}
    public record InvestigationRef(UUID id,String payloadHash,UUID person) {}
    /** 승인 내용에는 개인정보 대신 실제 불변 자료·조사와 정책만 고정한다. */
    public record Basis(String policy,SourceRef current,List<SourceRef> historical,List<InvestigationRef> investigations,int candidates) {
        public Basis {historical=List.copyOf(historical);investigations=List.copyOf(investigations);}
    }
    public record Eligibility(boolean eligible,String unmetCondition,Basis basis,Instant maximumValidUntil) {}
    private final JdbcTemplate jdbc;private final AmlSource sources;private final AmlInvestigationService investigations;private final CddCrypto crypto;
    public AmlResolutionPolicy(JdbcTemplate jdbc,AmlSource sources,AmlInvestigationService investigations,CddCrypto crypto) {this.jdbc=jdbc;this.sources=sources;this.investigations=investigations;this.crypto=crypto;}
    private Eligibility denied(String reason) {return new Eligibility(false,reason,null,null);}
    /** 호출 서비스가 사용자·사건을 잠근 같은 트랜잭션에서 실행한다. 외부 호출이나 엔진 접근은 없다. */
    public Eligibility assess(long user) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("AML 종결 판단에는 업무 트랜잭션이 필요합니다");
        var rows=jdbc.queryForList("""
            SELECT c.id,c.revision,a.id AS assessment,a.event_id,a.status_digest,a.key_id,a.policy_version,a.reasons,a.outcome,
             t.workflow_id,t.workflow_version,k.identity_status,k.valid_until,q.fresh_at
            FROM edd_cases c JOIN cdd_profiles p ON p.user_id=c.user_id AND p.assessment_id=c.latest_assessment
             JOIN cdd_assessments a ON a.id=p.assessment_id AND a.declaration_id=p.declaration_id AND a.user_id=c.user_id
             JOIN kyc_profiles k ON k.user_id=c.user_id AND k.active_attempt=a.attempt_id AND k.status_digest=a.status_digest
             JOIN kyc_attempts t ON t.id=a.attempt_id AND t.environment='live'
             LEFT JOIN kyc_reconciliations q ON q.attempt_id=t.id
            WHERE c.user_id=?
            """,user);
        if(rows.isEmpty())return denied("CURRENT_ASSESSMENT_UNAVAILABLE");var row=rows.getFirst();
        // 현재 점수가 낮아져도 과거의 다른 위험을 덮어쓰지 않는다. 이 경로는 AML 검토만 있던 사건에 한정한다.
        long other=jdbc.queryForObject("SELECT count(*) FROM cdd_assessments WHERE user_id=? AND outcome='REVIEW_REQUIRED' AND reasons<>'AML_REVIEW_REQUIRED'",Long.class,user);
        if(other>0||!"AML_REVIEW_REQUIRED".equals(row.get("reasons"))||!"REVIEW_REQUIRED".equals(row.get("outcome")))return denied("OTHER_RISK_REQUIRES_REVIEW");
        if(jdbc.queryForObject("SELECT count(*) FROM edd_evidence_requests WHERE case_id=?",Long.class,row.get("id"))>0)return denied("ADDITIONAL_EVIDENCE_UNRESOLVED");
        if(jdbc.queryForObject("SELECT count(*) FROM compliance_reviews WHERE case_id=?",Long.class,row.get("id"))>0)return denied("COMPLIANCE_REVIEW_UNRESOLVED");
        Instant now=Instant.now();
        if(!crypto.keyId().equals(row.get("key_id"))||!CddService.POLICY.equals(row.get("policy_version"))||!"VERIFIED".equals(row.get("identity_status"))
            ||row.get("valid_until")==null||!((Timestamp)row.get("valid_until")).toInstant().isAfter(now)
            ||row.get("fresh_at")==null||!((Timestamp)row.get("fresh_at")).toInstant().isAfter(now.minusSeconds(900)))return denied("CURRENT_IDENTITY_NOT_VERIFIED");
        // 평가마다 실제 보관 자료가 있어야 한다. 누락된 과거 자료를 현재 결과로 채워 넣으면 해소 대상을 증명할 수 없다.
        var all=jdbc.queryForList("""
            SELECT a.id AS assessment,s.id AS source,t.workflow_id,t.workflow_version FROM cdd_assessments a
             JOIN kyc_attempts t ON t.id=a.attempt_id LEFT JOIN edd_aml_sources s ON s.assessment_id=a.id AND s.user_id=a.user_id
             WHERE a.user_id=? AND a.outcome='REVIEW_REQUIRED' ORDER BY a.assessed_at,a.id
            """,user);
        if(all.stream().anyMatch(x->x.get("source")==null))return denied("HISTORICAL_SOURCE_MISSING");
        var current=all.stream().filter(x->row.get("assessment").equals(x.get("assessment"))).findFirst();
        if(current.isEmpty())return denied("CURRENT_SOURCE_MISSING");
        var m=sources.metadata((UUID)current.get().get("source"));var checked=AmlFalsePositiveRules.inspect(sources.content(m).report(),true);
        if(!checked.valid())return denied(checked.failure());
        var currentKeys=new HashSet<AmlFalsePositiveRules.Key>();checked.candidates().forEach(x->currentKeys.add(x.key()));var past=new ArrayList<SourceRef>();
        // 검색 배열 위치는 바뀔 수 있으므로 과거 후보는 노드 식별자와 후보 식별자 쌍으로 연결한다.
        // 공급사 시각도 엄격히 증가해야 같은 시각의 다른 이벤트를 새로운 해소 근거로 오인하지 않는다.
        for(var prior:all) {
            if(prior.get("assessment").equals(row.get("assessment")))continue;
            if(!row.get("workflow_id").equals(prior.get("workflow_id"))||!row.get("workflow_version").equals(prior.get("workflow_version")))return denied("WORKFLOW_CHANGED");
            var meta=sources.metadata((UUID)prior.get("source"));
            if(meta.providerTime()>=m.providerTime())return denied("NEW_SIGNED_RESOLUTION_REQUIRED");
            var historical=AmlFalsePositiveRules.inspect(sources.content(meta).report(),false);
            if(!historical.valid())return denied(historical.failure());
            if(!checked.subject().equals(historical.subject()))return denied("SCREENED_PERSON_MISMATCH");
            if(!historical.candidates().stream().allMatch(x->currentKeys.contains(x.key())))return denied("CANDIDATE_NOT_PRESERVED");
            past.add(new SourceRef(meta.id(),meta.contentMac()));
        }
        if(past.isEmpty())return denied("NEW_SIGNED_RESOLUTION_REQUIRED");
        var refs=new ArrayList<InvestigationRef>();
        // 현재 자료의 모든 후보에서 가장 최근 의견을 사용한다. 유리했던 과거 의견만 골라 승인하지 못하게 한다.
        // 메모의 암호화 인증도 다시 검사하지만 평문은 승인 내용이나 응답에 복사하지 않는다.
        for(var candidate:checked.candidates()) {
            var records=jdbc.queryForList("""
                SELECT id FROM edd_aml_investigations WHERE source_id=? AND payload::jsonb->>'screeningIndex'=? AND payload::jsonb->>'hitId'=?
                 ORDER BY created_at DESC,id DESC LIMIT 1
                """,m.id(),Integer.toString(candidate.screening()),candidate.key().hit());
            if(records.isEmpty())return denied("INVESTIGATION_UNRESOLVED");
            var record=investigations.verifiedForResolution((UUID)records.getFirst().get("id"));var p=record.payload();
            if(p.finding()!=AmlInvestigationService.Finding.IDENTIFIERS_DIFFER||p.caseRevision()!=((Number)row.get("revision")).longValue()
                ||!p.assessmentId().equals(m.assessmentId())||!p.sourceMac().equals(m.contentMac()))return denied("INVESTIGATION_UNRESOLVED");
            refs.add(new InvestigationRef(p.id(),record.payloadHash(),p.requesterPerson()));
        }
        // 하루는 자체 정책의 상한이다. 고객 신원 기한이 더 짧으면 그 기한을 적용하며 법정 기한으로 설명하지 않는다.
        Instant identityExpiry=((Timestamp)row.get("valid_until")).toInstant();Instant horizon=now.plusSeconds(86400);
        return new Eligibility(true,null,new Basis(POLICY,new SourceRef(m.id(),m.contentMac()),past,refs,checked.candidates().size()),identityExpiry.isBefore(horizon)?identityExpiry:horizon);
    }
    public Basis require(long user,Instant until) {
        var result=assess(user);if(!result.eligible()||until==null||!until.isAfter(Instant.now())||until.isAfter(result.maximumValidUntil()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"현재 AML 오탐 종결 조건과 기한을 확인하세요");
        return result.basis();
    }
}
