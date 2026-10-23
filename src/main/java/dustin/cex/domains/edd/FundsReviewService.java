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
 * 자금 발생·수취 자료의 내용과 현재 신고를 두 사람이 대조한다. 관찰한 사실과 독립 판단을 보존한다.
 * 외부 입금·자료 진위·자금 배정·재산 형성·제재 해소를 자동 확인하거나 거래를 허용하지 않는다.
 */
@Service
public class FundsReviewService {
    public static final String POLICY="EDD_FUNDS_DOCUMENT_PAIR_V1";
    public static final String ASSURANCE="DOCUMENT_OBSERVATIONS_ONLY_EXTERNAL_FUNDING_NOT_VERIFIED";
    public record Views(UUID origin,UUID receipt) {}
    public record Context(UUID originEvidence,UUID receiptEvidence,Views views,UUID caseId,long caseRevision,UUID assessmentId,UUID declarationId,
        String declaredName,CustomerDeclaration.SourceOfFunds declaredSource,FundsTradeLink.Target target,FundsTradeLink.Link trade,String assurance) {
        @Override public String toString() {return "Context[개인정보 생략]";}
    }
    public record Payload(UUID id,UUID originEvidence,UUID receiptEvidence,long userId,UUID caseId,long caseRevision,UUID assessmentId,UUID declarationId,
        Views requesterViews,FundsTradeLink.Target target,FundsTradeLink.Link trade,String assurance,String factsMac,String evidenceKeyId,String cddKeyId,String policy,List<FundsFacts.Reason> reasons,
        long requesterId,UUID requesterPerson,long requesterVersion,Instant expiresAt) {}
    public record Review(Payload payload,String payloadHash,String status) {}
    public record Inspection(Review review,Context context,FundsFacts facts) {
        @Override public String toString() {return "Inspection[개인정보 생략]";}
    }
    public record Confirmation(Review review,boolean approve,OperatorMfaService.Options authentication) {}
    private final FundsTradeLink trades;
    private final ReviewEvidenceAccess access;
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final EvidenceCrypto crypto;private final CddCrypto cdd;private final ObjectMapper json;private final DiditWebhookVerifier verifier;
    private final OperatorMfaService mfa;private final AuditService audit;private final TransactionTemplate tx;
    public FundsReviewService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,EvidenceCrypto crypto,CddCrypto cdd,
            ObjectMapper json,DiditWebhookVerifier verifier,OperatorMfaService mfa,AuditService audit,PlatformTransactionManager manager,ReviewEvidenceAccess access,FundsTradeLink trades) {
        this.trades=trades;this.access=access;this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.crypto=crypto;this.cdd=cdd;this.json=json;this.verifier=verifier;this.mfa=mfa;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    /** 이미 제공한 두 열람본과 같은 현재 신고의 이름·자금 원천만 반환한다. */
    public Context context(long actor,UUID sid,UUID origin,UUID receipt,Views views,FundsTradeLink.Target target) {
        return attempt(actor,"EDD_FUNDS_CONTEXT",()->tx.execute(s->{
            long user=pairOwner(origin,receipt);var locked=access.lock(actor,user);var operator=locked.get(actor);
            sessions.requireFresh(operator,sid,UserRole.OPERATOR,UserRole.APPROVER);access.customer(locked.get(user));
            var current=current(origin,receipt);view(operator,sid,origin,receipt,views,current);var trade=trades.read(user,target);
            audit.record(actor,"EDD_FUNDS_CONTEXT_READ","edd-evidence:"+origin,"SUCCESS","NAME_SOURCE_ONLY");return context(origin,receipt,views,current,target,trade);
        }));
    }
    public Review propose(long actor,UUID sid,UUID origin,UUID receipt,Views views,UUID assessment,long revision,UUID key,FundsFacts supplied,FundsTradeLink.Target target) {
        return attempt(actor,"EDD_FUNDS_PROPOSE",()->tx.execute(s->{
            long user=pairOwner(origin,receipt);var locked=access.lock(actor,user);var maker=locked.get(actor);sessions.requireFresh(maker,sid,UserRole.OPERATOR);access.customer(locked.get(user));
            if(supplied==null || key==null || assessment==null || views==null || target==null) throw denied(HttpStatus.BAD_REQUEST);
            var facts=supplied.validated();target.validated();String mac=fingerprint(user,origin,receipt,assessment,facts);
            var old=jdbc.queryForList("SELECT id FROM edd_funds_reviews WHERE requester_id=? AND request_key=?",actor,key);
            if(!old.isEmpty()) {
                var r=read((UUID)old.getFirst().get("id"));var p=r.payload();
                if(!p.originEvidence().equals(origin) || !p.receiptEvidence().equals(receipt) || !p.requesterViews().equals(views) || !p.target().equals(target) || !p.assessmentId().equals(assessment) || p.caseRevision()!=revision || !p.factsMac().equals(mac)) throw denied(HttpStatus.CONFLICT);
                return r;
            }
            var current=current(origin,receipt);view(maker,sid,origin,receipt,views,current);var trade=trades.read(user,target);
            if(current.revision()!=revision || !current.assessment().equals(assessment)) throw denied(HttpStatus.CONFLICT);
            // 한 사건의 같은 사실에는 미결 검토 하나만 만든다. 옛 변경 번호의 미결 이력이 새 검토를 막지는 않는다.
            if(jdbc.queryForObject("SELECT count(*) FROM edd_funds_reviews r WHERE r.case_id=? AND r.payload::jsonb->>'caseRevision'=? AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM edd_funds_decisions d WHERE d.review_id=r.id)",Long.class,current.caseId(),Long.toString(revision))>0) throw denied(HttpStatus.CONFLICT);
            if(jdbc.queryForObject("SELECT count(*) FROM edd_funds_reviews WHERE user_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,user)>=10) throw denied(HttpStatus.TOO_MANY_REQUESTS);
            UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
            var p=new Payload(id,origin,receipt,user,current.caseId(),revision,assessment,current.declaration(),views,target,trade,ASSURANCE,mac,crypto.keyId(),cdd.keyId(),POLICY,
                facts.compare(current.name(),current.source(),today(),trade),actor,maker.getOperatorPersonId(),maker.getVersion(),expires);
            String hash=verifier.digest(json.valueToTree(p));byte[] encrypted=crypto.encrypt(factsContext(p),json.writeValueAsBytes(facts));
            jdbc.update("INSERT INTO edd_funds_reviews(id,case_id,origin_evidence,receipt_evidence,user_id,requester_id,request_key,payload,payload_hash,facts_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,current.caseId(),origin,receipt,user,actor,key,json.writeValueAsString(p),hash,encrypted,Timestamp.from(expires));
            mfa.bindApproval(OperatorMfaService.ApprovalKind.EDD_FUNDS_REVIEW,id,actor,maker.getVersion(),hash,expires);
            audit.record(actor,"EDD_FUNDS_PROPOSED","edd-funds-review:"+id,"SUCCESS",p.reasons().isEmpty()?"CONTENT_CONSISTENT":"FURTHER_CHECK_REQUIRED");return read(id);
        }));
    }
    /** 승인자는 자신에게 제공된 열람본과 신고·작성자가 관찰한 내용을 같은 자리에서 대조한다. 열람도 감사 대상이다. */
    public Inspection inspect(long actor,UUID sid,UUID id,Views views) {
        return attempt(actor,"EDD_FUNDS_INSPECT",()->tx.execute(s->{
            var review=read(id);var current=check(actor,sid,review,review.payloadHash(),views);
            var facts=facts(review);audit.record(actor,"EDD_FUNDS_FACTS_READ","edd-funds-review:"+id,"SUCCESS","INDEPENDENT_INSPECTION");
            return new Inspection(review,context(review.payload().originEvidence(),review.payload().receiptEvidence(),views,current,review.payload().target(),review.payload().trade()),facts);
        }));
    }
    public Confirmation options(long actor,UUID sid,UUID id,String hash,Views views,boolean approve,FundsFacts observed) {
        return attempt(actor,"EDD_FUNDS_OPTIONS",()->tx.execute(s->{
            var r=read(id);check(actor,sid,r,hash,views);independent(r,approve,observed);
            return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.EDD_FUNDS_REVIEW,id,hash,approve));
        }));
    }
    public Review decide(long actor,UUID sid,UUID id,String hash,Views views,boolean approve,FundsFacts observed,OperatorMfaService.AuthorizationProof proof) {
        return attempt(actor,"EDD_FUNDS_DECIDE",()->tx.execute(s->{
            var r=read(id);var current=check(actor,sid,r,hash,views);independent(r,approve,observed);
            mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.EDD_FUNDS_REVIEW,id,hash,approve,proof);
            var checker=users.findById(actor).orElseThrow();
            jdbc.update("INSERT INTO edd_funds_decisions(review_id,approver_id,approver_person,origin_view,receipt_view,approved) VALUES(?,?,?,?,?,?)",id,actor,checker.getOperatorPersonId(),views.origin(),views.receipt(),approve);
            // 검토 결과도 새로운 사실이다. 오래된 사건 결론은 무효화하되 금융 위험을 해소한 것으로 취급하지 않는다.
            jdbc.update("UPDATE edd_cases SET revision=revision+1,state='OPEN',valid_until=NULL WHERE id=?",current.caseId());
            audit.record(actor,"EDD_FUNDS_DECIDED","edd-funds-review:"+id,"SUCCESS",(approve?"APPROVED_CONTENT_REVIEW:":"REJECTED:")+hash);return read(id);
        }));
    }
    public List<Review> list(long actor,UUID sid,long user,int page) {
        return attempt(actor,"EDD_FUNDS_LIST",()->tx.execute(s->{
            var locked=access.lock(actor,user);sessions.requireFresh(locked.get(actor),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);access.customer(locked.get(user));
            if(page<0 || page>10000) throw denied(HttpStatus.BAD_REQUEST);
            audit.record(actor,"EDD_FUNDS_REVIEWS_LISTED","customer:"+user,"SUCCESS","METADATA_ONLY");
            return jdbc.queryForList("SELECT id FROM edd_funds_reviews WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?",user,page*50).stream().map(r->read((UUID)r.get("id"))).toList();
        }));
    }
    private ReviewEvidenceAccess.Snapshot check(long actor,UUID sid,Review r,String hash,Views viewed) {
        var p=r.payload();var locked=access.lock(actor,p.requesterId(),p.userId());var checker=locked.get(actor);var maker=locked.get(p.requesterId());
        sessions.requireFresh(checker,sid,UserRole.APPROVER);access.customer(locked.get(p.userId()));
        if(maker==null || !maker.isAuthenticationAllowed() || maker.getRole()!=UserRole.OPERATOR || maker.getVersion()!=p.requesterVersion() || !p.requesterPerson().equals(maker.getOperatorPersonId())
                || actor==p.requesterId() || checker.getOperatorPersonId().equals(p.requesterPerson())) throw denied(HttpStatus.FORBIDDEN);
        var current=current(p.originEvidence(),p.receiptEvidence());view(checker,sid,p.originEvidence(),p.receiptEvidence(),viewed,current);
        // 사용자·사건 잠금을 얻은 뒤 결정 상태를 다시 읽어 동시 승인/거절의 두 번째 실행을 거부한다.
        if(!read(p.id()).status().equals("PENDING") || !r.payloadHash().equals(hash) || !POLICY.equals(p.policy()) || !p.evidenceKeyId().equals(crypto.keyId()) || !p.cddKeyId().equals(cdd.keyId())
                || !p.caseId().equals(current.caseId()) || p.caseRevision()!=current.revision() || !p.assessmentId().equals(current.assessment()) || !p.declarationId().equals(current.declaration())) throw denied(HttpStatus.CONFLICT);
        var facts=facts(r);
        if(!Objects.equals(trades.read(p.userId(),p.target()),p.trade()) || !ASSURANCE.equals(p.assurance()) || !facts.compare(current.name(),current.source(),today(),p.trade()).equals(p.reasons())) throw denied(HttpStatus.CONFLICT);
        return current;
    }
    private void independent(Review r,boolean approve,FundsFacts observed) {
        // 승인자는 같은 열람본에서 독립적으로 읽은 값을 보낸다. 단순 승인 체크박스나 작성자의 열람 영수증으로 대신하지 않는다.
        if(approve) {if(observed==null || !facts(r).equals(observed.validated())) throw denied(HttpStatus.CONFLICT);}
        else if(observed!=null) throw denied(HttpStatus.BAD_REQUEST);
    }
    private FundsFacts facts(Review r) {
        var p=r.payload();byte[] cipher=jdbc.queryForObject("SELECT facts_cipher FROM edd_funds_reviews WHERE id=?",byte[].class,p.id());
        try {
            var facts=json.readValue(crypto.decrypt(p.evidenceKeyId(),factsContext(p),cipher),FundsFacts.class).validated();
            if(!fingerprint(p.userId(),p.originEvidence(),p.receiptEvidence(),p.assessmentId(),facts).equals(p.factsMac())) throw new IllegalStateException("자금 검토 근거 검증 실패");return facts;
        } catch(RuntimeException e) {
            // 해독·JSON 형식 오류에 이름·자금·원문이 실리지 않게 원인 예외도 밖으로 전달하지 않는다.
            throw new IllegalStateException("자금 검토 근거 검증 실패");
        }
    }
    /** 두 자료의 소유자·평가·사건 변경 번호가 같아야 한다. 다른 자료나 재산 형성 자료로 대체하지 않는다. */
    private long pairOwner(UUID origin,UUID receipt) {
        if(origin==null || receipt==null || origin.equals(receipt)) throw denied(HttpStatus.BAD_REQUEST);
        long user=access.owner(origin);if(user!=access.owner(receipt))throw denied(HttpStatus.NOT_FOUND);
        // 같은 원본을 두 번 올린 것을 독립된 발생·수취 자료로 세지 않는다. 이미지 변조·진위 판별은 별도 업무다.
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT a.original_digest=b.original_digest FROM edd_evidence a,edd_evidence b WHERE a.id=? AND b.id=?",Boolean.class,origin,receipt)))throw denied(HttpStatus.CONFLICT);return user;
    }
    private ReviewEvidenceAccess.Snapshot current(UUID origin,UUID receipt) {
        var first=access.current(origin,EvidenceService.Purpose.SOURCE_OF_FUNDS);
        var second=access.current(receipt,EvidenceService.Purpose.SOURCE_OF_FUNDS);
        if(!first.equals(second))throw denied(HttpStatus.CONFLICT);return first;
    }
    private void view(User actor,UUID sid,UUID origin,UUID receipt,Views views,ReviewEvidenceAccess.Snapshot current) {
        if(views==null || views.origin()==null || views.receipt()==null)throw denied(HttpStatus.BAD_REQUEST);
        access.view(actor,sid,origin,views.origin(),current.assessment(),current.revision());
        access.view(actor,sid,receipt,views.receipt(),current.assessment(),current.revision());
    }
    private Context context(UUID origin,UUID receipt,Views views,ReviewEvidenceAccess.Snapshot c,FundsTradeLink.Target target,FundsTradeLink.Link trade) {
        return new Context(origin,receipt,views,c.caseId(),c.revision(),c.assessment(),c.declaration(),c.name(),c.source(),target,trade,ASSURANCE);
    }
    private Review read(UUID id) {
        var rows=jdbc.queryForList("SELECT r.payload,r.payload_hash,d.approved FROM edd_funds_reviews r LEFT JOIN edd_funds_decisions d ON d.review_id=r.id WHERE r.id=?",id);
        if(rows.isEmpty()) throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();var p=json.readValue((String)row.get("payload"),Payload.class);String hash=(String)row.get("payload_hash");
        if(!verifier.digest(json.valueToTree(p)).equals(hash) || !p.id().equals(id)) throw new IllegalStateException("자금 검토 내용 해시 검증 실패");
        return new Review(p,hash,row.get("approved")==null?(p.expiresAt().isAfter(Instant.now())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED"));
    }
    private String factsContext(Payload p) {return "FUNDS_REVIEW_V1:"+p.id()+":"+p.userId()+":"+p.originEvidence()+":"+p.receiptEvidence()+":"+p.assessmentId()+":"+p.caseRevision();}
    private String fingerprint(long user,UUID origin,UUID receipt,UUID assessment,FundsFacts facts) {return cdd.fingerprint("funds-review-v1:"+user+":"+origin+":"+receipt+":"+assessment,json.writeValueAsString(facts));}
    private static LocalDate today() {return LocalDate.now(ZoneId.of("Asia/Seoul"));}
    private <T>T attempt(long actor,String action,Supplier<T> work) {try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,action,"edd-funds-review","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"자금 검토의 현재 자료·평가·열람·독립 인증을 확인하세요");}
}
