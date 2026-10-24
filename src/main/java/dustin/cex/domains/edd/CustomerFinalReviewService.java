package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 고객 재검토의 최종 답변 초안을 보존한다. 답변은 거래 제한·STR·자산 상태를 변경하지 않는다. */
@Service
public class CustomerFinalReviewService {
    public static final String POLICY="CUSTOMER_FINAL_V1";
    public record Explanation(String text) {
        public Explanation validated(){return new Explanation(new CustomerExitReviewService.Submission(text).validated().rationale());}
        @Override public String toString(){return "Explanation[고객 공개 예정 문구 생략]";}
    }
    public record Person(long id,UUID person,long version) {}
    public record Basis(UUID exitId,CustomerReviewReplyService.Status status,UUID decisionId) {}
    public record Payload(UUID id,UUID reviewId,long userId,long customerVersion,Person requester,Person checker,
        long custodyGeneration,String custodyStamp,UUID noteId,long noteSequence,Basis basis,String explanationMac,
        String evidenceKeyId,String fingerprintKeyId,String policy,Instant expiresAt) {}
    public record Request(Payload payload,String payloadHash,String status) {}
    public record Inspection(Request request,Explanation explanation,CustomerExitReviewService.Inspection customerReason,CustomerReviewNoteService.Inspection note) {
        @Override public String toString(){return "Inspection[고객 사유·내부 기록·공개 예정 문구 생략]";}
    }
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final CustomerWorkCustody custody;private final CustomerExitReviewService requests;private final CustomerReviewNoteService notes;
    private final EvidenceCrypto crypto;private final CddCrypto cdd;private final ObjectMapper json;
    private final DiditWebhookVerifier verifier;private final OperatorMfaService mfa;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerFinalReviewService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,CustomerWorkCustody custody,
            CustomerExitReviewService requests,CustomerReviewNoteService notes,EvidenceCrypto crypto,CddCrypto cdd,ObjectMapper json,
            DiditWebhookVerifier verifier,OperatorMfaService mfa,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.custody=custody;this.requests=requests;this.notes=notes;
        this.crypto=crypto;this.cdd=cdd;this.json=json;this.verifier=verifier;this.mfa=mfa;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Transactional(propagation=Propagation.NEVER)
    public Request propose(long actor,UUID sid,UUID review,UUID key,UUID latestNote,Explanation supplied) {
        return attempt(actor,()->tx.execute(t->{
            var state=custody.current(review);var people=custody.lock(state,actor);var maker=people.get(actor);
            sessions.requireFresh(maker,sid,UserRole.OPERATOR);if(!state.contains(maker))throw denied(HttpStatus.NOT_FOUND);
            if(key==null||latestNote==null||supplied==null)throw denied(HttpStatus.BAD_REQUEST);var explanation=supplied.validated();String mac=mac(review,explanation);
            // 재전송은 당시 초안이다. 내용이 같아도 다른 근거를 붙여 원래 요청을 바꾸지 않는다.
            var old=jdbc.queryForList("SELECT id FROM customer_review_final_requests WHERE requester_id=? AND request_key=?",actor,key);
            if(!old.isEmpty()){var r=read((UUID)old.getFirst().get("id"));if(!r.payload().reviewId().equals(review)||!r.payload().noteId().equals(latestNote)||!r.payload().explanationMac().equals(mac))throw denied(HttpStatus.CONFLICT);audit.record(actor,"CUSTOMER_FINAL_REPLAYED","customer-final:"+r.payload().id(),"SUCCESS","SAME_DRAFT");return r;}
            requests.requirePersonalView(actor,sid,review);var note=notes.currentBasis(review,latestNote);
            var candidates=state.members().stream().map(m->people.get(m.id())).filter(u->u!=null&&state.contains(u)&&u.isAuthenticationAllowed()&&u.getRole()==UserRole.APPROVER&&!u.getOperatorPersonId().equals(maker.getOperatorPersonId())).toList();
            if(candidates.size()!=1)throw denied(HttpStatus.CONFLICT);var checker=candidates.getFirst();
            if(closed(review)||jdbc.queryForObject("SELECT count(*) FROM customer_review_final_requests r WHERE review_id=? AND expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM customer_review_final_decisions d WHERE d.request_id=r.id)",Long.class,review)>0)throw denied(HttpStatus.CONFLICT);
            if(jdbc.queryForObject("SELECT count(*) FROM customer_review_final_requests WHERE review_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,review)>=10)throw denied(HttpStatus.TOO_MANY_REQUESTS);
            var parent=requests.workRequest(actor,sid,review);UUID id=UUID.randomUUID();var owner=people.get(state.userId());
            var payload=new Payload(id,review,state.userId(),owner.getVersion(),person(maker),person(checker),state.generation(),state.stamp(),note.id(),note.sequence(),basis((UUID)parent.get("exit_id"),state.userId()),mac,crypto.keyId(),cdd.keyId(),POLICY,Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS));
            String raw=json.writeValueAsString(payload),hash=verifier.digest(json.readTree(raw));byte[] cipher=crypto.encrypt(cipherContext(payload),explanation.text().getBytes(StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO customer_review_final_requests(id,review_id,requester_id,request_key,payload,payload_hash,explanation_cipher,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,review,actor,key,raw,hash,cipher,Timestamp.from(payload.expiresAt()));
            mfa.bindApproval(OperatorMfaService.ApprovalKind.CUSTOMER_REVIEW_FINAL,id,actor,maker.getVersion(),hash,payload.expiresAt());
            audit.record(actor,"CUSTOMER_FINAL_PROPOSED","customer-final:"+id,"SUCCESS","DRAFT_ONLY");return read(id);
        }));
    }
    public Inspection inspect(long actor,UUID sid,UUID id) {return attempt(actor,()->tx.execute(t->{
        var r=checked(actor,sid,id,false);var p=r.payload();var reason=requests.inspect(actor,sid,p.reviewId());var note=notes.inspect(actor,sid,p.noteId());var explanation=decrypt(r);
        var viewer=users.findById(actor).orElseThrow();jdbc.update("INSERT INTO customer_review_final_views(id,request_id,actor_id,actor_person,actor_version,session_id,payload_hash) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),id,actor,viewer.getOperatorPersonId(),viewer.getVersion(),sid,r.payloadHash());
        audit.record(actor,"CUSTOMER_FINAL_VIEWED","customer-final:"+id,"SUCCESS","DESIGNATED_REVIEW_ONLY");return new Inspection(r,explanation,reason,note);
    }));}
    public Request status(long actor,UUID sid,UUID id){return attempt(actor,()->tx.execute(t->{var r=read(id);var p=r.payload();var state=custody.current(p.reviewId());var viewer=custody.lock(state,actor).get(actor);sessions.requireFresh(viewer,sid,UserRole.OPERATOR,UserRole.APPROVER);
        if(!state.contains(viewer)&&!(actor==p.requester().id()&&p.requester().person().equals(viewer.getOperatorPersonId())))throw denied(HttpStatus.NOT_FOUND);
        audit.record(actor,"CUSTOMER_FINAL_STATUS","customer-final:"+id,"SUCCESS","METADATA_ONLY");return r;
    }));}
    /** 지정 승인의 모든 가변 근거를 고객·두 담당자와 같은 순서로 잠근 뒤 재확인한다. */
    private Request checked(long actor,UUID sid,UUID id,boolean viewed) {
        var r=read(id);var p=r.payload();var state=custody.current(p.reviewId());var people=custody.lock(state,actor,p.requester().id(),p.checker().id());var viewer=people.get(actor);
        sessions.requireFresh(viewer,sid,UserRole.APPROVER);
        if(actor!=p.checker().id()||!person(viewer).equals(p.checker())||!state.contains(viewer)||viewer.getOperatorPersonId().equals(p.requester().person()))throw denied(HttpStatus.FORBIDDEN);
        var maker=people.get(p.requester().id());if(maker==null||!maker.isAuthenticationAllowed()||maker.getRole()!=UserRole.OPERATOR||!state.contains(maker)||!person(maker).equals(p.requester()))throw denied(HttpStatus.CONFLICT);
        if(!POLICY.equals(p.policy())||!crypto.keyId().equals(p.evidenceKeyId())||!cdd.keyId().equals(p.fingerprintKeyId())||!state.stamp().equals(p.custodyStamp())||state.generation()!=p.custodyGeneration()||people.get(state.userId()).getVersion()!=p.customerVersion()||closed(p.reviewId())||!read(id).status().equals("PENDING"))throw denied(HttpStatus.CONFLICT);
        notes.currentBasis(p.reviewId(),p.noteId());var parent=requests.workRequest(actor,sid,p.reviewId());if(!basis((UUID)parent.get("exit_id"),p.userId()).equals(p.basis()))throw denied(HttpStatus.CONFLICT);
        decrypt(r);
        if(viewed&&jdbc.queryForObject("SELECT count(*) FROM customer_review_final_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND provided_at>clock_timestamp()-interval '5 minutes'",Long.class,id,actor,viewer.getOperatorPersonId(),viewer.getVersion(),sid,r.payloadHash())==0)throw denied(HttpStatus.CONFLICT);
        return r;
    }
    private Basis basis(UUID exit,long user){
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM relationship_active_exits WHERE review_id=? AND user_id=?)",Boolean.class,exit,user)))return new Basis(exit,CustomerReviewReplyService.Status.EXIT_INITIATION_ACTIVE,exit);
        var rows=jdbc.queryForList("SELECT request_id FROM relationship_exit_rescission_decisions WHERE exit_id=? AND user_id=? AND approved",exit,user);if(rows.size()!=1)throw new IllegalStateException("최종 답변의 원래 종료 상태 근거가 없습니다");return new Basis(exit,CustomerReviewReplyService.Status.EXIT_INITIATION_RESCINDED,(UUID)rows.getFirst().get("request_id"));
    }
    private boolean closed(UUID review){return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_review_final_decisions WHERE review_id=? AND approved)",Boolean.class,review);}
    private Person person(User u){return new Person(u.getId(),u.getOperatorPersonId(),u.getVersion());}
    private Request read(UUID id){var rows=jdbc.queryForList("SELECT r.*,d.approved FROM customer_review_final_requests r LEFT JOIN customer_review_final_decisions d ON d.request_id=r.id WHERE r.id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String raw=(String)row.get("payload");var p=json.readValue(raw,Payload.class);
        if(!id.equals(p.id())||!p.reviewId().equals(row.get("review_id"))||p.requester().id()!=((Number)row.get("requester_id")).longValue()||!verifier.digest(json.readTree(raw)).equals(row.get("payload_hash"))||!p.expiresAt().equals(((Timestamp)row.get("expires_at")).toInstant()))throw new IllegalStateException("최종 답변 초안 검증 실패");
        return new Request(p,(String)row.get("payload_hash"),row.get("approved")==null?(p.expiresAt().isAfter(Instant.now())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED"));
    }
    private Explanation decrypt(Request r){var p=r.payload();byte[] cipher=jdbc.queryForObject("SELECT explanation_cipher FROM customer_review_final_requests WHERE id=?",byte[].class,p.id());var e=new Explanation(new String(crypto.decrypt(p.evidenceKeyId(),cipherContext(p),cipher),StandardCharsets.UTF_8)).validated();if(!mac(p.reviewId(),e).equals(p.explanationMac()))throw new IllegalStateException("최종 공개 문구 검증 실패");return e;}
    private String mac(UUID review,Explanation e){return cdd.fingerprint("customer-final-explanation",review+":"+e.text());}
    private String cipherContext(Payload p){return "CUSTOMER_FINAL:"+p.id()+":"+p.reviewId()+":"+p.explanationMac();}
    private <T>T attempt(long actor,Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_FINAL","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 담당·초안·근거·최근 인증을 확인하세요");}
}
