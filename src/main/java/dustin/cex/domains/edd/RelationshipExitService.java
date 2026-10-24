package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.text.Normalizer;
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
 * 현재 승인된 준법 회부와 고객확인 불가라는 명시적 판단으로 관계 종료 절차 개시를 독립 승인한다.
 * 승인 효과는 신규 주문 차단이다. 로그인·취소·잔액·원장·보고 판단을 바꾸거나 종료 완료를 주장하지 않는다.
 */
@Service
public class RelationshipExitService {
    public static final String POLICY="RELATIONSHIP_EXIT_INITIATION_V1";
    public static final String ASSURANCE="EXIT_PENDING_ASSET_AND_CONTRACT_REVIEW";
    public record Facts(boolean dueDiligenceUnable,String rationale) {
        public Facts validated() {
            if(!dueDiligenceUnable||rationale==null)throw denied(HttpStatus.BAD_REQUEST);
            String value=Normalizer.normalize(rationale,Normalizer.Form.NFC).strip();
            // 한 줄의 명시적인 사유만 받는다. 로그 위조·숨은 방향 전환 문자와 과도한 원문 수집을 막는다.
            if(value.isBlank()||value.length()>2000||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))throw denied(HttpStatus.BAD_REQUEST);
            return new Facts(true,value);
        }
        @Override public String toString() {return "Facts[종료 판단 생략]";}
    }
    /**
     * 서명할 당시의 고객·요청자·지정 승인자 버전과 실제 평가/회부/전체 근거를 고정한다.
     * 고객 버전은 계정 상태 변경을, 사건 변경 번호와 근거 해시는 확인 사실 변경을 감지한다.
     * 어느 한 값만 비교해서 다른 변경을 놓치지 않도록 각각 보관한다.
     */
    public record Payload(UUID id,long userId,long customerVersion,UUID caseId,long caseRevision,UUID assessmentId,UUID referralId,String referralHash,
        EddDossier.Summary dossier,String policy,String assurance,String factsMac,String evidenceKeyId,String cddKeyId,
        long requesterId,UUID requesterPerson,long requesterVersion,long checkerId,UUID checkerPerson,long checkerVersion,Instant expiresAt) {}
    public record Review(Payload payload,String payloadHash,String status) {}
    public record Inspection(Review review,Facts facts,EddDossier.Page dossier) {
        @Override public String toString() {return "Inspection[종료 판단 생략]";}
    }
    public record Confirmation(Review review,boolean approve,OperatorMfaService.Options authentication) {}
    private final ComplianceReferralBasis referrals;
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final EvidenceCrypto crypto;private final CddCrypto cdd;private final ObjectMapper json;
    private final DiditWebhookVerifier verifier;private final OperatorMfaService mfa;private final AuditService audit;private final EddDossier dossiers;private final TransactionTemplate tx;
    public RelationshipExitService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,EvidenceCrypto crypto,CddCrypto cdd,
            ObjectMapper json,DiditWebhookVerifier verifier,OperatorMfaService mfa,AuditService audit,EddDossier dossiers,PlatformTransactionManager manager,ComplianceReferralBasis referrals) {
        this.referrals=referrals;this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.crypto=crypto;this.cdd=cdd;this.json=json;this.verifier=verifier;this.mfa=mfa;this.audit=audit;this.dossiers=dossiers;tx=new TransactionTemplate(manager);
    }
    /** 요청자는 현재 회부를 선택하고 별도 승인자를 지정한다. 역할만 있으면 모든 사건을 읽는 경로는 제공하지 않는다. */
    public Review propose(long actor,UUID sid,UUID referral,long checker,UUID key,Facts supplied) {
        return attempt(actor,()->tx.execute(s->{
            if(referral==null||key==null||supplied==null)throw denied(HttpStatus.BAD_REQUEST);
            var reference=referrals.read(referral);var locked=lock(actor,checker,reference.user());var maker=locked.get(actor);var reviewer=locked.get(checker);
            sessions.requireFresh(maker,sid,UserRole.OPERATOR);customer(locked.get(reference.user()));
            if(reviewer==null||reviewer.getRole()!=UserRole.APPROVER||!reviewer.isAuthenticationAllowed()||reviewer.getOperatorPersonId()==null
                ||actor==checker||maker.getOperatorPersonId().equals(reviewer.getOperatorPersonId()))throw denied(HttpStatus.FORBIDDEN);
            var facts=supplied.validated();String mac=fingerprint(reference.user(),referral,facts);
            // 이미 접수된 같은 키는 현재 근거를 새 요청으로 다시 해석하지 않는다.
            // 원래 결과를 재조회하되 현재 지정 인물의 열람 권한과 내용 일치는 다시 확인한다.
            var old=jdbc.queryForList("SELECT id FROM relationship_exit_requests WHERE requester_id=? AND request_key=?",actor,key);
            if(!old.isEmpty()) {
                var r=read((UUID)old.getFirst().get("id"));var p=r.payload();assigned(actor,sid,r,locked);
                if(!p.referralId().equals(referral)||p.checkerId()!=checker||!p.factsMac().equals(mac))throw denied(HttpStatus.CONFLICT);
                audit.record(actor,"RELATIONSHIP_EXIT_REPLAYED","relationship-exit:"+p.id(),"SUCCESS","ASSIGNED_ONLY");return r;
            }
            long revision=referrals.current(reference);
            if(exitInitiated(reference.user()))throw denied(HttpStatus.CONFLICT);
            // 한 고객의 미결 또는 승인된 종료 개시를 중복 생성하지 않는다. 반려·만료 후에는 새 요청을 남길 수 있다.
            if(jdbc.queryForObject("SELECT count(*) FROM relationship_exit_requests r LEFT JOIN relationship_exit_decisions d ON d.review_id=r.id WHERE r.user_id=? AND (d.approved=true OR (d.review_id IS NULL AND r.expires_at>clock_timestamp()))",Long.class,reference.user())>0)throw denied(HttpStatus.CONFLICT);
            if(jdbc.queryForObject("SELECT count(*) FROM relationship_exit_requests WHERE user_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,reference.user())>=10)throw denied(HttpStatus.TOO_MANY_REQUESTS);
            UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
            var p=new Payload(id,reference.user(),locked.get(reference.user()).getVersion(),reference.caseId(),revision,reference.assessment(),referral,reference.hash(),reference.dossier(),POLICY,ASSURANCE,mac,crypto.keyId(),cdd.keyId(),
                actor,maker.getOperatorPersonId(),maker.getVersion(),checker,reviewer.getOperatorPersonId(),reviewer.getVersion(),expires);
            String hash=verifier.digest(json.valueToTree(p));byte[] cipher=crypto.encrypt(context(p),json.writeValueAsBytes(facts));
            jdbc.update("INSERT INTO relationship_exit_requests(id,case_id,user_id,referral_id,requester_id,checker_id,request_key,payload,payload_hash,facts_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,p.caseId(),p.userId(),referral,actor,checker,key,json.writeValueAsString(p),hash,cipher,Timestamp.from(expires));
            mfa.bindApproval(OperatorMfaService.ApprovalKind.RELATIONSHIP_EXIT,id,actor,maker.getVersion(),hash,expires);
            // 감사에는 내부 판단이나 사유 원문을 남기지 않는다. 접근 제한된 상세 기록에서만 확인한다.
            audit.record(actor,"RELATIONSHIP_EXIT_PROPOSED","relationship-exit:"+id,"SUCCESS",hash);return read(id);
        }));
    }
    /** 과거 판단도 지정된 두 계정·인물만 읽는다. 만료된 자료 조회는 현재 승인용 제공 확인으로 인정하지 않는다. */
    public Inspection inspect(long actor,UUID sid,UUID id,int page) {
        return attempt(actor,()->tx.execute(s->{
            var r=read(id);var locked=lock(actor,r.payload().userId());var person=assigned(actor,sid,r,locked);var p=r.payload();
            dossiers.verify(p.dossier(),p.caseId(),p.userId());var content=facts(r);var result=dossiers.page(p.dossier(),page);
            jdbc.update("INSERT INTO relationship_exit_views(id,review_id,actor_id,actor_person,actor_version,session_id,page,payload_hash) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),id,actor,person.getOperatorPersonId(),person.getVersion(),sid,page,r.payloadHash());
            audit.record(actor,"RELATIONSHIP_EXIT_VIEWED","relationship-exit:"+id,"SUCCESS","ASSIGNED_ONLY");return new Inspection(r,content,result);
        }));
    }
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve) {
        return attempt(actor,()->tx.execute(s->{var r=read(id);check(actor,sid,r,hash);return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.RELATIONSHIP_EXIT,id,hash,approve));}));
    }
    public Review decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof) {
        return attempt(actor,()->tx.execute(s->{
            var r=read(id);var checker=check(actor,sid,r,hash);
            mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.RELATIONSHIP_EXIT,id,hash,approve,proof);
            jdbc.update("INSERT INTO relationship_exit_decisions(review_id,user_id,approver_id,approver_person,approved) VALUES(?,?,?,?,?)",id,r.payload().userId(),actor,checker.getOperatorPersonId(),approve);
            // 불변 승인 자체가 신규 주문의 차단 근거다. 잔액·로그인·보고 판단은 수정하지 않는다.
            audit.record(actor,"RELATIONSHIP_EXIT_DECIDED","relationship-exit:"+id,"SUCCESS",(approve?"APPROVED_EXIT_PENDING:":"REJECTED:")+hash);return read(id);
        }));
    }
    public List<Review> list(long actor,UUID sid,int page) {
        return attempt(actor,()->tx.execute(s->{
            var locked=lock(actor);var person=locked.get(actor);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);
            if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);
            var ids=jdbc.queryForList("SELECT id FROM relationship_exit_requests WHERE (requester_id=? AND payload::jsonb->>'requesterPerson'=?) OR (checker_id=? AND payload::jsonb->>'checkerPerson'=?) ORDER BY created_at DESC,id DESC LIMIT 25 OFFSET ?",actor,person.getOperatorPersonId().toString(),actor,person.getOperatorPersonId().toString(),page*25);
            var result=ids.stream().map(x->read((UUID)x.get("id"))).toList();
            audit.record(actor,"RELATIONSHIP_EXITS_LISTED","operator:"+actor,"SUCCESS","ASSIGNED_ONLY");return result;
        }));
    }
    /** 고객에게 내부 사유·심사자·보고 여부를 노출하지 않는다. 종료 완료나 출금 허용 상태도 추측하지 않는다. */
    public record CustomerStatus(String state) {}
    public CustomerStatus customerStatus(long actor) {
        return tx.execute(s->{
            var person=users.lockById(actor).orElse(null);
            if(person==null||person.getRole()!=UserRole.CUSTOMER||!person.isAuthenticationAllowed())throw denied(HttpStatus.FORBIDDEN);
            audit.record(actor,"RELATIONSHIP_STATUS_READ","customer:"+actor,"SUCCESS","CUSTOMER_SAFE_STATUS");
            return new CustomerStatus(exitInitiated(actor)?"EXIT_PENDING":"NO_EXIT_DECISION");
        });
    }
    /** 요청의 10분 기한은 승인 기회만 제한한다. 이미 확정된 개시 효과의 자동 해제 기한이 아니다. */
    private boolean exitInitiated(long user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM relationship_exit_decisions WHERE user_id=? AND approved)",Boolean.class,user));
    }
    /**
     * 사용자 행 잠금 뒤 역할·인물·버전과 현재 회부를 다시 읽는다. 서명을 얻은 뒤 상태가
     * 바뀌었으면 증명을 소비하기 전에 거절한다. 반려도 원래 요청을 대상으로 하는 독립 결정이다.
     */
    private User check(long actor,UUID sid,Review r,String hash) {
        var p=r.payload();var locked=lock(actor,p.requesterId(),p.checkerId(),p.userId());var checker=assigned(actor,sid,r,locked);var maker=locked.get(p.requesterId());
        sessions.requireFresh(checker,sid,UserRole.APPROVER);customer(locked.get(p.userId()));
        if(actor!=p.checkerId()||checker.getVersion()!=p.checkerVersion()||maker==null||maker.getRole()!=UserRole.OPERATOR||!maker.isAuthenticationAllowed()
            ||maker.getVersion()!=p.requesterVersion()||!p.requesterPerson().equals(maker.getOperatorPersonId())||p.requesterPerson().equals(checker.getOperatorPersonId()))throw denied(HttpStatus.FORBIDDEN);
        // 사용자 잠금 뒤 상태를 다시 읽어 승인/반려 경합에서 한 결정만 확정한다.
        if(!read(p.id()).status().equals("PENDING")||!r.payloadHash().equals(hash)||!POLICY.equals(p.policy())||!ASSURANCE.equals(p.assurance())
            ||!crypto.keyId().equals(p.evidenceKeyId())||!cdd.keyId().equals(p.cddKeyId()))throw denied(HttpStatus.CONFLICT);
        var origin=referrals.read(p.referralId());long revision=referrals.current(origin);
        if(revision!=p.caseRevision()||!origin.hash().equals(p.referralHash())||!origin.dossier().equals(p.dossier())||!origin.assessment().equals(p.assessmentId()))throw denied(HttpStatus.CONFLICT);
        if(exitInitiated(p.userId())||locked.get(p.userId()).getVersion()!=p.customerVersion())throw denied(HttpStatus.CONFLICT);
        facts(r);
        long viewed=jdbc.queryForObject("SELECT count(DISTINCT page) FROM relationship_exit_views WHERE review_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND page>=0 AND page<? AND viewed_at>clock_timestamp()-interval '5 minutes'",Long.class,p.id(),actor,checker.getOperatorPersonId(),checker.getVersion(),sid,hash,p.dossier().pageCount());
        if(viewed!=p.dossier().pageCount())throw denied(HttpStatus.CONFLICT);return checker;
    }
    private User assigned(long actor,UUID sid,Review r,Map<Long,User> locked) {
        var person=locked.get(actor);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);var p=r.payload();
        if((actor==p.requesterId()&&p.requesterPerson().equals(person.getOperatorPersonId()))||(actor==p.checkerId()&&p.checkerPerson().equals(person.getOperatorPersonId())))return person;
        throw denied(HttpStatus.NOT_FOUND);
    }
    private Facts facts(Review r) {
        var p=r.payload();try {
            byte[] cipher=jdbc.queryForObject("SELECT facts_cipher FROM relationship_exit_requests WHERE id=?",byte[].class,p.id());
            var value=json.readValue(crypto.decrypt(p.evidenceKeyId(),context(p),cipher),Facts.class).validated();
            if(!p.factsMac().equals(fingerprint(p.userId(),p.referralId(),value)))throw new IllegalStateException();return value;
        } catch(RuntimeException e) {throw new IllegalStateException("종료 판단의 해독·무결성 검증 실패");}
    }
    private String fingerprint(long user,UUID referral,Facts facts) {return cdd.fingerprint("relationship-exit",json.writeValueAsString(List.of(user,referral,facts)));}
    private String context(Payload p) {return "RELATIONSHIP_EXIT:"+p.id()+":"+p.userId()+":"+p.caseId()+":"+p.referralId()+":"+p.factsMac();}
    private Review read(UUID id) {
        var rows=jdbc.queryForList("SELECT r.payload,r.payload_hash,d.approved FROM relationship_exit_requests r LEFT JOIN relationship_exit_decisions d ON d.review_id=r.id WHERE r.id=?",id);
        if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String raw=(String)row.get("payload");var p=json.readValue(raw,Payload.class);
        if(!p.id().equals(id)||!verifier.digest(json.readTree(raw)).equals(row.get("payload_hash")))throw new IllegalStateException("종료 검토 무결성 검증 실패");
        String state=row.get("approved")==null?(p.expiresAt().isAfter(Instant.now())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED");return new Review(p,(String)row.get("payload_hash"),state);
    }
    /** 같은 고객의 접수·상태 변경과 순서를 정한다. 정렬된 인물 잠금은 서로 반대 순서의 교착을 줄인다. */
    private Map<Long,User> lock(long... ids) {var result=new HashMap<Long,User>();Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(u->result.put(id,u)));return result;}
    private void customer(User user) {if(user==null||user.getRole()!=UserRole.CUSTOMER)throw denied(HttpStatus.CONFLICT);}
    private <T>T attempt(long actor,Supplier<T> work) {try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"RELATIONSHIP_EXIT","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"현재 업무 배정·근거·최근 인증을 확인하세요");}
}
