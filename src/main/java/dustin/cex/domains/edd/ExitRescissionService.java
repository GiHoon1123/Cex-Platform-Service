package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.text.Normalizer;
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
 * 원래 종료 개시를 고치지 않고 그 효과만 독립 철회한다. 고객확인·일반 제한·보고 판단을 해제하지 않는다.
 * 원래 지정 인물이 요청하고, 원래 판단 참여자와 요청자에게서 독립된 새 인물이 원래/현재 근거를 확인한다.
 */
@Service
public class ExitRescissionService {
    public static final String POLICY="EXIT_RESCISSION_V1";
    public static final String ASSURANCE="EXIT_HOLD_ONLY_OTHER_POLICIES_STILL_APPLY";
    public enum Basis { ORIGINAL, CURRENT }
    public record Facts(boolean exitNoLongerJustified,String rationale) {
        public Facts validated() {
            if(!exitNoLongerJustified||rationale==null)throw denied(HttpStatus.BAD_REQUEST);
            String value=Normalizer.normalize(rationale,Normalizer.Form.NFC).strip();
            if(value.isBlank()||value.length()>2000||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))throw denied(HttpStatus.BAD_REQUEST);
            return new Facts(true,value);
        }
        @Override public String toString(){return "Facts[철회 사유 생략]";}
    }
    // 기록 시점의 평가·사건·근거를 값으로 고정한다. 현재 값이 달라지면 옛 요청의 서명을 재사용하지 않는다.
    public record Snapshot(UUID caseId,long revision,String state,UUID assessmentId,String outcome,List<String> reasons,EddDossier.Summary dossier) {
        public Snapshot {reasons=List.copyOf(reasons);}
    }
    public record Payload(UUID id,UUID exitId,String exitHash,long userId,long customerVersion,EddDossier.Summary originalDossier,
        Snapshot current,String policy,String assurance,String factsMac,String evidenceKeyId,String cddKeyId,
        long requesterId,UUID requesterPerson,long requesterVersion,long checkerId,UUID checkerPerson,long checkerVersion,Instant expiresAt) {}
    public record Request(Payload payload,String payloadHash,String status) {}
    public record Confirmation(Request request,boolean approve,OperatorMfaService.Options authentication) {}
    public record Inspection(Request request,RelationshipExitService.Review original,Facts facts,
        RelationshipExitService.Facts originalFacts,Basis basis,EddDossier.Page dossier) {
        @Override public String toString(){return "Inspection[원래 판단과 철회 사유 생략]";}
    }
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final RelationshipExitService exits;private final EddDossier dossiers;private final EvidenceCrypto crypto;
    private final CddCrypto cdd;private final DiditWebhookVerifier verifier;private final ObjectMapper json;
    private final OperatorMfaService mfa;private final AuditService audit;private final TransactionTemplate tx;
    public ExitRescissionService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,RelationshipExitService exits,
        EddDossier dossiers,EvidenceCrypto crypto,CddCrypto cdd,DiditWebhookVerifier verifier,ObjectMapper json,
        OperatorMfaService mfa,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.exits=exits;this.dossiers=dossiers;this.crypto=crypto;
        this.cdd=cdd;this.verifier=verifier;this.json=json;this.mfa=mfa;this.audit=audit;tx=new TransactionTemplate(manager);
        // 고객 잠금을 기다린 다음 조회는 새 확정 상태를 읽어야 한다. 지원하는 격리 수준을 명시한다.
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 원래 참여자만 요청한다. 새 승인자는 원래 두 인물에게서 독립되어야 하며 사유는 암호화해 저장한다. */
    public Request propose(long actor,UUID sid,UUID exitId,long checker,UUID key,Facts supplied) {
        return attempt(actor,()->tx.execute(t->{
            if(exitId==null||key==null||supplied==null)throw denied(HttpStatus.BAD_REQUEST);
            var original=exits.approvedOrigin(exitId);var origin=original.payload();var people=lock(actor,checker,origin.userId());
            var maker=people.get(actor);sessions.requireFresh(maker,sid,UserRole.OPERATOR);requireOriginalMember(maker,origin);customer(people.get(origin.userId()));
            var value=supplied.validated();String mac=fingerprint(original,value);
            // 응답 유실 뒤 같은 키를 재조회해도 새로운 종료 개시나 옛 철회 효과를 실행하지 않는다.
            var old=jdbc.queryForList("SELECT id FROM relationship_exit_rescissions WHERE requester_id=? AND request_key=?",actor,key);
            if(!old.isEmpty()) {
                var prior=read((UUID)old.getFirst().get("id"));var p=prior.payload();assigned(actor,sid,prior,people);
                if(!p.exitId().equals(exitId)||p.checkerId()!=checker||!p.factsMac().equals(mac))throw denied(HttpStatus.CONFLICT);
                audit.record(actor,"EXIT_RESCISSION_REPLAYED","exit-rescission:"+p.id(),"SUCCESS","SAME_REQUEST");return prior;
            }
            requireActive(original);var reviewer=people.get(checker);independent(reviewer,maker,origin);
            if(jdbc.queryForObject("SELECT count(*) FROM relationship_exit_rescissions r WHERE r.exit_id=? AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM relationship_exit_rescission_decisions d WHERE d.request_id=r.id)",Long.class,exitId)>0)throw denied(HttpStatus.CONFLICT);
            if(jdbc.queryForObject("SELECT count(*) FROM relationship_exit_rescissions WHERE user_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,origin.userId())>=10)throw denied(HttpStatus.TOO_MANY_REQUESTS);
            var row=currentCase(origin.userId(),origin.caseId());var summary=dossiers.capture(origin.caseId(),origin.userId());
            var snapshot=snapshot(row,summary);UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
            var p=new Payload(id,exitId,original.payloadHash(),origin.userId(),people.get(origin.userId()).getVersion(),origin.dossier(),snapshot,
                POLICY,ASSURANCE,mac,crypto.keyId(),cdd.keyId(),actor,maker.getOperatorPersonId(),maker.getVersion(),checker,reviewer.getOperatorPersonId(),reviewer.getVersion(),expires);
            String hash=verifier.digest(json.valueToTree(p));byte[] cipher=crypto.encrypt(context(p),json.writeValueAsBytes(value));
            jdbc.update("INSERT INTO relationship_exit_rescissions(id,exit_id,user_id,requester_id,checker_id,request_key,payload,payload_hash,reason_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,exitId,p.userId(),actor,checker,key,json.writeValueAsString(p),hash,cipher,Timestamp.from(expires));
            mfa.bindApproval(OperatorMfaService.ApprovalKind.EXIT_RESCISSION,id,actor,maker.getVersion(),hash,expires);
            audit.record(actor,"EXIT_RESCISSION_PROPOSED","exit-rescission:"+id,"SUCCESS",hash);return read(id);
        }));
    }
    /** 역사 원문도 현재 지정된 인물만 읽는다. 현재 판단의 자격은 옵션·결정에서 별도로 검사한다. */
    public Inspection inspect(long actor,UUID sid,UUID id,Basis basis,int page) {
        return attempt(actor,()->tx.execute(t->{
            if(basis==null)throw denied(HttpStatus.BAD_REQUEST);var r=read(id);var p=r.payload();
            var person=assigned(actor,sid,r,lock(actor,p.userId()));var original=exits.approvedOrigin(p.exitId());verifyOrigin(p,original);
            var summary=basis==Basis.ORIGINAL?p.originalDossier():p.current().dossier();dossiers.verify(summary,p.current().caseId(),p.userId());
            var content=facts(r,original);var originalContent=exits.correctionFacts(p.exitId());var result=dossiers.page(summary,page);
            // 원래/현재 근거의 내용 해시가 같으면 같은 제공으로 확인한다. 실제 제공한 사본 식별자는 따로 보존한다.
            jdbc.update("INSERT INTO relationship_exit_rescission_views(id,request_id,dossier_id,actor_id,actor_person,actor_version,session_id,page,digest,payload_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),id,summary.id(),actor,person.getOperatorPersonId(),person.getVersion(),sid,page,summary.digest(),r.payloadHash());
            audit.record(actor,"EXIT_RESCISSION_VIEWED","exit-rescission:"+id,"SUCCESS",basis.name()+":"+page);
            return new Inspection(r,original,content,originalContent,basis,result);
        }));
    }
    /** 옵션 발급과 실제 결정에서 같은 현재성 검사를 반복한다. 옵션이 생겨도 승인 권한을 예약하지 않는다. */
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve) {
        return attempt(actor,()->tx.execute(t->{var r=read(id);check(actor,sid,r,hash);return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.EXIT_RESCISSION,id,hash,approve));}));
    }
    public Request decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof) {
        return attempt(actor,()->tx.execute(t->{
            var r=read(id);var reviewer=check(actor,sid,r,hash);var p=r.payload();
            mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.EXIT_RESCISSION,id,hash,approve,proof);
            jdbc.update("INSERT INTO relationship_exit_rescission_decisions(request_id,exit_id,user_id,approver_id,approver_person,approved) VALUES(?,?,?,?,?,?)",id,p.exitId(),p.userId(),actor,reviewer.getOperatorPersonId(),approve);
            // 원래 판단과 금융 상태를 수정하지 않는다. 현재 효과는 승인된 철회를 제외하는 뷰가 계산한다.
            audit.record(actor,"EXIT_RESCISSION_DECIDED","exit-rescission:"+id,"SUCCESS",(approve?"APPROVED:":"REJECTED:")+hash);return read(id);
        }));
    }
    /** 응답 유실 후 재확인은 메타데이터만 반환한다. 과거 승인을 다시 집행하거나 새로운 제한을 풀지 않는다. */
    public Request status(long actor,UUID sid,UUID id) {
        return attempt(actor,()->tx.execute(t->{var r=read(id);assigned(actor,sid,r,lock(actor));audit.record(actor,"EXIT_RESCISSION_STATUS","exit-rescission:"+id,"SUCCESS","METADATA_ONLY");return r;}));
    }
    public List<Request> list(long actor,UUID sid,int page) {
        return attempt(actor,()->tx.execute(t->{
            var person=lock(actor).get(actor);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);
            if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);
            var ids=jdbc.queryForList("SELECT id FROM relationship_exit_rescissions WHERE (requester_id=? AND payload::jsonb->>'requesterPerson'=?) OR (checker_id=? AND payload::jsonb->>'checkerPerson'=?) ORDER BY created_at DESC,id DESC LIMIT 25 OFFSET ?",actor,person.getOperatorPersonId().toString(),actor,person.getOperatorPersonId().toString(),page*25);
            var result=ids.stream().map(row->read((UUID)row.get("id"))).toList();audit.record(actor,"EXIT_RESCISSIONS_LISTED","operator:"+actor,"SUCCESS","METADATA_ONLY");return result;
        }));
    }
    /** 정렬된 사용자 잠금 아래 현재 인물·평가·근거·본인 제공을 재검사하고 그 뒤에만 서명을 소모한다. */
    private User check(long actor,UUID sid,Request r,String hash) {
        var p=r.payload();var people=lock(actor,p.userId(),p.requesterId(),p.checkerId());var reviewer=assigned(actor,sid,r,people);
        sessions.requireFresh(reviewer,sid,UserRole.APPROVER);customer(people.get(p.userId()));
        var maker=people.get(p.requesterId());var original=exits.approvedOrigin(p.exitId());verifyOrigin(p,original);
        if(maker==null||!maker.isAuthenticationAllowed()||maker.getRole()!=UserRole.OPERATOR||maker.getVersion()!=p.requesterVersion()
            ||!maker.getOperatorPersonId().equals(p.requesterPerson())||actor!=p.checkerId()||reviewer.getVersion()!=p.checkerVersion()
            ||!reviewer.getOperatorPersonId().equals(p.checkerPerson())||people.get(p.userId()).getVersion()!=p.customerVersion())throw denied(HttpStatus.CONFLICT);
        requireOriginalMember(maker,original.payload());independent(reviewer,maker,original.payload());requireActive(original);
        if(!"PENDING".equals(read(p.id()).status())||!p.policy().equals(POLICY)||!p.assurance().equals(ASSURANCE)||!r.payloadHash().equals(hash)
            ||!p.evidenceKeyId().equals(crypto.keyId())||!p.cddKeyId().equals(cdd.keyId()))throw denied(HttpStatus.CONFLICT);
        var row=currentCase(p.userId(),p.current().caseId());
        if(!p.current().equals(snapshot(row,p.current().dossier()))||!p.current().dossier().digest().equals(dossiers.liveDigest(p.current().caseId(),p.userId())))throw denied(HttpStatus.CONFLICT);
        facts(r,original);exits.correctionFacts(p.exitId());
        for(var summary:List.of(p.originalDossier(),p.current().dossier())) {
            dossiers.verify(summary,p.current().caseId(),p.userId());
            long viewed=jdbc.queryForObject("SELECT count(DISTINCT page) FROM relationship_exit_rescission_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND digest=? AND page>=0 AND page<? AND viewed_at>clock_timestamp()-interval '5 minutes'",Long.class,p.id(),actor,reviewer.getOperatorPersonId(),reviewer.getVersion(),sid,hash,summary.digest(),summary.pageCount());
            if(viewed!=summary.pageCount())throw denied(HttpStatus.CONFLICT);
        }
        return reviewer;
    }
    /** 현재 사건은 종결 상태여도 읽지만 새 신고 뒤 아직 평가가 없으면 확인되지 않은 상태로 철회하지 않는다. */
    private Map<String,Object> currentCase(long user,UUID caseId) {
        var rows=jdbc.queryForList("SELECT c.id,c.revision,c.state,a.id AS assessment_id,a.outcome,a.reasons,a.policy_version,a.key_id FROM edd_cases c JOIN cdd_profiles p ON p.user_id=c.user_id JOIN cdd_assessments a ON a.id=p.assessment_id AND a.user_id=p.user_id AND a.declaration_id=p.declaration_id WHERE c.id=? AND c.user_id=? AND c.latest_assessment=a.id FOR UPDATE OF c",caseId,user);
        if(rows.isEmpty())throw denied(HttpStatus.CONFLICT);var row=rows.getFirst();
        if(!CddService.POLICY.equals(row.get("policy_version"))||!cdd.keyId().equals(row.get("key_id")))throw denied(HttpStatus.CONFLICT);return row;
    }
    private Snapshot snapshot(Map<String,Object> row,EddDossier.Summary dossier) {
        return new Snapshot((UUID)row.get("id"),((Number)row.get("revision")).longValue(),(String)row.get("state"),(UUID)row.get("assessment_id"),(String)row.get("outcome"),row.get("reasons")==null||row.get("reasons").equals("")?List.of():List.of(((String)row.get("reasons")).split(",")),dossier);
    }
    private void verifyOrigin(Payload p,RelationshipExitService.Review original) {
        if(!p.exitHash().equals(original.payloadHash())||p.userId()!=original.payload().userId()||!p.current().caseId().equals(original.payload().caseId())||!p.originalDossier().equals(original.payload().dossier()))throw new IllegalStateException("철회할 원래 결정의 근거 검증 실패");
    }
    private void requireActive(RelationshipExitService.Review original) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM relationship_active_exits WHERE review_id=? AND user_id=?)",Boolean.class,original.payload().id(),original.payload().userId())))throw denied(HttpStatus.CONFLICT);
    }
    private void requireOriginalMember(User person,RelationshipExitService.Payload original) {
        if(!((person.getId()==original.requesterId()&&person.getOperatorPersonId().equals(original.requesterPerson()))||(person.getId()==original.checkerId()&&person.getOperatorPersonId().equals(original.checkerPerson()))))throw denied(HttpStatus.NOT_FOUND);
    }
    private void independent(User reviewer,User maker,RelationshipExitService.Payload original) {
        if(reviewer==null||reviewer.getRole()!=UserRole.APPROVER||!reviewer.isAuthenticationAllowed()||reviewer.getOperatorPersonId().equals(maker.getOperatorPersonId())
            ||reviewer.getOperatorPersonId().equals(original.requesterPerson())||reviewer.getOperatorPersonId().equals(original.checkerPerson()))throw denied(HttpStatus.FORBIDDEN);
    }
    private User assigned(long actor,UUID sid,Request r,Map<Long,User> people) {
        var person=people.get(actor);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);var p=r.payload();
        if((actor==p.requesterId()&&person.getOperatorPersonId().equals(p.requesterPerson()))||(actor==p.checkerId()&&person.getOperatorPersonId().equals(p.checkerPerson())))return person;throw denied(HttpStatus.NOT_FOUND);
    }
    private Facts facts(Request r,RelationshipExitService.Review original) {
        var p=r.payload();try {
            byte[] cipher=jdbc.queryForObject("SELECT reason_cipher FROM relationship_exit_rescissions WHERE id=?",byte[].class,p.id());
            var value=json.readValue(crypto.decrypt(p.evidenceKeyId(),context(p),cipher),Facts.class).validated();
            if(!p.cddKeyId().equals(cdd.keyId())||!p.factsMac().equals(fingerprint(original,value)))throw new IllegalStateException();return value;
        } catch(RuntimeException e){throw new IllegalStateException("철회 사유의 해독·무결성 검증 실패");}
    }
    private String fingerprint(RelationshipExitService.Review original,Facts facts) {return cdd.fingerprint("exit-rescission",json.writeValueAsString(List.of(original.payload().id(),original.payloadHash(),facts)));}
    private String context(Payload p) {return "EXIT_RESCISSION:"+p.id()+":"+p.exitId()+":"+p.exitHash()+":"+p.userId()+":"+p.factsMac();}
    private Request read(UUID id) {
        var rows=jdbc.queryForList("SELECT r.payload,r.payload_hash,d.approved FROM relationship_exit_rescissions r LEFT JOIN relationship_exit_rescission_decisions d ON d.request_id=r.id WHERE r.id=?",id);
        if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String raw=(String)row.get("payload");var p=json.readValue(raw,Payload.class);
        if(!p.id().equals(id)||!verifier.digest(json.readTree(raw)).equals(row.get("payload_hash")))throw new IllegalStateException("철회 요청의 무결성 검증 실패");
        String status=row.get("approved")==null?(p.expiresAt().isAfter(Instant.now())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED");return new Request(p,(String)row.get("payload_hash"),status);
    }
    // 여러 업무가 같은 사람을 반대 순서로 잡아 교착하지 않도록 중복을 제거하고 식별자 순서로 잠근다.
    private Map<Long,User> lock(long... ids) {var result=new HashMap<Long,User>();Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(u->result.put(id,u)));return result;}
    private void customer(User user) {if(user==null||user.getRole()!=UserRole.CUSTOMER)throw denied(HttpStatus.CONFLICT);}
    private <T>T attempt(long actor,Supplier<T> work) {try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"EXIT_RESCISSION","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"현재 원래 결정·독립 인물·확인 근거·최근 인증을 확인하세요");}
}
