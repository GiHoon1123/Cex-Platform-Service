package dustin.cex.domains.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 복구가 실제로 확정된 사실만 본인에게 제공한다. 코드·해시·서명·세션·메일 수신 결과는 포함하지 않는다. */
@Service
public class CustomerRecoveryHistory {
    public record Cursor(Instant createdAt,UUID id) {}
    public record Change(UUID id,Instant createdAt,String kind,int keysChanged) {}
    public record Page(List<Change> items,Cursor next) {public Page {items=List.copyOf(items);}}
    private final CustomerSessionPolicy sessions;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public CustomerRecoveryHistory(CustomerSessionPolicy sessions,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){
        this.sessions=sessions;this.jdbc=jdbc;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    public Page own(long actor,UUID sid,Cursor after){
        try{return tx.execute(t->{
            // 실제 고객/본인 세션의 현재 자격을 검사한다. 단순 이력 조회에 새 업무 서명은 요구하지 않는다.
            sessions.require(actor,sid);
            // 보안 이력 커서는 UTC의1970년부터 현재까지다. JDBC 변환의 범위 초과를 먼저 막는다.
            if(after!=null&&(after.id()==null||after.createdAt()==null||after.createdAt().isBefore(Instant.EPOCH)||after.createdAt().isAfter(Instant.now())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"조회 위치를 확인하세요");
            String sql="""
                SELECT e.id,e.created_at,e.kind,
                  (SELECT count(*) FROM customer_recovery_key_changes k WHERE k.event_id=e.id AND k.user_id=e.user_id) key_count
                  FROM customer_recovery_events e WHERE e.user_id=?
                """;
            String order=" ORDER BY e.created_at DESC,e.id DESC LIMIT 26";
            var rows=after==null?jdbc.queryForList(sql+order,actor):jdbc.queryForList(sql+" AND (e.created_at,e.id)<(?,?)"+order,actor,Timestamp.from(after.createdAt()),after.id());
            // 한 행을 더 읽어 정확히25건일 때 빈 다음 페이지를 요청하지 않게 한다.
            var items=rows.stream().limit(25).map(row->new Change((UUID)row.get("id"),((Timestamp)row.get("created_at")).toInstant(),(String)row.get("kind"),Math.toIntExact(((Number)row.get("key_count")).longValue()))).toList();
            var last=items.isEmpty()?null:items.getLast();
            audit.record(actor,"CUSTOMER_RECOVERY_HISTORY_READ","customer:"+actor,"SUCCESS","OWN_CONFIRMED_FACTS_ONLY");
            return new Page(items,rows.size()>25?new Cursor(last.createdAt(),last.id()):null);
        });}catch(ResponseStatusException e){
            audit.denied(actor,"CUSTOMER_RECOVERY_HISTORY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;
        }
    }
}
