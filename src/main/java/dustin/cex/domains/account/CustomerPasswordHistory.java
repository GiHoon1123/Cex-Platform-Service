package dustin.cex.domains.account;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 본인이 다시 로그인한 뒤 변경 사실을 확인한다. 열람은 변경/고객 확인 영수증을 만들지 않는다. */
@Service
public class CustomerPasswordHistory {
    public record Cursor(Instant changedAt,UUID id) {}
    public record Change(UUID id,Instant changedAt) {}
    public record Page(List<Change> items,Cursor next){public Page{items=List.copyOf(items);}}
    private final CustomerSessionPolicy sessions;private final JdbcTemplate jdbc;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerPasswordHistory(CustomerSessionPolicy sessions,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){this.sessions=sessions;this.jdbc=jdbc;this.audit=audit;tx=new TransactionTemplate(manager);}
    public Page own(long actor,UUID sid,Cursor after){try{return tx.execute(t->{
        // 보안 이력 커서는 UTC의1970년부터 현재까지다. JDBC 변환의 범위 초과를 먼저 막는다.
        sessions.require(actor,sid);if(after!=null&&(after.id()==null||after.changedAt()==null||after.changedAt().isBefore(Instant.EPOCH)||after.changedAt().isAfter(Instant.now())))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"조회 위치를 확인하세요");
        String sql="SELECT id,changed_at FROM customer_password_changes WHERE user_id=? ";var rows=after==null?jdbc.queryForList(sql+"ORDER BY changed_at DESC,id DESC LIMIT 25",actor):jdbc.queryForList(sql+"AND (changed_at,id)<(?,?) ORDER BY changed_at DESC,id DESC LIMIT 25",actor,Timestamp.from(after.changedAt()),after.id());
        var items=rows.stream().map(r->new Change((UUID)r.get("id"),((Timestamp)r.get("changed_at")).toInstant())).toList();var last=rows.isEmpty()?null:items.getLast();
        audit.record(actor,"CUSTOMER_PASSWORD_HISTORY_READ","customer:"+actor,"SUCCESS","OWN_CHANGE_FACTS_ONLY");return new Page(items,rows.size()==25?new Cursor(last.changedAt(),last.id()):null);
    });}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_PASSWORD_HISTORY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
}
