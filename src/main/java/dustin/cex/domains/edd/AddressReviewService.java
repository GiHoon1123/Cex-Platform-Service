package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * 주소 문서의 내용과 현재 신고를 두 사람이 대조한다. 관찰한 사실과 독립 판단의 이력을 보존한다.
 * 문서 진위·실제 거주·자금 원천·PEP/제재 해소를 자동 확인하거나 거래를 허용하는 서비스가 아니다.
 */
@Service
public class AddressReviewService {
    public static final String POLICY="EDD_ADDRESS_DOCUMENT_V1";
    public record Context(UUID evidenceId,UUID viewId,UUID caseId,long caseRevision,UUID assessmentId,UUID declarationId,String declaredName,String declaredAddress) {
        @Override public String toString() {return "Context[개인정보 생략]";}
    }
    public record Payload(UUID id,UUID evidenceId,long userId,UUID caseId,long caseRevision,UUID assessmentId,UUID declarationId,
        UUID requesterView,String factsMac,String evidenceKeyId,String cddKeyId,String policy,List<AddressFacts.Reason> reasons,
        long requesterId,UUID requesterPerson,long requesterVersion,Instant expiresAt) {}
    public record Review(Payload payload,String payloadHash,String status) {}
    public record Inspection(Review review,Context context,AddressFacts facts) {
        @Override public String toString() {return "Inspection[개인정보 생략]";}
    }
    public record Confirmation(Review review,boolean approve,OperatorMfaService.Options authentication) {}
    private record Current(UUID caseId,long revision,long userId,UUID assessment,UUID declaration,String name,String address) {}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final EvidenceCrypto crypto;private final CddCrypto cdd;private final ObjectMapper json;private final DiditWebhookVerifier verifier;
    private final OperatorMfaService mfa;private final AuditService audit;private final TransactionTemplate tx;
    public AddressReviewService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,EvidenceCrypto crypto,CddCrypto cdd,
            ObjectMapper json,DiditWebhookVerifier verifier,OperatorMfaService mfa,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.crypto=crypto;this.cdd=cdd;this.json=json;this.verifier=verifier;this.mfa=mfa;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    /** 이미 제공받은 열람본과 같은 현재 신고의 이름·주소만 반환한다. 생일·직업·신원 번호는 노출하지 않는다. */
    public Context context(long actor,UUID sid,UUID evidence,UUID view) {
        return attempt(actor,"EDD_ADDRESS_CONTEXT",()->tx.execute(s->{
            long user=owner(evidence);var locked=lock(actor,user);var operator=locked.get(actor);
            sessions.requireFresh(operator,sid,UserRole.OPERATOR,UserRole.APPROVER);customer(locked.get(user));
            var current=current(evidence);view(operator,sid,evidence,view,current);
            audit.record(actor,"EDD_ADDRESS_CONTEXT_READ","edd-evidence:"+evidence,"SUCCESS","NAME_ADDRESS_ONLY");return context(evidence,view,current);
        }));
    }
    public Review propose(long actor,UUID sid,UUID evidence,UUID view,UUID assessment,long revision,UUID key,AddressFacts supplied) {
        return attempt(actor,"EDD_ADDRESS_PROPOSE",()->tx.execute(s->{
            long user=owner(evidence);var locked=lock(actor,user);var maker=locked.get(actor);sessions.requireFresh(maker,sid,UserRole.OPERATOR);customer(locked.get(user));
            if(supplied==null || key==null || assessment==null || view==null) throw denied(HttpStatus.BAD_REQUEST);
            var facts=supplied.validated();String mac=fingerprint(facts);
            var old=jdbc.queryForList("SELECT id FROM edd_address_reviews WHERE requester_id=? AND request_key=?",actor,key);
            if(!old.isEmpty()) {
                var r=read((UUID)old.getFirst().get("id"));var p=r.payload();
                if(!p.evidenceId().equals(evidence) || !p.requesterView().equals(view) || !p.assessmentId().equals(assessment) || p.caseRevision()!=revision || !p.factsMac().equals(mac)) throw denied(HttpStatus.CONFLICT);
                return r;
            }
            var current=current(evidence);view(maker,sid,evidence,view,current);
            if(current.revision()!=revision || !current.assessment().equals(assessment)) throw denied(HttpStatus.CONFLICT);
            // 한 사건의 같은 사실에는 미결 검토 하나만 만든다. 옛 변경 번호의 미결 이력이 새 검토를 막지는 않는다.
            if(jdbc.queryForObject("SELECT count(*) FROM edd_address_reviews r WHERE r.case_id=? AND r.payload::jsonb->>'caseRevision'=? AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM edd_address_decisions d WHERE d.review_id=r.id)",Long.class,current.caseId(),Long.toString(revision))>0) throw denied(HttpStatus.CONFLICT);
            if(jdbc.queryForObject("SELECT count(*) FROM edd_address_reviews WHERE user_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,user)>=10) throw denied(HttpStatus.TOO_MANY_REQUESTS);
            UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
            var p=new Payload(id,evidence,user,current.caseId(),revision,assessment,current.declaration(),view,mac,crypto.keyId(),cdd.keyId(),POLICY,
                facts.compare(current.name(),current.address(),today()),actor,maker.getOperatorPersonId(),maker.getVersion(),expires);
            String hash=verifier.digest(json.valueToTree(p));byte[] encrypted=crypto.encrypt(factsContext(p),json.writeValueAsBytes(facts));
            jdbc.update("INSERT INTO edd_address_reviews(id,case_id,evidence_id,user_id,requester_id,request_key,payload,payload_hash,facts_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,current.caseId(),evidence,user,actor,key,json.writeValueAsString(p),hash,encrypted,Timestamp.from(expires));
            mfa.bindApproval(OperatorMfaService.ApprovalKind.EDD_ADDRESS_REVIEW,id,actor,maker.getVersion(),hash,expires);
            audit.record(actor,"EDD_ADDRESS_PROPOSED","edd-address-review:"+id,"SUCCESS",p.reasons().isEmpty()?"CONTENT_CONSISTENT":"FURTHER_CHECK_REQUIRED");return read(id);
        }));
    }
    /** 승인자는 자신에게 제공된 열람본과 신고·작성자가 관찰한 내용을 같은 자리에서 대조한다. 열람도 감사 대상이다. */
    public Inspection inspect(long actor,UUID sid,UUID id,UUID view) {
        return attempt(actor,"EDD_ADDRESS_INSPECT",()->tx.execute(s->{
            var review=read(id);var current=check(actor,sid,review,review.payloadHash(),view);
            var facts=facts(review);audit.record(actor,"EDD_ADDRESS_FACTS_READ","edd-address-review:"+id,"SUCCESS","INDEPENDENT_INSPECTION");
            return new Inspection(review,context(review.payload().evidenceId(),view,current),facts);
        }));
    }
    public Confirmation options(long actor,UUID sid,UUID id,String hash,UUID view,boolean approve,AddressFacts observed) {
        return attempt(actor,"EDD_ADDRESS_OPTIONS",()->tx.execute(s->{
            var r=read(id);check(actor,sid,r,hash,view);independent(r,approve,observed);
            return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.EDD_ADDRESS_REVIEW,id,hash,approve));
        }));
    }
    public Review decide(long actor,UUID sid,UUID id,String hash,UUID view,boolean approve,AddressFacts observed,OperatorMfaService.AuthorizationProof proof) {
        return attempt(actor,"EDD_ADDRESS_DECIDE",()->tx.execute(s->{
            var r=read(id);var current=check(actor,sid,r,hash,view);independent(r,approve,observed);
            mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.EDD_ADDRESS_REVIEW,id,hash,approve,proof);
            var checker=users.findById(actor).orElseThrow();
            jdbc.update("INSERT INTO edd_address_decisions(review_id,approver_id,approver_person,view_id,approved) VALUES(?,?,?,?,?)",id,actor,checker.getOperatorPersonId(),view,approve);
            // 검토 결과도 새로운 사실이다. 오래된 사건 결론은 무효화하되 금융 위험을 해소한 것으로 취급하지 않는다.
            jdbc.update("UPDATE edd_cases SET revision=revision+1,state='OPEN',valid_until=NULL WHERE id=?",current.caseId());
            audit.record(actor,"EDD_ADDRESS_DECIDED","edd-address-review:"+id,"SUCCESS",(approve?"APPROVED_CONTENT_REVIEW:":"REJECTED:")+hash);return read(id);
        }));
    }
    public List<Review> list(long actor,UUID sid,long user,int page) {
        return attempt(actor,"EDD_ADDRESS_LIST",()->tx.execute(s->{
            var locked=lock(actor,user);sessions.requireFresh(locked.get(actor),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);customer(locked.get(user));
            if(page<0 || page>10000) throw denied(HttpStatus.BAD_REQUEST);
            audit.record(actor,"EDD_ADDRESS_REVIEWS_LISTED","customer:"+user,"SUCCESS","METADATA_ONLY");
            return jdbc.queryForList("SELECT id FROM edd_address_reviews WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?",user,page*50).stream().map(r->read((UUID)r.get("id"))).toList();
        }));
    }
    private Current check(long actor,UUID sid,Review r,String hash,UUID viewed) {
        var p=r.payload();var locked=lock(actor,p.requesterId(),p.userId());var checker=locked.get(actor);var maker=locked.get(p.requesterId());
        sessions.requireFresh(checker,sid,UserRole.APPROVER);customer(locked.get(p.userId()));
        if(maker==null || !maker.isAuthenticationAllowed() || maker.getRole()!=UserRole.OPERATOR || maker.getVersion()!=p.requesterVersion() || !p.requesterPerson().equals(maker.getOperatorPersonId())
                || actor==p.requesterId() || checker.getOperatorPersonId().equals(p.requesterPerson())) throw denied(HttpStatus.FORBIDDEN);
        var current=current(p.evidenceId());view(checker,sid,p.evidenceId(),viewed,current);
        // 사용자·사건 잠금을 얻은 뒤 결정 상태를 다시 읽어 동시 승인/거절의 두 번째 실행을 거부한다.
        if(!read(p.id()).status().equals("PENDING") || !r.payloadHash().equals(hash) || !POLICY.equals(p.policy()) || !p.evidenceKeyId().equals(crypto.keyId()) || !p.cddKeyId().equals(cdd.keyId())
                || !p.caseId().equals(current.caseId()) || p.caseRevision()!=current.revision() || !p.assessmentId().equals(current.assessment()) || !p.declarationId().equals(current.declaration())) throw denied(HttpStatus.CONFLICT);
        var facts=facts(r);
        if(!facts.compare(current.name(),current.address(),today()).equals(p.reasons())) throw denied(HttpStatus.CONFLICT);
        return current;
    }
    private void independent(Review r,boolean approve,AddressFacts observed) {
        // 승인자는 같은 열람본에서 독립적으로 읽은 값을 보낸다. 단순 승인 체크박스나 작성자의 열람 영수증으로 대신하지 않는다.
        if(approve) {if(observed==null || !facts(r).equals(observed.validated())) throw denied(HttpStatus.CONFLICT);}
        else if(observed!=null) throw denied(HttpStatus.BAD_REQUEST);
    }
    private AddressFacts facts(Review r) {
        var p=r.payload();byte[] cipher=jdbc.queryForObject("SELECT facts_cipher FROM edd_address_reviews WHERE id=?",byte[].class,p.id());
        try {
            var facts=json.readValue(crypto.decrypt(p.evidenceKeyId(),factsContext(p),cipher),AddressFacts.class).validated();
            if(!fingerprint(facts).equals(p.factsMac())) throw new IllegalStateException("주소 검토 근거 검증 실패");return facts;
        } catch(RuntimeException e) {
            // 해독·JSON 형식 오류에 이름·주소·원문이 실리지 않게 원인 예외도 밖으로 전달하지 않는다.
            throw new IllegalStateException("주소 검토 근거 검증 실패");
        }
    }
    private Current current(UUID evidence) {
        crypto.requireConfigured();cdd.requireConfigured();
        var rows=jdbc.queryForList("""
            SELECT c.id AS case_id,c.revision,c.user_id,a.id AS assessment_id,d.id AS declaration_id,d.ciphertext,
             a.key_id AS assessment_key,d.key_id AS declaration_key,e.key_id AS evidence_key,a.policy_version
            FROM edd_evidence e JOIN edd_evidence_requests r ON r.id=e.request_id AND r.user_id=e.user_id
             JOIN edd_cases c ON c.id=r.case_id AND c.user_id=r.user_id
             JOIN cdd_profiles p ON p.user_id=c.user_id AND p.assessment_id=c.latest_assessment
             JOIN cdd_assessments a ON a.id=p.assessment_id AND a.user_id=c.user_id AND a.declaration_id=p.declaration_id AND a.id=r.assessment_id
             JOIN cdd_declarations d ON d.id=p.declaration_id AND d.user_id=c.user_id
             JOIN kyc_profiles k ON k.user_id=c.user_id AND k.active_attempt=a.attempt_id AND k.status_digest=a.status_digest
            WHERE e.id=? AND r.purpose='RESIDENTIAL_ADDRESS' AND c.state IN ('OPEN','REFERRED') FOR UPDATE OF c
            """,evidence);
        if(rows.isEmpty()) throw denied(HttpStatus.CONFLICT);var r=rows.getFirst();
        if(!cdd.keyId().equals(r.get("assessment_key")) || !cdd.keyId().equals(r.get("declaration_key")) || !crypto.keyId().equals(r.get("evidence_key")) || !CddService.POLICY.equals(r.get("policy_version"))) throw denied(HttpStatus.CONFLICT);
        long user=((Number)r.get("user_id")).longValue();UUID declaration=(UUID)r.get("declaration_id");
        CustomerDeclaration declared;
        try {declared=json.readValue(cdd.decrypt(user,declaration,(byte[])r.get("ciphertext")),CustomerDeclaration.class);}
        catch(RuntimeException e) {throw new IllegalStateException("현재 신고 해독 검증 실패");}
        return new Current((UUID)r.get("case_id"),((Number)r.get("revision")).longValue(),user,(UUID)r.get("assessment_id"),declaration,declared.fullName(),declared.address());
    }
    private void view(User actor,UUID sid,UUID evidence,UUID view,Current current) {
        if(view==null) throw denied(HttpStatus.CONFLICT);
        boolean valid=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM edd_evidence_views v JOIN edd_evidence e ON e.id=v.evidence_id
             JOIN edd_evidence_requests r ON r.id=e.request_id
             WHERE v.id=? AND v.evidence_id=? AND v.actor_id=? AND v.actor_person=? AND v.actor_version=? AND v.session_id=?
              AND v.assessment_id=? AND v.case_revision=? AND v.key_id=? AND v.viewed_at>clock_timestamp()-interval '5 minutes'
              AND ((?='OPERATOR' AND r.requester_id=v.actor_id AND r.requester_person=v.actor_person)
               OR (?='APPROVER' AND r.requester_person<>v.actor_person)))
            """,Boolean.class,view,evidence,actor.getId(),actor.getOperatorPersonId(),actor.getVersion(),sid,current.assessment(),current.revision(),crypto.keyId(),actor.getRole().name(),actor.getRole().name()));
        if(!valid) throw denied(HttpStatus.CONFLICT);
    }
    private long owner(UUID evidence) {var rows=jdbc.queryForList("SELECT user_id FROM edd_evidence WHERE id=?",evidence);if(rows.isEmpty()) throw denied(HttpStatus.NOT_FOUND);return ((Number)rows.getFirst().get("user_id")).longValue();}
    private Context context(UUID evidence,UUID view,Current c) {return new Context(evidence,view,c.caseId(),c.revision(),c.assessment(),c.declaration(),c.name(),c.address());}
    private Review read(UUID id) {
        var rows=jdbc.queryForList("SELECT r.payload,r.payload_hash,d.approved FROM edd_address_reviews r LEFT JOIN edd_address_decisions d ON d.review_id=r.id WHERE r.id=?",id);
        if(rows.isEmpty()) throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();var p=json.readValue((String)row.get("payload"),Payload.class);String hash=(String)row.get("payload_hash");
        if(!verifier.digest(json.valueToTree(p)).equals(hash) || !p.id().equals(id)) throw new IllegalStateException("주소 검토 내용 해시 검증 실패");
        return new Review(p,hash,row.get("approved")==null?(p.expiresAt().isAfter(Instant.now())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED"));
    }
    private String factsContext(Payload p) {return "ADDRESS_REVIEW_V1:"+p.id()+":"+p.userId()+":"+p.evidenceId()+":"+p.assessmentId()+":"+p.caseRevision();}
    private String fingerprint(AddressFacts facts) {return cdd.fingerprint("address-review-v1",json.writeValueAsString(facts));}
    private static LocalDate today() {return LocalDate.now(ZoneId.of("Asia/Seoul"));}
    private Map<Long,User> lock(long... ids) {var result=new HashMap<Long,User>();Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(u->result.put(id,u)));return result;}
    private void customer(User user) {if(user==null || user.getRole()!=UserRole.CUSTOMER) throw denied(HttpStatus.CONFLICT);}
    private <T>T attempt(long actor,String action,Supplier<T> work) {try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,action,"edd-address-review","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"주소 검토의 현재 자료·평가·열람·독립 인증을 확인하세요");}
}
