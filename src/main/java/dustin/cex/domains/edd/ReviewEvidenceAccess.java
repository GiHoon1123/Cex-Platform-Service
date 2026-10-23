package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 주소·자금 검토가 실제로 공유하는 현재 평가·키·자료 제공 검사를 한곳에 둔다. 외부 API는 없다. */
@Component
class ReviewEvidenceAccess {
    record Snapshot(UUID caseId,long revision,long userId,UUID assessment,UUID declaration,String name,String address,CustomerDeclaration.SourceOfFunds source) {
        @Override public String toString() {return "Snapshot[개인정보 생략]";}
    }
    private final JdbcTemplate jdbc;private final UserRepository users;private final EvidenceCrypto crypto;private final CddCrypto cdd;private final ObjectMapper json;
    ReviewEvidenceAccess(JdbcTemplate jdbc,UserRepository users,EvidenceCrypto crypto,CddCrypto cdd,ObjectMapper json) {this.jdbc=jdbc;this.users=users;this.crypto=crypto;this.cdd=cdd;this.json=json;}
    /** 호출 서비스가 먼저 사용자 행을 잠그고 최근 인증을 검사한 같은 업무 트랜잭션에서만 사용한다. */
    Snapshot current(UUID evidence,EvidenceService.Purpose purpose) {
        transaction();crypto.requireConfigured();cdd.requireConfigured();
        var rows=jdbc.queryForList("""
            SELECT c.id AS case_id,c.revision,c.user_id,a.id AS assessment_id,d.id AS declaration_id,d.ciphertext,
             a.key_id AS assessment_key,d.key_id AS declaration_key,e.key_id AS evidence_key,a.policy_version
            FROM edd_evidence e JOIN edd_evidence_requests r ON r.id=e.request_id AND r.user_id=e.user_id
             JOIN edd_cases c ON c.id=r.case_id AND c.user_id=r.user_id
             JOIN cdd_profiles p ON p.user_id=c.user_id AND p.assessment_id=c.latest_assessment
             JOIN cdd_assessments a ON a.id=p.assessment_id AND a.user_id=c.user_id AND a.declaration_id=p.declaration_id AND a.id=r.assessment_id
             JOIN cdd_declarations d ON d.id=p.declaration_id AND d.user_id=c.user_id
             JOIN kyc_profiles k ON k.user_id=c.user_id AND k.active_attempt=a.attempt_id AND k.status_digest=a.status_digest
            WHERE e.id=? AND r.purpose=? AND c.state IN ('OPEN','REFERRED') FOR UPDATE OF c
            """,evidence,purpose.name());
        if(rows.isEmpty()) throw denied(HttpStatus.CONFLICT);var r=rows.getFirst();
        if(!cdd.keyId().equals(r.get("assessment_key")) || !cdd.keyId().equals(r.get("declaration_key")) || !crypto.keyId().equals(r.get("evidence_key")) || !CddService.POLICY.equals(r.get("policy_version"))) throw denied(HttpStatus.CONFLICT);
        long user=((Number)r.get("user_id")).longValue();UUID declaration=(UUID)r.get("declaration_id");CustomerDeclaration declared;
        try {declared=json.readValue(cdd.decrypt(user,declaration,(byte[])r.get("ciphertext")),CustomerDeclaration.class);}
        catch(RuntimeException e) {throw new IllegalStateException("현재 신고 해독 검증 실패");}
        return new Snapshot((UUID)r.get("case_id"),((Number)r.get("revision")).longValue(),user,(UUID)r.get("assessment_id"),declaration,declared.fullName(),declared.address(),declared.sourceOfFunds());
    }
    void view(User actor,UUID sid,UUID evidence,UUID view,UUID assessment,long revision) {
        transaction();if(view==null) throw denied(HttpStatus.CONFLICT);
        boolean valid=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM edd_evidence_views v JOIN edd_evidence e ON e.id=v.evidence_id
             JOIN edd_evidence_requests r ON r.id=e.request_id
             WHERE v.id=? AND v.evidence_id=? AND v.actor_id=? AND v.actor_person=? AND v.actor_version=? AND v.session_id=?
              AND v.assessment_id=? AND v.case_revision=? AND v.key_id=? AND v.viewed_at>clock_timestamp()-interval '5 minutes'
              AND ((?='OPERATOR' AND r.requester_id=v.actor_id AND r.requester_person=v.actor_person)
               OR (?='APPROVER' AND r.requester_person<>v.actor_person)))
            """,Boolean.class,view,evidence,actor.getId(),actor.getOperatorPersonId(),actor.getVersion(),sid,assessment,revision,crypto.keyId(),actor.getRole().name(),actor.getRole().name()));
        if(!valid) throw denied(HttpStatus.CONFLICT);
    }
    long owner(UUID evidence) {transaction();var rows=jdbc.queryForList("SELECT user_id FROM edd_evidence WHERE id=?",evidence);if(rows.isEmpty()) throw denied(HttpStatus.NOT_FOUND);return ((Number)rows.getFirst().get("user_id")).longValue();}
    Map<Long,User> lock(long... ids) {transaction();var result=new HashMap<Long,User>();Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(u->result.put(id,u)));return result;}
    void customer(User user) {if(user==null || user.getRole()!=UserRole.CUSTOMER) throw denied(HttpStatus.CONFLICT);}
    private void transaction() {if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("증빙 접근에는 업무 트랜잭션이 필요합니다");}
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"증빙의 현재 평가·자료·인물·세션을 확인하세요");}
}
