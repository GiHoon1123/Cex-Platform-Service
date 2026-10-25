package dustin.cex.domains.monitoring;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.edd.EvidenceCrypto;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 불변 신호/의견의 명시적인 접두 구간을 고정한다. 위치 토큰은 원본 검증이나 금융 허가를 대체하지 않는다. */
@Service
@DependsOn("depositMonitoringSchemaInitializer")
public class CaseReviewEvidence {
    public record Position(UUID caseId,long customerId,String policy,long signals,String throughCursor,long opinions,String version){}
    private final JdbcTemplate jdbc;
    private final CddCrypto cdd;
    private final EvidenceCrypto crypto;
    public CaseReviewEvidence(JdbcTemplate jdbc,CddCrypto cdd,EvidenceCrypto crypto){this.jdbc=jdbc;this.cdd=cdd;this.crypto=crypto;}
    /** 호출자가 실제 고객을 먼저 잠근다. 다음 입금은 범위 밖에 남기고 범위 안 의견 추가는 위치를 바꾼다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Position position(UUID id,Long through){
        if(through!=null&&through<=0)throw denied(HttpStatus.BAD_REQUEST);
        var rows=jdbc.queryForList("SELECT * FROM monitoring_case_scope(?,?)",id,through==null?Long.MAX_VALUE:through);
        if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var r=rows.getFirst();long last=((Number)r.get("through_cursor")).longValue();
        if(last<=0||(through!=null&&last!=through))throw denied(HttpStatus.CONFLICT);
        return new Position(id,((Number)r.get("user_id")).longValue(),(String)r.get("policy_id"),((Number)r.get("signals")).longValue(),Long.toString(last),((Number)r.get("opinions")).longValue(),(String)r.get("scope_version"));
    }
    /** 실제118 열람에서 검증한 원본·메모를 모두 확인한 사람만 판단한다. 열람 감사 자체는 새 판단 근거로 세지 않는다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireViewed(User person,UUID sid,Position p){
        crypto.requireConfigured();cdd.requireConfigured();long through=cursor(p);
        boolean missing=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM deposit_monitoring_case_assessments m WHERE m.case_id=? AND m.input_cursor<=?
             AND NOT EXISTS(SELECT 1 FROM deposit_monitoring_source_views v WHERE v.assessment_id=m.assessment_id AND v.source_hash=m.source_hash
              AND v.actor_id=? AND v.actor_person=? AND v.actor_version=? AND v.session_id=?))
             OR EXISTS(SELECT 1 FROM deposit_monitoring_observations o JOIN deposit_monitoring_case_assessments m ON m.assessment_id=o.assessment_id
              WHERE m.case_id=? AND m.input_cursor<=? AND NOT EXISTS(SELECT 1 FROM deposit_monitoring_observation_views v WHERE v.observation_id=o.id
               AND v.actor_id=? AND v.actor_person=? AND v.actor_version=? AND v.session_id=?))
            """,Boolean.class,p.caseId(),through,person.getId(),person.getOperatorPersonId(),person.getVersion(),sid,p.caseId(),through,person.getId(),person.getOperatorPersonId(),person.getVersion(),sid));
        if(missing)throw denied(HttpStatus.CONFLICT);
        // 지원하지 않는 옛 키의 메모를 과거 열람 이력만으로 정상이라고 처리하지 않는다.
        boolean unsupported=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deposit_monitoring_observations o JOIN deposit_monitoring_case_assessments m ON m.assessment_id=o.assessment_id WHERE m.case_id=? AND m.input_cursor<=? AND(o.evidence_key_id<>? OR o.fingerprint_key_id<>?))",Boolean.class,p.caseId(),through,crypto.keyId(),cdd.keyId()));
        if(unsupported)throw denied(HttpStatus.SERVICE_UNAVAILABLE);
    }
    static long cursor(Position p){try{long value=Long.parseLong(p.throughCursor());if(value<=0)throw new IllegalArgumentException();return value;}catch(RuntimeException invalid){throw denied(HttpStatus.BAD_REQUEST);}}
    static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 조사 범위·열람 근거·지원 키를 확인하세요");}
}
