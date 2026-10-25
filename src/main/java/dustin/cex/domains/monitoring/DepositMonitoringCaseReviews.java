package dustin.cex.domains.monitoring;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.edd.EvidenceCrypto;
import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.text.Normalizer;
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

/** 명시한 신호 구간의 비공개 판단을 서로 다른 두 인물이 확인한다. 승인 이력은 사건 종결·외부 보고·자금 사용 허가가 아니다. */
@Service
@DependsOn("depositMonitoringSchemaInitializer")
public class DepositMonitoringCaseReviews {
    public static final String POLICY="TM_CASE_REVIEW_V1";
    public static final String ASSURANCE="INTERNAL_CASE_REVIEW_NOT_CLOSURE_REPORT_OR_FINANCIAL_PERMISSION";
    public enum Finding {EXPLAINED_ACTIVITY,REQUIRES_FURTHER_REVIEW,REFER_FOR_REPORTING_REVIEW}
    public record Facts(Finding finding,String rationale){
        Facts validated(){
            if(finding==null||rationale==null||rationale.length()>2000)throw denied(HttpStatus.BAD_REQUEST);String text=Normalizer.normalize(rationale,Normalizer.Form.NFC).strip();
            if(text.isBlank()||text.length()>2000||text.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE))throw denied(HttpStatus.BAD_REQUEST);
            return new Facts(finding,text);
        }
        @Override public String toString(){return "CaseReviewFacts[비공개 판단 생략]";}
    }
    public record Payload(UUID id,UUID caseId,long userId,CaseReviewEvidence.Position scope,String policy,String assurance,
        String factsMac,String evidenceKeyId,String cddKeyId,long requesterId,UUID requesterPerson,long requesterVersion,
        long checkerId,UUID checkerPerson,long checkerVersion,Instant expiresAt){}
    public record Review(Payload payload,String payloadHash,String status){}
    public record Inspection(Review review,Facts facts){@Override public String toString(){return "CaseReviewInspection[비공개 판단 생략]";}}
    public record Confirmation(Review review,boolean approve,OperatorMfaService.Options authentication){}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final CaseReviewEvidence evidence;private final EvidenceCrypto crypto;private final CddCrypto cdd;private final ObjectMapper json;
    private final OperatorMfaService mfa;private final AuditService audit;private final Clock clock;private final TransactionTemplate tx;
    public DepositMonitoringCaseReviews(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,CaseReviewEvidence evidence,EvidenceCrypto crypto,CddCrypto cdd,ObjectMapper json,OperatorMfaService mfa,AuditService audit,@Qualifier("monitoringReviewClock") Clock clock,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.evidence=evidence;this.crypto=crypto;this.cdd=cdd;this.json=json;this.mfa=mfa;this.audit=audit;this.clock=clock;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 실제 입금이 계속 들어와도 이미 정한 접두 구간까지의 검토를 명시적으로 준비할 수 있다. */
    public CaseReviewEvidence.Position context(long actor,UUID sid,UUID id,Long through){return work(actor,"MONITORING_CASE_REVIEW_CONTEXT",()->{
        var locked=casePeople(id,actor);sessions.requireFresh(locked.get(actor),sid,UserRole.OPERATOR,UserRole.APPROVER);requireCase(id);
        var p=evidence.position(id,through);audit.record(actor,"MONITORING_CASE_REVIEW_CONTEXT","monitoring-case:"+id,"SUCCESS",p.version());return p;
    });}
    public Review propose(long actor,UUID sid,UUID id,long checker,UUID key,CaseReviewEvidence.Position expected,Facts supplied){return work(actor,"MONITORING_CASE_REVIEW_PROPOSE",()->{
        var locked=casePeople(id,actor,checker);var maker=locked.get(actor);sessions.requireFresh(maker,sid,UserRole.OPERATOR);requireCase(id);var reviewer=locked.get(checker);
        if(key==null||expected==null||supplied==null||!id.equals(expected.caseId()))throw denied(HttpStatus.BAD_REQUEST);
        if(reviewer==null||reviewer.getRole()!=UserRole.APPROVER||!reviewer.isAuthenticationAllowed()||reviewer.getOperatorPersonId()==null||actor==checker||maker.getOperatorPersonId().equals(reviewer.getOperatorPersonId()))throw denied(HttpStatus.FORBIDDEN);
        // 클라이언트가 보낸 위치 토큰도 먼저 크기·필수 필드를 확인한다. 원본과의 일치는 아래 실제 SQL 결과로 검증한다.
        if(expected.version()==null||!expected.version().matches("[a-f0-9]{64}")||expected.policy()==null||expected.policy().isBlank()||expected.policy().length()>50||expected.customerId()<=0||expected.signals()<=0||expected.opinions()<0)throw denied(HttpStatus.BAD_REQUEST);
        CaseReviewEvidence.cursor(expected);
        var facts=supplied.validated();crypto.requireConfigured();String mac=fingerprint(expected,facts);
        var old=jdbc.queryForList("SELECT id FROM deposit_monitoring_case_reviews WHERE requester_id=? AND request_key=?",UUID.class,actor,key);
        if(!old.isEmpty()){
            var r=read(old.getFirst());assigned(actor,sid,r,locked);
            if(!r.payload().caseId().equals(id)||r.payload().checkerId()!=checker||!r.payload().scope().equals(expected)||!r.payload().factsMac().equals(mac))throw denied(HttpStatus.CONFLICT);
            facts(r);audit.record(actor,"MONITORING_CASE_REVIEW_REPLAYED","monitoring-case-review:"+r.payload().id(),"SUCCESS",r.payloadHash());return r;
        }
        var current=evidence.position(id,CaseReviewEvidence.cursor(expected));if(!current.equals(expected)||current.opinions()<1)throw denied(HttpStatus.CONFLICT);evidence.requireViewed(maker,sid,current);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deposit_monitoring_case_reviews r LEFT JOIN deposit_monitoring_case_review_decisions d ON d.review_id=r.id WHERE r.case_id=? AND r.scope_version=? AND(d.approved=true OR(d.review_id IS NULL AND r.expires_at>?)))",Boolean.class,id,current.version(),Timestamp.from(clock.instant()))))throw denied(HttpStatus.CONFLICT);
        UUID review=UUID.randomUUID();Instant expires=clock.instant().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        var payload=new Payload(review,id,current.customerId(),current,POLICY,ASSURANCE,mac,crypto.keyId(),cdd.keyId(),actor,maker.getOperatorPersonId(),maker.getVersion(),checker,reviewer.getOperatorPersonId(),reviewer.getVersion(),expires);
        String raw=json.writeValueAsString(payload),hash=hash(raw);byte[] cipher=crypto.encrypt(context(review,hash),json.writeValueAsBytes(facts));
        jdbc.update("INSERT INTO deposit_monitoring_case_reviews(id,case_id,user_id,requester_id,checker_id,request_key,through_cursor,scope_version,payload,payload_hash,facts_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",review,id,current.customerId(),actor,checker,key,CaseReviewEvidence.cursor(current),current.version(),raw,hash,cipher,Timestamp.from(expires));
        mfa.bindApproval(OperatorMfaService.ApprovalKind.TM_CASE_REVIEW,review,actor,maker.getVersion(),hash,expires);
        audit.record(actor,"MONITORING_CASE_REVIEW_PROPOSED","monitoring-case-review:"+review,"SUCCESS",hash);return read(review);
    });}
    /** 만료·역사 자료도 지정된 현재 두 인물만 열람한다. 조회 이력은 현재 판단 내용의 해시에 결합한다. */
    public Inspection inspect(long actor,UUID sid,UUID id){return work(actor,"MONITORING_CASE_REVIEW_INSPECT",()->{
        // 불변 요청으로 잠글 사용자만 파악한다. 역할·버전은 정렬된 잠금에서 처음 읽고 원문은 인증 뒤에 복호화한다.
        var r=read(id);var people=lock(actor,r.payload().userId());var person=assigned(actor,sid,r,people);var content=facts(r);UUID view=UUID.randomUUID();
        jdbc.update("INSERT INTO deposit_monitoring_case_review_views(id,review_id,actor_id,actor_person,actor_version,session_id,payload_hash) VALUES(?,?,?,?,?,?,?)",view,id,actor,person.getOperatorPersonId(),person.getVersion(),sid,r.payloadHash());
        audit.record(actor,"MONITORING_CASE_REVIEW_VIEWED","monitoring-case-review-view:"+view,"SUCCESS",r.payloadHash());return new Inspection(r,content);
    });}
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve){return work(actor,"MONITORING_CASE_REVIEW_OPTIONS",()->{
        var r=read(id);check(actor,sid,r,hash);return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.TM_CASE_REVIEW,id,hash,approve));
    });}
    public Review decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof){return work(actor,"MONITORING_CASE_REVIEW_DECIDE",()->{
        var r=read(id);var checker=check(actor,sid,r,hash);
        mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.TM_CASE_REVIEW,id,hash,approve,proof);
        jdbc.update("INSERT INTO deposit_monitoring_case_review_decisions(review_id,approver_id,approver_person,approver_version,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?,?,?)",id,actor,checker.getOperatorPersonId(),checker.getVersion(),sid,approve,hash,proof.challengeId());
        audit.record(actor,"MONITORING_CASE_REVIEW_DECIDED","monitoring-case-review:"+id,"SUCCESS",(approve?"APPROVED_INTERNAL_ONLY:":"REJECTED:")+hash);return read(id);
    });}
    public List<Review> list(long actor,UUID sid,int page){return work(actor,"MONITORING_CASE_REVIEWS_LIST",()->{
        var people=lock(actor);var person=people.get(actor);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);
        var ids=jdbc.queryForList("SELECT id FROM deposit_monitoring_case_reviews WHERE(requester_id=? AND payload::jsonb->>'requesterPerson'=?) OR(checker_id=? AND payload::jsonb->>'checkerPerson'=?) ORDER BY created_at DESC,id DESC LIMIT 25 OFFSET ?",UUID.class,actor,person.getOperatorPersonId().toString(),actor,person.getOperatorPersonId().toString(),page*25);
        var result=ids.stream().map(this::read).toList();audit.record(actor,"MONITORING_CASE_REVIEWS_LISTED","operator:"+actor,"SUCCESS","ASSIGNED_ONLY");return result;
    });}
    private User check(long actor,UUID sid,Review r,String hash){
        var p=r.payload();var people=lock(actor,p.requesterId(),p.checkerId(),p.userId());var checker=assigned(actor,sid,r,people);var maker=people.get(p.requesterId());sessions.requireFresh(checker,sid,UserRole.APPROVER);
        if(actor!=p.checkerId()||checker.getVersion()!=p.checkerVersion()||maker==null||maker.getRole()!=UserRole.OPERATOR||!maker.isAuthenticationAllowed()||maker.getVersion()!=p.requesterVersion()||!p.requesterPerson().equals(maker.getOperatorPersonId())||p.requesterPerson().equals(checker.getOperatorPersonId()))throw denied(HttpStatus.FORBIDDEN);
        if(!read(p.id()).status().equals("PENDING")||!Objects.equals(hash,r.payloadHash())||!POLICY.equals(p.policy())||!ASSURANCE.equals(p.assurance()))throw denied(HttpStatus.CONFLICT);
        if(!evidence.position(p.caseId(),CaseReviewEvidence.cursor(p.scope())).equals(p.scope()))throw denied(HttpStatus.CONFLICT);
        facts(r);evidence.requireViewed(checker,sid,p.scope());
        boolean viewed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deposit_monitoring_case_review_views WHERE review_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND viewed_at>clock_timestamp()-interval '5 minutes')",Boolean.class,p.id(),actor,checker.getOperatorPersonId(),checker.getVersion(),sid,hash));
        if(!viewed)throw denied(HttpStatus.CONFLICT);return checker;
    }
    private User assigned(long actor,UUID sid,Review r,Map<Long,User> people){var person=people.get(actor);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);var p=r.payload();if((actor==p.requesterId()&&p.requesterPerson().equals(person.getOperatorPersonId()))||(actor==p.checkerId()&&p.checkerPerson().equals(person.getOperatorPersonId())))return person;throw denied(HttpStatus.NOT_FOUND);}
    /** 후속 업무는 별도 내용 결합 인증을 사용한다. 이 메서드는 기존 승인의 현재 근거만 검증하며 종결을 실행하지 않는다. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Inspection dispositionEvidence(long actor,UUID sid,UUID id){
        // 요청은 불변이므로 식별자만 먼저 읽는다. 현재 역할·인물은 모든 관련 사용자 잠금 뒤 처음 검증한다.
        var r=read(id);var p=r.payload();var people=lock(actor,p.requesterId(),p.checkerId(),p.userId());var person=assigned(actor,sid,r,people);
        var maker=people.get(p.requesterId());var checker=people.get(p.checkerId());
        if(maker==null||checker==null||maker.getRole()!=UserRole.OPERATOR||checker.getRole()!=UserRole.APPROVER||!maker.isAuthenticationAllowed()||!checker.isAuthenticationAllowed()
            ||maker.getVersion()!=p.requesterVersion()||checker.getVersion()!=p.checkerVersion()||!p.requesterPerson().equals(maker.getOperatorPersonId())||!p.checkerPerson().equals(checker.getOperatorPersonId()))throw denied(HttpStatus.FORBIDDEN);
        // 의견의 부분 구간 승인과 사건 전체 실행은 다르다. 새 신호나 의견이 있으면 후속 실행을 준비하지 않는다.
        if(!read(id).status().equals("APPROVED")||!evidence.position(p.caseId(),null).equals(p.scope()))throw denied(HttpStatus.CONFLICT);
        evidence.requireViewed(person,sid,p.scope());var content=facts(r);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deposit_monitoring_case_review_views WHERE review_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND viewed_at>clock_timestamp()-interval '5 minutes')",Boolean.class,id,actor,person.getOperatorPersonId(),person.getVersion(),sid,r.payloadHash())))throw denied(HttpStatus.CONFLICT);
        return new Inspection(r,content);
    }
    private Facts facts(Review r){try{
        var p=r.payload();if(!crypto.keyId().equals(p.evidenceKeyId())||!cdd.keyId().equals(p.cddKeyId()))throw new IllegalStateException();
        byte[] cipher=jdbc.queryForObject("SELECT facts_cipher FROM deposit_monitoring_case_reviews WHERE id=?",byte[].class,p.id());var facts=json.readValue(crypto.decrypt(p.evidenceKeyId(),context(p.id(),r.payloadHash()),cipher),Facts.class).validated();
        if(!p.factsMac().equals(fingerprint(p.scope(),facts)))throw new IllegalStateException();return facts;
    }catch(RuntimeException invalid){throw denied(HttpStatus.SERVICE_UNAVAILABLE);}}
    private Review read(UUID id){var rows=jdbc.queryForList("SELECT r.payload,r.payload_hash,d.approved FROM deposit_monitoring_case_reviews r LEFT JOIN deposit_monitoring_case_review_decisions d ON d.review_id=r.id WHERE r.id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String raw=(String)row.get("payload");var p=json.readValue(raw,Payload.class);if(!p.id().equals(id)||!hash(raw).equals(row.get("payload_hash")))throw denied(HttpStatus.SERVICE_UNAVAILABLE);String state=row.get("approved")==null?(p.expiresAt().isAfter(clock.instant())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED");return new Review(p,(String)row.get("payload_hash"),state);}
    private String fingerprint(CaseReviewEvidence.Position p,Facts facts){return cdd.fingerprint("tm-case-review-facts",json.writeValueAsString(List.of(p.caseId(),p.customerId(),p.version(),facts)));}
    private String context(UUID id,String hash){return "TM_CASE_REVIEW_V1:"+id+":"+hash;}
    private String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("판단 근거 지문을 만들 수 없습니다");}}
    private Map<Long,User> casePeople(UUID id,long... actors){var owner=jdbc.queryForList("SELECT user_id FROM deposit_monitoring_cases WHERE id=?",Long.class,id);long[] ids=Arrays.copyOf(actors,actors.length+owner.size());if(!owner.isEmpty())ids[actors.length]=owner.getFirst();return lock(ids);}
    private void requireCase(UUID id){if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deposit_monitoring_cases WHERE id=?)",Boolean.class,id)))throw denied(HttpStatus.NOT_FOUND);}
    private Map<Long,User> lock(long... ids){var result=new HashMap<Long,User>();Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(user->result.put(id,user)));return result;}
    private <T>T work(long actor,String action,Supplier<T> callback){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("사건 판단은 독립 트랜잭션으로 시작하세요");try{return tx.execute(t->callback.get());}catch(ResponseStatusException rejected){audit.denied(actor,action,"monitoring-review-restricted","POLICY_OR_SOURCE_"+rejected.getStatusCode().value());throw rejected;}}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 지정 인물·조사 범위·열람·서명 근거를 확인하세요");}
}
