package dustin.cex.domains.cdd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * 추가 확인 사건과 독립 심사를 담당한다. 고객의 재신고는 검토 이력을 지우지 못한다.
 * 수동 판단으로 부족한 신원·AML을 승인하지 않는다. 새 서명 확인으로 해소된 단순 정보 불일치만 제한적으로 종결한다.
 */
@Service
public class EddService {
    public enum Finding { AWAITING_EVIDENCE, COMPLIANCE_ESCALATION, CORRECTED_SIGNED_IDENTITY }
    public record Case(UUID id,long userId,long revision,UUID firstAssessment,UUID latestAssessment,String state,Instant validUntil,List<String> reviewReasons) {}
    /** 승인자가 볼 근거와 해시 입력이 같은 값이다. 개인정보 대신 실제 불변 평가와 서명 이벤트 식별자를 고정한다. */
    public record Payload(UUID id,UUID caseId,long caseRevision,long userId,UUID assessmentId,UUID eventId,UUID attemptId,
        String statusDigest,String keyId,String policyVersion,List<String> historicalReasons,Finding finding,Instant validUntil,
        long requesterId,UUID requesterPerson,long requesterVersion,Instant expiresAt) {}
    public record Report(Payload payload,String payloadHash,String status) {}
    public record Confirmation(Report report,boolean approve,OperatorMfaService.Options authentication) {}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy policy;
    private final OperatorMfaService mfa;private final AuditService audit;private final ObjectMapper json;
    private final DiditWebhookVerifier verifier;private final CddCrypto crypto;private final TransactionTemplate tx;
    public EddService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy policy,OperatorMfaService mfa,
            AuditService audit,ObjectMapper json,DiditWebhookVerifier verifier,CddCrypto crypto,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.policy=policy;this.mfa=mfa;this.audit=audit;this.json=json;
        this.verifier=verifier;this.crypto=crypto;tx=new TransactionTemplate(manager);
    }
    /** 호출한 CDD 서비스가 고객 행을 잠그고 평가·현재 포인터를 먼저 저장한 같은 트랜잭션에서 실행한다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void observe(long user,UUID assessment) {
        var prior=cases(user,false);
        if(prior==null) {
            var reviews=jdbc.queryForList("SELECT id FROM cdd_assessments WHERE user_id=? AND outcome='REVIEW_REQUIRED' ORDER BY assessed_at,id LIMIT 1",user);
            if(reviews.isEmpty()) return;
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO edd_cases(id,user_id,first_assessment,latest_assessment) VALUES(?,?,?,?)",id,user,reviews.getFirst().get("id"),assessment);
            audit.record(user,"EDD_CASE_OPENED","edd:"+id,"SUCCESS","REVIEW_HISTORY_RETAINED");
        } else {
            // 새 검증 사실은 옛 보고서와 종결의 효력을 없앤다. 중복 이벤트는 CDD에서 이미 배제돼 여기 오지 않는다.
            jdbc.update("UPDATE edd_cases SET latest_assessment=?,revision=revision+1,state='OPEN',valid_until=NULL WHERE id=?",assessment,prior.id());
            audit.record(user,"EDD_CASE_UPDATED","edd:"+prior.id(),"SUCCESS","NEW_ASSESSMENT");
        }
    }
    public Report propose(long actor,UUID sid,long user,UUID expectedAssessment,UUID request,Finding finding,Instant until) {
        return attempt(actor,"EDD_PROPOSE",()->tx.execute(status->{
            var locked=lock(actor,user);var maker=locked.get(actor);policy.requireFresh(maker,sid,UserRole.OPERATOR);
            customer(locked.get(user));if(request==null || finding==null || expectedAssessment==null) throw deny(HttpStatus.BAD_REQUEST);
            var old=jdbc.queryForList("SELECT id FROM edd_reports WHERE requester_id=? AND request_key=?",actor,request);
            if(!old.isEmpty()) {
                var report=read((UUID)old.getFirst().get("id"));var p=report.payload();
                if(p.userId()!=user || !p.assessmentId().equals(expectedAssessment) || p.finding()!=finding || !Objects.equals(p.validUntil(),micros(until))) throw deny(HttpStatus.CONFLICT);
                return report;
            }
            var assessment=current(user);if(!expectedAssessment.equals(assessment.get("id"))) throw deny(HttpStatus.CONFLICT);
            var c=cases(user,true);
            // 기존 실제 검토 이력이 있는 고객만 명시적으로 사건을 시작한다. 과거 고객의 승인 근거는 채워 넣지 않는다.
            if(c==null) {observe(user,expectedAssessment);c=cases(user,true);}
            if(c==null || !c.latestAssessment().equals(expectedAssessment)) throw deny(HttpStatus.CONFLICT);
            validateFinding(c,assessment,finding,until);
            if(jdbc.queryForObject("SELECT count(*) FROM edd_reports WHERE case_id=? AND payload::jsonb->>'caseRevision'=? AND payload::jsonb->>'assessmentId'=? AND expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM edd_decisions d WHERE d.report_id=edd_reports.id)",Long.class,c.id(),Long.toString(c.revision()),expectedAssessment.toString())>0)
                throw deny(HttpStatus.CONFLICT);
            Instant expires=micros(Instant.now().plusSeconds(600));
            var p=new Payload(UUID.randomUUID(),c.id(),c.revision(),user,expectedAssessment,(UUID)assessment.get("event_id"),(UUID)assessment.get("attempt_id"),
                (String)assessment.get("status_digest"),(String)assessment.get("key_id"),(String)assessment.get("policy_version"),c.reviewReasons(),finding,micros(until),
                actor,maker.getOperatorPersonId(),maker.getVersion(),expires);
            String hash=verifier.digest(json.valueToTree(p));
            jdbc.update("INSERT INTO edd_reports(id,case_id,user_id,requester_id,request_key,payload,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?)",
                p.id(),c.id(),user,actor,request,json.writeValueAsString(p),hash,Timestamp.from(expires));
            mfa.bindApproval(OperatorMfaService.ApprovalKind.EDD_REVIEW,p.id(),actor,maker.getVersion(),hash,expires);
            audit.record(actor,"EDD_PROPOSED","edd-report:"+p.id(),"SUCCESS",finding+":"+hash);return read(p.id());
        }));
    }
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve) {
        return attempt(actor,"EDD_OPTIONS",()->tx.execute(status->{
            var r=read(id);check(actor,sid,r,hash);
            return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.EDD_REVIEW,id,hash,approve));
        }));
    }
    public Report decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof) {
        return attempt(actor,"EDD_DECIDE",()->tx.execute(status->{
            var r=read(id);var c=check(actor,sid,r,hash);
            mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.EDD_REVIEW,id,hash,approve,proof);
            var checker=users.findById(actor).orElseThrow();
            jdbc.update("INSERT INTO edd_decisions(report_id,approver_id,approver_person,approved) VALUES(?,?,?,?)",id,actor,checker.getOperatorPersonId(),approve);
            if(approve) {
                String state=switch(r.payload().finding()) {case AWAITING_EVIDENCE->"OPEN";case COMPLIANCE_ESCALATION->"REFERRED";case CORRECTED_SIGNED_IDENTITY->"CLOSED";};
                jdbc.update("UPDATE edd_cases SET state=?,valid_until=?,revision=revision+1 WHERE id=?",state,
                    r.payload().validUntil()==null?null:Timestamp.from(r.payload().validUntil()),c.id());
            }
            audit.record(actor,"EDD_DECIDED","edd-report:"+id,"SUCCESS",(approve?"APPROVED:":"REJECTED:")+hash);return read(id);
        }));
    }
    private Case check(long actor,UUID sid,Report r,String hash) {
        var p=r.payload();var locked=lock(actor,p.requesterId(),p.userId());var checker=locked.get(actor);var maker=locked.get(p.requesterId());
        policy.requireFresh(checker,sid,UserRole.APPROVER);customer(locked.get(p.userId()));
        if(maker==null || !maker.isAuthenticationAllowed() || maker.getRole()!=UserRole.OPERATOR || maker.getVersion()!=p.requesterVersion()
                || !p.requesterPerson().equals(maker.getOperatorPersonId()) || actor==p.requesterId() || checker.getOperatorPersonId().equals(p.requesterPerson())) throw deny(HttpStatus.FORBIDDEN);
        var c=cases(p.userId(),true);var current=current(p.userId());
        if(!read(p.id()).status().equals("PENDING") || !r.payloadHash().equals(hash) || !verifier.digest(json.valueToTree(p)).equals(hash)
                || c==null || c.revision()!=p.caseRevision() || !c.id().equals(p.caseId()) || !p.assessmentId().equals(current.get("id"))
                || !p.assessmentId().equals(c.latestAssessment()) || !c.reviewReasons().equals(p.historicalReasons())) throw deny(HttpStatus.CONFLICT);
        validateFinding(c,current,p.finding(),p.validUntil());return c;
    }
    private void validateFinding(Case c,Map<String,Object> a,Finding finding,Instant until) {
        if(finding!=Finding.CORRECTED_SIGNED_IDENTITY) {if(until!=null) throw deny(HttpStatus.BAD_REQUEST);return;}
        // 추가 자료는 아직 사실 검증을 마치지 않았다. 단순 신원 정정 종결로 자료 검토를 건너뛰지 않는다.
        if(jdbc.queryForObject("SELECT count(*) FROM edd_evidence_requests WHERE case_id=?",Long.class,c.id())>0) throw deny(HttpStatus.CONFLICT);
        // PEP·제재 의심·실소유자·국가·목적·중복 신원은 신원 재제출만으로 해소되지 않는다.
        if(c.reviewReasons().isEmpty() || !c.reviewReasons().stream().allMatch("IDENTITY_MISMATCH"::equals)
                || !a.get("outcome").equals("STANDARD") || !CddService.POLICY.equals(a.get("policy_version")) || !crypto.keyId().equals(a.get("key_id"))) throw deny(HttpStatus.CONFLICT);
        if(until==null || !until.isAfter(Instant.now()) || until.isAfter(Instant.now().plusSeconds(86400))) throw deny(HttpStatus.CONFLICT);
        boolean verified=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM kyc_profiles k JOIN kyc_attempts t ON t.id=k.active_attempt
             JOIN kyc_reconciliations q ON q.attempt_id=t.id JOIN cdd_profiles p ON p.user_id=k.user_id
             WHERE k.user_id=? AND p.assessment_id=? AND p.declaration_id=? AND k.active_attempt=? AND k.status_digest=?
              AND k.identity_status='VERIFIED' AND k.valid_until>? AND t.environment='live'
              AND q.fresh_at>clock_timestamp()-interval '15 minutes'
              AND NOT EXISTS(SELECT 1 FROM cdd_assessments old WHERE old.user_id=k.user_id AND old.outcome='REVIEW_REQUIRED' AND old.attempt_id=t.id))
            """,Boolean.class,c.userId(),a.get("id"),a.get("declaration_id"),a.get("attempt_id"),a.get("status_digest"),Timestamp.from(until)));
        if(!verified) throw deny(HttpStatus.CONFLICT);
    }
    /** 현재 신고의 현재 평가만 사용한다. 과거 평가나 다른 고객의 행을 승인 근거로 받지 않는다. */
    private Map<String,Object> current(long user) {
        var rows=jdbc.queryForList("SELECT a.* FROM cdd_profiles p JOIN cdd_assessments a ON a.id=p.assessment_id AND a.user_id=p.user_id AND a.declaration_id=p.declaration_id WHERE p.user_id=?",user);
        if(rows.isEmpty()) throw deny(HttpStatus.CONFLICT);return rows.getFirst();
    }
    public Case status(long user) {return cases(user,false);}
    public List<Report> reports(long user,int page) {
        if(page<0 || page>10000) throw deny(HttpStatus.BAD_REQUEST);
        return jdbc.queryForList("SELECT id FROM edd_reports WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?",user,page*50).stream().map(row->read((UUID)row.get("id"))).toList();
    }
    private Case cases(long user,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM edd_cases WHERE user_id=?"+(lock?" FOR UPDATE":""),user);if(rows.isEmpty()) return null;
        var r=rows.getFirst();var reasons=jdbc.queryForList("SELECT DISTINCT unnest(string_to_array(reasons,',')) AS reason FROM cdd_assessments WHERE user_id=? AND outcome='REVIEW_REQUIRED' ORDER BY reason",String.class,user);
        return new Case((UUID)r.get("id"),user,((Number)r.get("revision")).longValue(),(UUID)r.get("first_assessment"),(UUID)r.get("latest_assessment"),(String)r.get("state"),r.get("valid_until")==null?null:((Timestamp)r.get("valid_until")).toInstant(),reasons);
    }
    private Report read(UUID id) {
        var rows=jdbc.queryForList("SELECT r.*,d.approved FROM edd_reports r LEFT JOIN edd_decisions d ON d.report_id=r.id WHERE r.id=?",id);
        if(rows.isEmpty()) throw deny(HttpStatus.NOT_FOUND);var row=rows.getFirst();
        var p=json.readValue((String)row.get("payload"),Payload.class);
        String status=row.get("approved")==null?(p.expiresAt().isAfter(Instant.now())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED");
        return new Report(p,(String)row.get("payload_hash"),status);
    }
    private Map<Long,User> lock(long... ids) {var result=new HashMap<Long,User>();Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(user->result.put(id,user)));return result;}
    private void customer(User user) {if(user==null || user.getRole()!=UserRole.CUSTOMER) throw deny(HttpStatus.CONFLICT);}
    private static Instant micros(Instant instant) {return instant==null?null:instant.truncatedTo(ChronoUnit.MICROS);}
    private <T>T attempt(long actor,String action,Supplier<T> work) {try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,action,"edd","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException deny(HttpStatus status) {return new ResponseStatusException(status,"검토 사건의 현재 근거·역할·추가 인증을 확인하세요");}
}
