package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.AuditService;
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

/** 공개 회신은 원래 대상의 확정 상태만 전달한다. 내부 메모·보고 사유·임의 문구를 공개하지 않는다. */
@Service
public class CustomerReviewReplyService {
    public enum Status { EXIT_INITIATION_ACTIVE, EXIT_INITIATION_RESCINDED }
    public record Reply(UUID id,Status status,String message,Instant observedAt) {}
    public record Payload(UUID id,UUID requestId,long userId,long actorId,long sequence,UUID noteId,long noteSequence,Status status,UUID basisId,String message,Instant observedAt) {}
    private final JdbcTemplate jdbc;private final CustomerExitReviewService requests;private final CustomerReviewNoteService notes;
    private final CddCrypto fingerprints;private final ObjectMapper json;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerReviewReplyService(JdbcTemplate jdbc,CustomerExitReviewService requests,CustomerReviewNoteService notes,CddCrypto fingerprints,ObjectMapper json,AuditService audit,PlatformTransactionManager manager){this.jdbc=jdbc;this.requests=requests;this.notes=notes;this.fingerprints=fingerprints;this.json=json;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    @Transactional(propagation=Propagation.NEVER)
    public Reply record(long actor,UUID sid,UUID request,UUID key,UUID latestNote){return attempt(actor,()->tx.execute(t->{
        var origin=requests.workRequest(actor,sid,request);long user=((Number)origin.get("user_id")).longValue();if(key==null||latestNote==null)throw denied(HttpStatus.BAD_REQUEST);
        var prior=jdbc.queryForList("SELECT * FROM customer_exit_review_replies WHERE actor_id=? AND request_key=?",actor,key);
        // 재송신은 당시 회신을 반환한다. 나중에 상태/메모가 변해도 옛 회신을 다시 만들지 않는다.
        if(!prior.isEmpty()){var payload=verify(prior.getFirst());if(!payload.requestId().equals(request)||!payload.noteId().equals(latestNote)||payload.userId()!=user)throw denied(HttpStatus.CONFLICT);audit.record(actor,"CUSTOMER_REVIEW_REPLY_REPLAYED","customer-reply:"+payload.id(),"SUCCESS","HISTORICAL_REPLY");return safe(payload);}
        requests.requirePersonalView(actor,sid,request);var note=notes.currentBasis(request,latestNote);
        if(jdbc.queryForObject("SELECT count(*) FROM customer_exit_review_replies WHERE request_id=? AND (payload::jsonb->>'observedAt')::timestamptz>clock_timestamp()-interval '1 day'",Long.class,request)>=10)throw denied(HttpStatus.TOO_MANY_REQUESTS);
        UUID exit=(UUID)origin.get("exit_id");Status status;UUID basis;
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM relationship_active_exits WHERE review_id=? AND user_id=?)",Boolean.class,exit,user))){status=Status.EXIT_INITIATION_ACTIVE;basis=exit;}
        else {
            var decisions=jdbc.queryForList("SELECT request_id FROM relationship_exit_rescission_decisions WHERE exit_id=? AND user_id=? AND approved",exit,user);
            if(decisions.size()!=1)throw new IllegalStateException("회신할 원래 종료 상태의 확정 근거가 없습니다");status=Status.EXIT_INITIATION_RESCINDED;basis=(UUID)decisions.getFirst().get("request_id");
        }
        // 같은 고객 잠금 아래 상태와 최신 메모를 묶고 순번을 증가시킨다. 시각 정렬로 최신을 추측하지 않는다.
        long sequence=jdbc.queryForObject("SELECT coalesce(max(sequence),0)+1 FROM customer_exit_review_replies WHERE request_id=?",Long.class,request);UUID id=UUID.randomUUID();
        var payload=new Payload(id,request,user,actor,sequence,note.id(),note.sequence(),status,basis,message(status),Instant.now().truncatedTo(ChronoUnit.MICROS));String raw=json.writeValueAsString(payload);
        jdbc.update("INSERT INTO customer_exit_review_replies(id,request_id,sequence,actor_id,request_key,note_id,note_sequence,payload,content_mac,fingerprint_key_id) VALUES(?,?,?,?,?,?,?,?,?,?)",id,request,sequence,actor,key,note.id(),note.sequence(),raw,mac(raw),fingerprints.keyId());
        audit.record(actor,"CUSTOMER_REVIEW_REPLY_RECORDED","customer-reply:"+id,"SUCCESS","STATUS_OBSERVED_ONLY");return safe(payload);
    }));}
    public List<Reply> own(long actor,UUID sid,UUID request,int page){return attempt(actor,()->tx.execute(t->{requests.own(actor,sid,request);var result=list(request,actor,page);audit.record(actor,"CUSTOMER_REVIEW_REPLIES_OWN","customer-review:"+request,"SUCCESS","PUBLIC_STATUS_ONLY");return result;}));}
    public List<Reply> assigned(long actor,UUID sid,UUID request,int page){return attempt(actor,()->tx.execute(t->{var parent=requests.workRequest(actor,sid,request);var result=list(request,((Number)parent.get("user_id")).longValue(),page);audit.record(actor,"CUSTOMER_REVIEW_REPLIES_ASSIGNED","customer-review:"+request,"SUCCESS","PUBLIC_STATUS_ONLY");return result;}));}
    private List<Reply> list(UUID request,long user,int page){if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);var result=new ArrayList<Reply>();for(var row:jdbc.queryForList("SELECT * FROM customer_exit_review_replies WHERE request_id=? ORDER BY sequence DESC LIMIT 25 OFFSET ?",request,page*25)){var payload=verify(row);if(!payload.requestId().equals(request)||payload.userId()!=user)throw new IllegalStateException("고객 회신 대상 검증 실패");result.add(safe(payload));}return List.copyOf(result);}
    private Payload verify(Map<String,Object> row){String raw=(String)row.get("payload");if(!fingerprints.keyId().equals(row.get("fingerprint_key_id"))||!mac(raw).equals(row.get("content_mac")))throw new IllegalStateException("고객 회신 무결성 검증 실패");var p=json.readValue(raw,Payload.class);if(!p.id().equals(row.get("id"))||!p.requestId().equals(row.get("request_id"))||p.actorId()!=((Number)row.get("actor_id")).longValue()||p.sequence()!=((Number)row.get("sequence")).longValue()||!p.noteId().equals(row.get("note_id"))||p.noteSequence()!=((Number)row.get("note_sequence")).longValue()||p.status()==null||p.basisId()==null||p.message()==null||p.message().isBlank()||p.observedAt()==null)throw new IllegalStateException("고객 회신 근거 결합 검증 실패");return p;}
    // 조회 때 최신 문구로 바꾸지 않는다. 실제 회신 당시 서명한 문구를 그대로 제공한다.
    private Reply safe(Payload p){return new Reply(p.id(),p.status(),p.message(),p.observedAt());}
    private String message(Status status){return switch(status){
        case EXIT_INITIATION_ACTIVE->"회신 시점에 요청 대상의 거래관계 종료 개시는 유지 중입니다. 재검토 요청의 처리 완료를 뜻하지 않습니다.";
        case EXIT_INITIATION_RESCINDED->"요청 대상의 거래관계 종료 개시가 철회된 사실을 확인했습니다. 다른 고객확인·거래 제한은 별도로 적용됩니다.";
    };}
    private String mac(String raw){return fingerprints.fingerprint("customer-review-public-reply",raw);}
    private <T>T attempt(long actor,Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_REVIEW_REPLY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 담당 권한·본인 열람·최신 내부 근거·회신 키를 확인하세요");}
}
