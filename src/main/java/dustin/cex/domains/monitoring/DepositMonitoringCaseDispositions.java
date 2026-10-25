package dustin.cex.domains.monitoring;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 검토 의견과 실제 사건 처리를 구분한다. 종결·내부 보고 검토 회부는 새 요청과 그 내용에 대한 별도 서명이 필요하다. */
@Service
@DependsOn("depositMonitoringSchemaInitializer")
public class DepositMonitoringCaseDispositions {
    public static final String POLICY="TM_CASE_DISPOSITION_V1";
    public static final String ASSURANCE="INTERNAL_CASE_ACTION_NOT_EXTERNAL_REPORT_OR_FINANCIAL_PERMISSION";
    public enum Action {CLOSE_EXPLAINED,REFER_FOR_REPORTING_REVIEW}
    public record Payload(UUID id,UUID reviewId,String reviewHash,UUID caseId,long customerId,String scopeVersion,Action action,
        long requesterId,long checkerId,String policy,String assurance,Instant expiresAt){}
    public record Request(Payload payload,String payloadHash,String status){}
    public record Confirmation(Request request,boolean approve,OperatorMfaService.Options authentication){}
    /** 목록에는 비공개 사유·탐지 규칙·신고 판단 원문을 넣지 않는다. 외부 신고 접수 상태로 해석하지 않는다. */
    public record Referral(UUID requestId,UUID reviewId,UUID caseId,long customerId,Instant referredAt,String status,String assurance){}
    private final JdbcTemplate jdbc;private final DepositMonitoringCaseReviews reviews;private final UserRepository users;
    private final OperatorSessionPolicy sessions;private final OperatorMfaService mfa;private final AuditService audit;
    private final ObjectMapper json;private final Clock clock;private final TransactionTemplate tx;
    public DepositMonitoringCaseDispositions(JdbcTemplate jdbc,DepositMonitoringCaseReviews reviews,UserRepository users,OperatorSessionPolicy sessions,OperatorMfaService mfa,AuditService audit,ObjectMapper json,@Qualifier("monitoringReviewClock") Clock clock,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.reviews=reviews;this.users=users;this.sessions=sessions;this.mfa=mfa;this.audit=audit;this.json=json;this.clock=clock;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 같은 두 인물이 확인한 판단만 사용한다. 전부 검토되지 않은 새 신호나 의견이 있으면 먼저 다시 조사해야 한다. */
    public Request propose(long actor,UUID sid,UUID reviewId,Action action,UUID key){return work(actor,"MONITORING_CASE_ACTION_PROPOSE",()->{
        var inspected=reviews.dispositionEvidence(actor,sid,reviewId);var review=inspected.review();var p=review.payload();
        if(actor!=p.requesterId())throw denied(HttpStatus.FORBIDDEN);if(action==null||key==null)throw denied(HttpStatus.BAD_REQUEST);
        if((action==Action.CLOSE_EXPLAINED&&inspected.facts().finding()!=DepositMonitoringCaseReviews.Finding.EXPLAINED_ACTIVITY)
          ||(action==Action.REFER_FOR_REPORTING_REVIEW&&inspected.facts().finding()!=DepositMonitoringCaseReviews.Finding.REFER_FOR_REPORTING_REVIEW))throw denied(HttpStatus.CONFLICT);
        var old=jdbc.queryForList("SELECT id FROM deposit_monitoring_case_actions WHERE requester_id=? AND request_key=?",UUID.class,actor,key);
        if(!old.isEmpty()){var request=read(old.getFirst());if(!request.payload().reviewId().equals(reviewId)||request.payload().action()!=action)throw denied(HttpStatus.CONFLICT);audit.record(actor,"MONITORING_CASE_ACTION_REPLAYED","monitoring-case-action:"+request.payload().id(),"SUCCESS",request.payloadHash());return request;}
        requireOpen(p.caseId());
        UUID id=UUID.randomUUID();Instant expiry=clock.instant().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        var payload=new Payload(id,reviewId,review.payloadHash(),p.caseId(),p.userId(),p.scope().version(),action,actor,p.checkerId(),POLICY,ASSURANCE,expiry);
        String raw=json.writeValueAsString(payload),hash=hash(raw);
        jdbc.update("INSERT INTO deposit_monitoring_case_actions(id,review_id,case_id,user_id,requester_id,checker_id,request_key,action,payload,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,reviewId,p.caseId(),p.userId(),actor,p.checkerId(),key,action.name(),raw,hash,Timestamp.from(expiry));
        mfa.bindApproval(OperatorMfaService.ApprovalKind.TM_CASE_ACTION,id,actor,p.requesterVersion(),hash,expiry);
        audit.record(actor,"MONITORING_CASE_ACTION_PROPOSED","monitoring-case-action:"+id,"SUCCESS",hash);return read(id);
    });}
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve){return work(actor,"MONITORING_CASE_ACTION_OPTIONS",()->{
        var request=check(actor,sid,id,hash);return new Confirmation(request,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.TM_CASE_ACTION,id,hash,approve));
    });}
    /** 인증 소비·결정·종결 또는 회부·감사를 하나의 금융 외부 효과 없는 트랜잭션으로 확정한다. */
    public Request decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof){return work(actor,"MONITORING_CASE_ACTION_DECIDE",()->{
        check(actor,sid,id,hash);mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.TM_CASE_ACTION,id,hash,approve,proof);
        jdbc.update("INSERT INTO deposit_monitoring_case_action_decisions(request_id,approver_id,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?)",id,actor,sid,approve,hash,proof.challengeId());
        audit.record(actor,"MONITORING_CASE_ACTION_DECIDED","monitoring-case-action:"+id,"SUCCESS",hash);return read(id);
    });}
    public List<Referral> referrals(long actor,UUID sid,int page){return work(actor,"MONITORING_REPORT_REFERRALS_READ",()->{
        sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);
        var result=jdbc.queryForList("SELECT r.id,r.review_id,r.case_id,r.user_id,d.decided_at FROM deposit_monitoring_case_actions r JOIN deposit_monitoring_case_action_decisions d ON d.request_id=r.id AND d.approved WHERE r.action='REFER_FOR_REPORTING_REVIEW' ORDER BY d.decided_at DESC,r.id DESC LIMIT 25 OFFSET ?",page*25).stream().map(r->new Referral((UUID)r.get("id"),(UUID)r.get("review_id"),(UUID)r.get("case_id"),((Number)r.get("user_id")).longValue(),((Timestamp)r.get("decided_at")).toInstant(),"AWAITING_REPORTING_REVIEW",ASSURANCE)).toList();
        audit.record(actor,"MONITORING_REPORT_REFERRALS_READ","monitoring-report-referrals","SUCCESS","METADATA_ONLY");return result;
    });}
    private Request check(long actor,UUID sid,UUID id,String hash){
        var initial=read(id);var p=initial.payload();var source=reviews.dispositionEvidence(actor,sid,p.reviewId());
        if(actor!=p.checkerId())throw denied(HttpStatus.FORBIDDEN);
        // 고객 잠금 뒤 미결 상태를 다시 읽는다. 병렬 승인·반려는 하나만 확정할 수 있다.
        var current=read(id);if(!current.status().equals("PENDING")||!Objects.equals(hash,current.payloadHash())||!p.reviewHash().equals(source.review().payloadHash())||!POLICY.equals(p.policy())||!ASSURANCE.equals(p.assurance()))throw denied(HttpStatus.CONFLICT);
        requireOpen(p.caseId());return current;
    }
    private void requireOpen(UUID id){
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deposit_monitoring_case_actions a JOIN deposit_monitoring_case_action_decisions d ON d.request_id=a.id AND d.approved WHERE a.case_id=? AND (a.action='REFER_FOR_REPORTING_REVIEW' OR monitoring_case_closed(a.case_id)))",Boolean.class,id)))throw denied(HttpStatus.CONFLICT);
    }
    private Request read(UUID id){var rows=jdbc.queryForList("SELECT a.payload,a.payload_hash,d.approved FROM deposit_monitoring_case_actions a LEFT JOIN deposit_monitoring_case_action_decisions d ON d.request_id=a.id WHERE a.id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String raw=(String)row.get("payload");var p=json.readValue(raw,Payload.class);if(!p.id().equals(id)||!hash(raw).equals(row.get("payload_hash")))throw denied(HttpStatus.SERVICE_UNAVAILABLE);String state=row.get("approved")==null?(p.expiresAt().isAfter(clock.instant())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED");return new Request(p,(String)row.get("payload_hash"),state);}
    private String hash(String raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("사건 후속 업무의 지문을 만들 수 없습니다");}}
    private <T>T work(long actor,String action,Supplier<T> callback){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("사건 처리는 독립 트랜잭션으로 시작하세요");try{return tx.execute(t->callback.get());}catch(ResponseStatusException rejected){audit.denied(actor,action,"monitoring-case-action-restricted","POLICY_OR_SOURCE_"+rejected.getStatusCode().value());throw rejected;}}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 인물·승인 근거·전체 사건 범위·후속 업무를 확인하세요");}
}
