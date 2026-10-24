package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.CddCrypto;
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

/** 보고 후속 업무의 인계만 승인한다. 원래 보고 판단·작성자·서명·내부 점검 시각과 금융 상태는 바꾸지 않는다. */
@Service
public class ReportingCustodyService {
    public static final String POLICY="REPORTING_CUSTODY_V1";
    public record Target(long id,UUID person,long version) {}
    public record Payload(UUID id,UUID reviewId,long userId,String reviewHash,long generation,String previousStamp,
        Target primary,Target checker,String policy,String reasonMac,String evidenceKeyId,String cddKeyId,
        long requesterId,UUID requesterPerson,long requesterVersion,Instant expiresAt) {}
    public record Request(Payload payload,String payloadHash,String status) {}
    public record Context(UUID reviewId,long userId,long generation,String stamp,List<ReportingCustody.Member> members,boolean orphaned) {
        public Context {members=List.copyOf(members);}
    }
    public record Inspection(Request request,String reason) {@Override public String toString(){return "Inspection[인계 사유 생략]";}}
    public record Confirmation(Request request,boolean approve,OperatorMfaService.Options authentication) {}
    public record Cursor(Instant decidedAt,UUID reviewId) {}
    public record Orphans(List<Context> items,Cursor next,int scanned) {public Orphans{items=List.copyOf(items);}}
    private final JdbcTemplate jdbc;private final ReportingReviewService reviews;private final ReportingCustody custody;
    private final UserRepository users;private final OperatorSessionPolicy sessions;private final EvidenceCrypto crypto;private final CddCrypto cdd;
    private final ObjectMapper json;private final DiditWebhookVerifier verifier;private final OperatorMfaService mfa;private final AuditService audit;private final TransactionTemplate tx;
    public ReportingCustodyService(JdbcTemplate jdbc,ReportingReviewService reviews,ReportingCustody custody,UserRepository users,
            OperatorSessionPolicy sessions,EvidenceCrypto crypto,CddCrypto cdd,ObjectMapper json,DiditWebhookVerifier verifier,
            OperatorMfaService mfa,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.reviews=reviews;this.custody=custody;this.users=users;this.sessions=sessions;this.crypto=crypto;this.cdd=cdd;this.json=json;this.verifier=verifier;this.mfa=mfa;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    /** 두 담당자 모두 업무 권한이 없는 건만 원문 없이 찾는다. 조회가 인계 권한이나 법정 담당자 임명은 아니다. */
    public Orphans orphans(long actor,UUID sid,Cursor after) {
        return attempt(actor,()->tx.execute(s->{
            var person=users.lockById(actor).orElseThrow(()->denied(HttpStatus.FORBIDDEN));sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);
            if(after!=null&&(after.decidedAt()==null||after.reviewId()==null||after.decidedAt().isBefore(Instant.EPOCH)||after.decidedAt().isAfter(Instant.now())))throw denied(HttpStatus.BAD_REQUEST);
            var ids=reviews.custodyCandidates(after==null?null:after.decidedAt(),after==null?null:after.reviewId());var result=new ArrayList<Context>();
            for(UUID id:ids) {var origin=reviews.custodyCandidate(id);if(origin==null)continue;var state=custody.current(id);if(custody.orphaned(state))result.add(context(state,true));}
            Cursor next=ids.size()==25?new Cursor(reviews.custodyDecision(ids.getLast()),ids.getLast()):null;
            audit.record(actor,"REPORTING_ORPHANS_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");return new Orphans(result,next,ids.size());
        }));
    }
    public Context context(long actor,UUID sid,UUID review) {
        return attempt(actor,()->tx.execute(s->{
            reviews.custodyOrigin(review);var state=custody.current(review);var people=custody.lock(state,actor);var person=people.get(actor);
            sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);eligible(person,state,people);
            audit.record(actor,"REPORTING_CUSTODY_CONTEXT","compliance-review:"+review,"SUCCESS","METADATA_ONLY");return context(state,custody.orphaned(state,people));
        }));
    }
    public Request propose(long actor,UUID sid,UUID review,String expectedStamp,long primary,long checker,UUID key,String reason) {
        return attempt(actor,()->tx.execute(s->{
            if(review==null||expectedStamp==null||key==null)throw denied(HttpStatus.BAD_REQUEST);String value=reason(reason);
            var origin=reviews.custodyOrigin(review);var state=custody.current(review);var people=custody.lock(state,actor,primary,checker);var maker=people.get(actor);
            sessions.requireFresh(maker,sid,UserRole.OPERATOR,UserRole.APPROVER);eligible(maker,state,people);var first=target(people.get(primary),UserRole.OPERATOR);var second=target(people.get(checker),UserRole.APPROVER);
            if(first.person().equals(second.person())||primary==checker)throw denied(HttpStatus.FORBIDDEN);
            String mac=fingerprint(review,value);var old=jdbc.queryForList("SELECT id FROM reporting_custody_requests WHERE requester_id=? AND request_key=?",actor,key);
            if(!old.isEmpty()) {var r=read((UUID)old.getFirst().get("id"));var p=r.payload();if(!p.reviewId().equals(review)||!p.previousStamp().equals(expectedStamp)||p.primary().id()!=primary||p.checker().id()!=checker||!p.reasonMac().equals(mac))throw denied(HttpStatus.CONFLICT);
                audit.record(actor,"REPORTING_CUSTODY_REPLAYED","custody:"+p.id(),"SUCCESS","SAME_REQUEST");return r;}
            if(!state.stamp().equals(expectedStamp))throw denied(HttpStatus.CONFLICT);
            // 같은 두 인물의 무의미한 재배정으로 세대와 감사 기록만 늘리지 않는다.
            if(new HashSet<>(state.members()).equals(Set.of(new ReportingCustody.Member(primary,first.person()),new ReportingCustody.Member(checker,second.person()))))throw denied(HttpStatus.CONFLICT);
            if(jdbc.queryForObject("SELECT count(*) FROM reporting_custody_requests r WHERE r.review_id=? AND r.generation=? AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM reporting_custody_decisions d WHERE d.request_id=r.id)",Long.class,review,state.generation()+1)>0)throw denied(HttpStatus.CONFLICT);
            if(jdbc.queryForObject("SELECT count(*) FROM reporting_custody_requests WHERE review_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,review)>=10)throw denied(HttpStatus.TOO_MANY_REQUESTS);
            UUID id=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
            var p=new Payload(id,review,origin.payload().userId(),origin.payloadHash(),state.generation()+1,state.stamp(),first,second,POLICY,mac,crypto.keyId(),cdd.keyId(),actor,maker.getOperatorPersonId(),maker.getVersion(),expires);
            String hash=verifier.digest(json.valueToTree(p));byte[] cipher=crypto.encrypt(cipherContext(p),value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO reporting_custody_requests(id,review_id,user_id,generation,requester_id,request_key,primary_id,checker_id,payload,payload_hash,reason_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,review,p.userId(),p.generation(),actor,key,primary,checker,json.writeValueAsString(p),hash,cipher,Timestamp.from(expires));
            mfa.bindApproval(OperatorMfaService.ApprovalKind.STR_CUSTODY,id,actor,maker.getVersion(),hash,expires);
            audit.record(actor,"REPORTING_CUSTODY_PROPOSED","custody:"+id,"SUCCESS",hash);return read(id);
        }));
    }
    /** 승인자는 인계의 대상·사유만 확인한다. 이 단계에서 보고 원문과 외부 참조를 제공하지 않는다. */
    public Inspection inspect(long actor,UUID sid,UUID id) {
        return attempt(actor,()->tx.execute(s->{var r=read(id);var person=check(actor,sid,r,r.payloadHash(),false);String reason=decrypt(r);
            jdbc.update("INSERT INTO reporting_custody_views(id,request_id,actor_id,actor_person,actor_version,session_id,payload_hash) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),id,actor,person.getOperatorPersonId(),person.getVersion(),sid,r.payloadHash());
            audit.record(actor,"REPORTING_CUSTODY_VIEWED","custody:"+id,"SUCCESS","ASSIGNMENT_ONLY");return new Inspection(r,reason);
        }));
    }
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve) {
        return attempt(actor,()->tx.execute(s->{var r=read(id);check(actor,sid,r,hash,true);return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.STR_CUSTODY,id,hash,approve));}));
    }
    public Request decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof) {
        return attempt(actor,()->tx.execute(s->{
            var r=read(id);var actorUser=check(actor,sid,r,hash,true);var p=r.payload();mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.STR_CUSTODY,id,hash,approve,proof);
            jdbc.update("INSERT INTO reporting_custody_decisions(request_id,review_id,generation,approver_id,approver_person,approved) VALUES(?,?,?,?,?,?)",id,p.reviewId(),p.generation(),actor,actorUser.getOperatorPersonId(),approve);
            audit.record(actor,"REPORTING_CUSTODY_DECIDED","custody:"+id,"SUCCESS",(approve?"APPROVED:":"REJECTED:")+hash);return read(id);
        }));
    }
    /** 후속 업무를 넘겨받은 현재 인물은 원래 확정 근거와 당시 자료를 읽는다. 새 보고 판단을 승인할 권한은 주지 않는다. */
    public ReportingReviewService.Inspection origin(long actor,UUID sid,UUID review,int page) {
        return attempt(actor,()->tx.execute(s->{reviews.deliveryOrigin(actor,sid,review);var result=reviews.custodyContent(review,page);
            audit.record(actor,"REPORTING_CUSTODY_ORIGIN_READ","compliance-review:"+review,"SUCCESS","CURRENT_CUSTODIAN");return result;
        }));
    }
    /** 승인 응답이 끊겨도 요청자·실제 결정자·현재 담당자는 원문 없이 결과를 재확인할 수 있다. */
    public Request status(long actor,UUID sid,UUID id) {
        return attempt(actor,()->tx.execute(s->{var r=read(id);var p=r.payload();var state=custody.current(p.reviewId());var person=custody.lock(state,actor).get(actor);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);
            boolean owner=actor==p.requesterId()&&p.requesterPerson().equals(person.getOperatorPersonId());
            boolean decider=jdbc.queryForObject("SELECT count(*) FROM reporting_custody_decisions WHERE request_id=? AND approver_id=? AND approver_person=?",Long.class,id,actor,person.getOperatorPersonId())>0;
            if(!owner&&!decider&&!state.contains(person))throw denied(HttpStatus.NOT_FOUND);
            audit.record(actor,"REPORTING_CUSTODY_STATUS","custody:"+id,"SUCCESS","METADATA_ONLY");return r;
        }));
    }
    public List<Request> history(long actor,UUID sid,UUID review,int page) {
        return attempt(actor,()->tx.execute(s->{reviews.deliveryOrigin(actor,sid,review);if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);
            var result=jdbc.queryForList("SELECT id FROM reporting_custody_requests WHERE review_id=? ORDER BY created_at DESC,id DESC LIMIT 25 OFFSET ?",review,page*25).stream().map(row->read((UUID)row.get("id"))).toList();
            audit.record(actor,"REPORTING_CUSTODY_HISTORY","compliance-review:"+review,"SUCCESS","METADATA_ONLY");return result;
        }));
    }
    /** 역사 제공은 승인용 영수증을 만들지 않는다. 현재 후속 담당자만 과거 인계 사유를 읽는다. */
    public Inspection historyDetail(long actor,UUID sid,UUID id) {
        return attempt(actor,()->tx.execute(s->{var r=read(id);reviews.deliveryOrigin(actor,sid,r.payload().reviewId());String reason=decrypt(r);
            audit.record(actor,"REPORTING_CUSTODY_HISTORY_DETAIL","custody:"+id,"SUCCESS","CURRENT_CUSTODIAN");return new Inspection(r,reason);
        }));
    }
    private User check(long actor,UUID sid,Request r,String hash,boolean viewed) {
        var p=r.payload();var state=custody.current(p.reviewId());var people=custody.lock(state,actor,p.requesterId(),p.primary().id(),p.checker().id());var approver=people.get(actor);var maker=people.get(p.requesterId());
        sessions.requireFresh(approver,sid,UserRole.APPROVER);
        if(maker==null||!maker.isAuthenticationAllowed()||!Set.of(UserRole.OPERATOR,UserRole.APPROVER).contains(maker.getRole())||maker.getVersion()!=p.requesterVersion()||!p.requesterPerson().equals(maker.getOperatorPersonId())
            ||actor==p.requesterId()||approver.getOperatorPersonId().equals(p.requesterPerson())||approver.getOperatorPersonId().equals(p.primary().person())||approver.getOperatorPersonId().equals(p.checker().person()))throw denied(HttpStatus.FORBIDDEN);
        eligible(maker,state,people);var first=target(people.get(p.primary().id()),UserRole.OPERATOR);var second=target(people.get(p.checker().id()),UserRole.APPROVER);
        if(!first.equals(p.primary())||!second.equals(p.checker())||first.person().equals(second.person()))throw denied(HttpStatus.CONFLICT);
        var origin=reviews.custodyOrigin(p.reviewId());
        if(!read(p.id()).status().equals("PENDING")||!r.payloadHash().equals(hash)||!POLICY.equals(p.policy())||!p.evidenceKeyId().equals(crypto.keyId())||!p.cddKeyId().equals(cdd.keyId())
            ||!state.stamp().equals(p.previousStamp())||state.generation()+1!=p.generation()||!origin.payloadHash().equals(p.reviewHash()))throw denied(HttpStatus.CONFLICT);
        decrypt(r);
        if(viewed&&jdbc.queryForObject("SELECT count(*) FROM reporting_custody_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND viewed_at>clock_timestamp()-interval '5 minutes'",Long.class,p.id(),actor,approver.getOperatorPersonId(),approver.getVersion(),sid,hash)==0)throw denied(HttpStatus.CONFLICT);
        return approver;
    }
    private Target target(User user,UserRole role) {if(user==null||user.getRole()!=role||!user.isAuthenticationAllowed())throw denied(HttpStatus.CONFLICT);return new Target(user.getId(),user.getOperatorPersonId(),user.getVersion());}
    private void eligible(User maker,ReportingCustody.State state,Map<Long,User> people) {if(!state.contains(maker)&&!custody.orphaned(state,people))throw denied(HttpStatus.NOT_FOUND);}
    private Context context(ReportingCustody.State state,boolean orphaned) {return new Context(state.reviewId(),state.userId(),state.generation(),state.stamp(),state.members(),orphaned);}
    private String decrypt(Request request) {
        var p=request.payload();try {byte[] cipher=jdbc.queryForObject("SELECT reason_cipher FROM reporting_custody_requests WHERE id=?",byte[].class,p.id());String value=reason(new String(crypto.decrypt(p.evidenceKeyId(),cipherContext(p),cipher),java.nio.charset.StandardCharsets.UTF_8));
            if(!p.reasonMac().equals(fingerprint(p.reviewId(),value)))throw new IllegalStateException();return value;
        }catch(RuntimeException e){throw new IllegalStateException("인계 사유의 해독·무결성 검증 실패");}
    }
    private String reason(String reason) {if(reason==null)throw denied(HttpStatus.BAD_REQUEST);String value=Normalizer.normalize(reason,Normalizer.Form.NFC).strip();if(value.isBlank()||value.length()>2000||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))throw denied(HttpStatus.BAD_REQUEST);return value;}
    private String fingerprint(UUID review,String reason) {return cdd.fingerprint("reporting-custody-reason",review+":"+reason);}
    private String cipherContext(Payload p) {return "REPORTING_CUSTODY:"+p.id()+":"+p.reviewId()+":"+p.reviewHash()+":"+p.reasonMac();}
    private Request read(UUID id) {
        var rows=jdbc.queryForList("SELECT r.payload,r.payload_hash,d.approved FROM reporting_custody_requests r LEFT JOIN reporting_custody_decisions d ON d.request_id=r.id WHERE r.id=?",id);
        if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String raw=(String)row.get("payload");var p=json.readValue(raw,Payload.class);
        if(!p.id().equals(id)||!verifier.digest(json.readTree(raw)).equals(row.get("payload_hash")))throw new IllegalStateException("인계 요청의 무결성 검증 실패");
        return new Request(p,(String)row.get("payload_hash"),row.get("approved")==null?(p.expiresAt().isAfter(Instant.now())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED"));
    }
    private <T>T attempt(long actor,Supplier<T> work) {try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"REPORTING_CUSTODY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 인계 대상·독립 인물·근거·최근 인증을 확인하세요");}
}
