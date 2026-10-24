package dustin.cex.domains.mail;

import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 발송 실패를 원문 없이 점검한다. 조회는 작업을 선점하거나 재발송하지 않는다. */
@Service
public class CustomerMailOperations {
    public enum Signal { EXPIRED, EXHAUSTED, LEASE_EXPIRED, DELAYED }
    public record Cursor(Instant createdAt,UUID id) {}
    public record Item(UUID id,String kind,String status,int attempts,Instant createdAt,Instant expiresAt,Signal signal) {}
    public record Page(List<Item> items,Cursor next,int scanned,Instant observedAt,String semantics){public Page{items=List.copyOf(items);}}
    public record Event(UUID id,int attempt,String phase,String outcome,Instant recordedAt) {}
    public record Events(List<Event> items,Cursor next){public Events{items=List.copyOf(items);}}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final AuditService audit;private final TransactionTemplate tx;
    public CustomerMailOperations(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,AuditService audit,PlatformTransactionManager manager){this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.audit=audit;tx=new TransactionTemplate(manager);}
    public Page attention(long actor,UUID sid,Cursor after){return attempt(actor,()->tx.execute(t->{
        staff(actor,sid);Instant now=Instant.now();validate(after,now);
        String query="SELECT j.id,j.kind,j.created_at,j.expires_at,d.status,d.attempts,d.lease_until,d.next_attempt_at FROM customer_mail_jobs j JOIN customer_mail_delivery d ON d.job_id=j.id WHERE d.status IN ('PENDING','RETRY','SENDING','EXHAUSTED') ";
        var rows=after==null?jdbc.queryForList(query+"ORDER BY j.created_at,j.id LIMIT 25"):jdbc.queryForList(query+"AND (j.created_at,j.id)>(?,?) ORDER BY j.created_at,j.id LIMIT 25",Timestamp.from(after.createdAt()),after.id());
        var items=new ArrayList<Item>();for(var row:rows){var item=item(row,now);if(item.signal()!=null)items.add(item);}
        // 필터 뒤 결과가 비어도 실제 읽은 후보의 마지막 위치를 반환한다. 전체 목록은 처음부터 새로 점검한다.
        var last=rows.isEmpty()?null:rows.getLast();Cursor next=rows.size()==25?new Cursor(time(last,"created_at"),(UUID)last.get("id")):null;
        audit.record(actor,"CUSTOMER_MAIL_ATTENTION","operator:"+actor,"SUCCESS","METADATA_ONLY");
        return new Page(items,next,rows.size(),now,"REFRESH_FROM_START_NOT_A_DURABLE_CHECKPOINT");
    }));}
    public Item ownJob(long actor,UUID sid,UUID id){return attempt(actor,()->tx.execute(t->{staff(actor,sid);var result=metadata(id,Instant.now());audit.record(actor,"CUSTOMER_MAIL_INSPECTED","mail:"+id,"SUCCESS","NO_RECIPIENT_OR_TOKEN");return result;}));}
    public Events events(long actor,UUID sid,UUID id,Cursor after){return attempt(actor,()->tx.execute(t->{
        staff(actor,sid);validate(after,Instant.now());metadata(id,Instant.now());
        String sql="SELECT id,attempt,phase,outcome,recorded_at FROM customer_mail_attempts WHERE job_id=? ";
        var rows=after==null?jdbc.queryForList(sql+"ORDER BY recorded_at,id LIMIT 25",id):jdbc.queryForList(sql+"AND (recorded_at,id)>(?,?) ORDER BY recorded_at,id LIMIT 25",id,Timestamp.from(after.createdAt()),after.id());
        var items=rows.stream().map(r->new Event((UUID)r.get("id"),((Number)r.get("attempt")).intValue(),(String)r.get("phase"),(String)r.get("outcome"),time(r,"recorded_at"))).toList();
        var last=rows.isEmpty()?null:rows.getLast();audit.record(actor,"CUSTOMER_MAIL_ATTEMPTS_READ","mail:"+id,"SUCCESS","NO_LEASE_TOKEN");
        return new Events(items,rows.size()==25?new Cursor(time(last,"recorded_at"),(UUID)last.get("id")):null);
    }));}
    private Item metadata(UUID id,Instant now){var rows=jdbc.queryForList("SELECT j.id,j.kind,j.created_at,j.expires_at,d.status,d.attempts,d.lease_until,d.next_attempt_at FROM customer_mail_jobs j JOIN customer_mail_delivery d ON d.job_id=j.id WHERE j.id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);return item(rows.getFirst(),now);}
    private Item item(Map<String,Object> r,Instant now){String status=(String)r.get("status");Instant expires=time(r,"expires_at");Signal signal=null;
        // 접수 확정/억제는 이미 끝난 상태다. 만료가 지나도 대기 경보로 되돌리지 않는다.
        if(!Set.of("SMTP_ACCEPTED","SUPPRESSED").contains(status)){
            if(!expires.isAfter(now))signal=Signal.EXPIRED;
            else if("EXHAUSTED".equals(status))signal=Signal.EXHAUSTED;
            else if("SENDING".equals(status)&&r.get("lease_until")!=null&&!time(r,"lease_until").isAfter(now))signal=Signal.LEASE_EXPIRED;
            else if(("PENDING".equals(status)&&time(r,"created_at").isBefore(now.minusSeconds(300)))||("RETRY".equals(status)&&time(r,"next_attempt_at").isBefore(now.minusSeconds(300))))signal=Signal.DELAYED;
        }
        return new Item((UUID)r.get("id"),(String)r.get("kind"),status,((Number)r.get("attempts")).intValue(),time(r,"created_at"),expires,signal);
    }
    private void staff(long actor,UUID sid){sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);}
    private void validate(Cursor c,Instant now){if(c!=null&&(c.createdAt()==null||c.id()==null||c.createdAt().isBefore(Instant.EPOCH)||c.createdAt().isAfter(now)))throw denied(HttpStatus.BAD_REQUEST);}
    private static Instant time(Map<String,Object> row,String key){return ((Timestamp)row.get(key)).toInstant();}
    private <T>T attempt(long actor,java.util.function.Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_MAIL_OPERATIONS","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 운영 인증·작업 식별자·조회 위치를 확인하세요");}
}
