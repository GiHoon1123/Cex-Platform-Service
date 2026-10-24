package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.AuditService;
import java.sql.Timestamp;
import java.time.Instant;
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

/** 담당자의 관찰은 비공개 추가 이력이다. 고객에게 조사 완료나 거래 재개로 제공하지 않는다. */
@Service
public class CustomerReviewNoteService {
    public enum Progress { INVESTIGATING, ADDITIONAL_CHECK_REQUIRED }
    public record Facts(Progress progress,String note) {
        public Facts validated(){if(progress==null)throw denied(HttpStatus.BAD_REQUEST);return new Facts(progress,new CustomerExitReviewService.Submission(note).validated().rationale());}
        @Override public String toString(){return "Facts[내부 처리 메모 생략]";}
    }
    public record Note(UUID id,UUID requestId,long sequence,Progress progress,Instant recordedAt) {}
    public record Inspection(Note note,Facts facts){@Override public String toString(){return "Inspection[내부 메모 생략]";}}
    private final JdbcTemplate jdbc;private final CustomerExitReviewService requests;private final EvidenceCrypto crypto;
    private final CddCrypto fingerprints;private final ObjectMapper json;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerReviewNoteService(JdbcTemplate jdbc,CustomerExitReviewService requests,EvidenceCrypto crypto,CddCrypto fingerprints,ObjectMapper json,AuditService audit,PlatformTransactionManager manager){this.jdbc=jdbc;this.requests=requests;this.crypto=crypto;this.fingerprints=fingerprints;this.json=json;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    @Transactional(propagation=Propagation.NEVER)
    public Note record(long actor,UUID sid,UUID request,UUID key,Facts supplied){return attempt(actor,()->tx.execute(t->{
        requests.workRequest(actor,sid,request);if(key==null||supplied==null)throw denied(HttpStatus.BAD_REQUEST);var f=supplied.validated();String mac=mac(request,actor,f);
        var old=jdbc.queryForList("SELECT * FROM customer_exit_review_notes WHERE actor_id=? AND request_key=?",actor,key);
        if(!old.isEmpty()){var row=old.getFirst();if(!request.equals(row.get("request_id"))||!mac.equals(row.get("content_mac")))throw denied(HttpStatus.CONFLICT);facts(row);audit.record(actor,"CUSTOMER_REVIEW_NOTE_REPLAYED","customer-note:"+row.get("id"),"SUCCESS","METADATA_ONLY");return metadata(row);}
        requests.requirePersonalView(actor,sid,request);
        if(jdbc.queryForObject("SELECT count(*) FROM customer_exit_review_notes WHERE request_id=? AND recorded_at>clock_timestamp()-interval '1 day'",Long.class,request)>=10)throw denied(HttpStatus.TOO_MANY_REQUESTS);
        // 시계가 역행하거나 같은 시각이어도 논리 순번은 증가한다. 고객 잠금 아래에서만 계산한다.
        long sequence=jdbc.queryForObject("SELECT coalesce(max(sequence),0)+1 FROM customer_exit_review_notes WHERE request_id=?",Long.class,request);UUID id=UUID.randomUUID();
        byte[] cipher=crypto.encrypt(context(id,request,actor,sequence,mac),json.writeValueAsBytes(f));
        jdbc.update("INSERT INTO customer_exit_review_notes(id,request_id,sequence,actor_id,request_key,progress,note_cipher,content_mac,evidence_key_id,fingerprint_key_id) VALUES(?,?,?,?,?,?,?,?,?,?)",id,request,sequence,actor,key,f.progress().name(),cipher,mac,crypto.keyId(),fingerprints.keyId());
        audit.record(actor,"CUSTOMER_REVIEW_NOTE_RECORDED","customer-note:"+id,"SUCCESS","INTERNAL_ONLY");return metadata(read(id));
    }));}
    public List<Note> list(long actor,UUID sid,UUID request,int page){return attempt(actor,()->tx.execute(t->{requests.workRequest(actor,sid,request);if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);var rows=jdbc.queryForList("SELECT * FROM customer_exit_review_notes WHERE request_id=? ORDER BY sequence DESC LIMIT 25 OFFSET ?",request,page*25);for(var row:rows)facts(row);audit.record(actor,"CUSTOMER_REVIEW_NOTES_LISTED","customer-review:"+request,"SUCCESS","METADATA_ONLY");return rows.stream().map(this::metadata).toList();}));}
    public Inspection inspect(long actor,UUID sid,UUID id){return attempt(actor,()->tx.execute(t->{var row=read(id);requests.workRequest(actor,sid,(UUID)row.get("request_id"));var f=facts(row);audit.record(actor,"CUSTOMER_REVIEW_NOTE_VIEWED","customer-note:"+id,"SUCCESS","INTERNAL_ONLY");return new Inspection(metadata(row),f);}));}
    /** 회신 서비스가 현재 고객/행위자를 잠근 뒤 호출해 실제 최신 내부 근거를 확인한다. */
    Note currentBasis(UUID request,UUID expected){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("후속 근거에는 트랜잭션이 필요합니다");
        var rows=jdbc.queryForList("SELECT * FROM customer_exit_review_notes WHERE request_id=? ORDER BY sequence DESC LIMIT 1",request);
        if(rows.isEmpty()||!rows.getFirst().get("id").equals(expected))throw denied(HttpStatus.CONFLICT);facts(rows.getFirst());return metadata(rows.getFirst());
    }
    private Map<String,Object> read(UUID id){var rows=jdbc.queryForList("SELECT * FROM customer_exit_review_notes WHERE id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();}
    private Note metadata(Map<String,Object> row){return new Note((UUID)row.get("id"),(UUID)row.get("request_id"),((Number)row.get("sequence")).longValue(),Progress.valueOf((String)row.get("progress")),((Timestamp)row.get("recorded_at")).toInstant());}
    private Facts facts(Map<String,Object> row){var n=metadata(row);long actor=((Number)row.get("actor_id")).longValue();String mac=(String)row.get("content_mac");var f=json.readValue(crypto.decrypt((String)row.get("evidence_key_id"),context(n.id(),n.requestId(),actor,n.sequence(),mac),(byte[])row.get("note_cipher")),Facts.class).validated();if(!fingerprints.keyId().equals(row.get("fingerprint_key_id"))||!f.progress().equals(n.progress())||!mac.equals(mac(n.requestId(),actor,f)))throw new IllegalStateException("내부 처리 기록 검증 실패");return f;}
    private String mac(UUID request,long actor,Facts f){return fingerprints.fingerprint("customer-review-note",json.writeValueAsString(List.of(request,actor,f)));}
    private String context(UUID id,UUID request,long actor,long sequence,String mac){return "CUSTOMER_REVIEW_NOTE:"+id+":"+request+":"+actor+":"+sequence+":"+mac;}
    private <T>T attempt(long actor,Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_REVIEW_NOTE","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 담당 권한·본인 열람·요청 키·내부 처리 기록을 확인하세요");}
}
