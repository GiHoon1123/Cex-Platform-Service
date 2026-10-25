package dustin.cex.domains.monitoring;

import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 같은 고객의 반복 신호를 한 조사 사건에 묶는다. 사건의 존재·담당자 의견은 종결·보고·금융 허가가 아니다. */
@Service
@DependsOn("depositMonitoringSchemaInitializer")
public class DepositMonitoringCases {
    public static final String ASSURANCE="INVESTIGATION_NOT_A_REPORT_OR_FINANCIAL_PERMISSION";
    public record Case(UUID id,long customerId,String policy,UUID firstAssessmentId,long signalCount,Instant openedAt,String status,String assurance){}
    public record Member(UUID assessmentId,UUID receiptId,String cursor,Instant referenceAt,String reason,String sourceHash,long observations){}
    public record Members(Case investigation,List<Member> items,String nextBefore){}
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuditService audit;
    private final OperatorSessionPolicy sessions;
    private final TransactionTemplate tx;
    public DepositMonitoringCases(JdbcTemplate jdbc,UserRepository users,AuditService audit,OperatorSessionPolicy sessions,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.users=users;this.audit=audit;this.sessions=sessions;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 자동 분석 트랜잭션에서만 호출한다. 실제 평가의 소유자 잠금으로 여러 프로세스의 최초 사건 생성을 직렬화한다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID link(UUID assessment){
        var rows=jdbc.queryForList("SELECT user_id,policy_id,outcome,input_cursor,source_hash FROM deposit_monitoring_assessments WHERE id=?",assessment);
        if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var source=rows.getFirst();long owner=((Number)source.get("user_id")).longValue();
        if(!"REVIEW_REQUIRED".equals(source.get("outcome")))throw denied(HttpStatus.CONFLICT);
        users.lockById(owner).orElseThrow(()->denied(HttpStatus.NOT_FOUND));
        var old=jdbc.queryForList("SELECT case_id FROM deposit_monitoring_case_assessments WHERE assessment_id=?",UUID.class,assessment);
        if(!old.isEmpty())return old.getFirst();
        var heads=jdbc.queryForList("SELECT case_id FROM deposit_monitoring_case_heads WHERE user_id=? FOR UPDATE",UUID.class,owner);UUID id;
        // 종결 당시 범위가 아직 유효한 경우에만 새 사건을 만든다. 원래 사건과 구성원은 수정하지 않는다.
        if(heads.isEmpty()||Boolean.TRUE.equals(jdbc.queryForObject("SELECT monitoring_case_closed(?)",Boolean.class,heads.getFirst()))){
            id=UUID.randomUUID();jdbc.update("INSERT INTO deposit_monitoring_cases(id,user_id,policy_id,first_assessment_id) VALUES(?,?,?,?)",id,owner,source.get("policy_id"),assessment);
            if(heads.isEmpty())jdbc.update("INSERT INTO deposit_monitoring_case_heads(user_id,case_id) VALUES(?,?)",owner,id);
            else jdbc.update("UPDATE deposit_monitoring_case_heads SET case_id=? WHERE user_id=?",id,owner);
        }else id=heads.getFirst();
        jdbc.update("INSERT INTO deposit_monitoring_case_assessments(case_id,assessment_id,input_cursor,source_hash) VALUES(?,?,?,?)",id,assessment,source.get("input_cursor"),source.get("source_hash"));
        audit.record(null,"MONITORING_CASE_ASSESSMENT_LINKED","monitoring-case:"+id+":assessment:"+assessment,"SUCCESS",(String)source.get("source_hash"));return id;
    }
    /** 사건 목록은 개인정보·메모·전체 금융 입력 없이 제공한다. 현재 역할과 추가 인증을 조회 전에 다시 확인한다. */
    public List<Case> list(long actor,UUID sid,int page){return work(actor,"MONITORING_CASES_LISTED",()->{
        authorize(actor,sid);if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);
        var rows=jdbc.queryForList("SELECT c.*,monitoring_case_closed(c.id) closed,(SELECT count(*) FROM deposit_monitoring_case_assessments m WHERE m.case_id=c.id) signals FROM deposit_monitoring_cases c ORDER BY c.opened_at DESC,c.id DESC LIMIT 25 OFFSET ?",page*25);
        var result=rows.stream().map(this::map).toList();audit.record(actor,"MONITORING_CASES_LISTED","monitoring-cases","SUCCESS","METADATA_ONLY");return result;
    });}
    public Members members(long actor,UUID sid,UUID id,Long before){return work(actor,"MONITORING_CASE_READ",()->{
        authorize(actor,sid);if(before!=null&&before<=0)throw denied(HttpStatus.BAD_REQUEST);var investigation=read(id);
        var rows=jdbc.queryForList("""
            SELECT a.id,i.receipt_id,m.input_cursor,a.reference_at,a.reason,m.source_hash,
             (SELECT count(*) FROM deposit_monitoring_observations o WHERE o.assessment_id=a.id) observations
             FROM deposit_monitoring_case_assessments m JOIN deposit_monitoring_assessments a ON a.id=m.assessment_id
             JOIN deposit_monitoring_inputs i ON i.input_cursor=m.input_cursor
             WHERE m.case_id=? AND m.input_cursor<? ORDER BY m.input_cursor DESC LIMIT 26
            """,id,before==null?Long.MAX_VALUE:before);
        var items=rows.stream().limit(25).map(r->new Member((UUID)r.get("id"),(UUID)r.get("receipt_id"),r.get("input_cursor").toString(),((Timestamp)r.get("reference_at")).toInstant(),(String)r.get("reason"),(String)r.get("source_hash"),((Number)r.get("observations")).longValue())).toList();
        audit.record(actor,"MONITORING_CASE_READ","monitoring-case:"+id,"SUCCESS","METADATA_ONLY");return new Members(investigation,items,rows.size()>25?items.getLast().cursor():null);
    });}
    private Case read(UUID id){var rows=jdbc.queryForList("SELECT c.*,monitoring_case_closed(c.id) closed,(SELECT count(*) FROM deposit_monitoring_case_assessments m WHERE m.case_id=c.id) signals FROM deposit_monitoring_cases c WHERE c.id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);return map(rows.getFirst());}
    private Case map(Map<String,Object> r){return new Case((UUID)r.get("id"),((Number)r.get("user_id")).longValue(),(String)r.get("policy_id"),(UUID)r.get("first_assessment_id"),((Number)r.get("signals")).longValue(),((Timestamp)r.get("opened_at")).toInstant(),Boolean.TRUE.equals(r.get("closed"))?"CLOSED":"OPEN",ASSURANCE);}
    private void authorize(long actor,UUID sid){sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);}
    private <T>T work(long actor,String action,Supplier<T> callback){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("조사 사건 업무는 독립 트랜잭션으로 시작하세요");try{return tx.execute(t->callback.get());}catch(ResponseStatusException denied){audit.denied(actor,action,"monitoring-case-restricted","POLICY_OR_SOURCE_"+denied.getStatusCode().value());throw denied;}}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 운영 인증과 실제 거래 탐지 근거를 확인하세요");}
}
