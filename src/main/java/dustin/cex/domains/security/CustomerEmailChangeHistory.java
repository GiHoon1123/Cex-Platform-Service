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

/** 서명 요청과 실제 완료를 구분한다. 완료가 없다는 이유로 실패·취소·현재 실행 가능 여부를 추측하지 않는다. */
@Service
public class CustomerEmailChangeHistory {
    public record Cursor(Instant requestedAt,UUID id) {}
    public record Completion(UUID id,Instant changedAt) {}
    public record Entry(UUID id,Instant requestedAt,Instant expiresAt,Completion completed) {}
    public record Page(List<Entry> items,Cursor next){public Page{items=List.copyOf(items);}}
    private final CustomerSessionPolicy sessions;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public CustomerEmailChangeHistory(CustomerSessionPolicy sessions,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){
        this.sessions=sessions;this.jdbc=jdbc;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    public Page own(long actor,UUID sid,Cursor after){
        try{return tx.execute(t->{
            sessions.require(actor,sid);
            // 보안 이력 커서는 UTC의1970년부터 현재까지다. JDBC 변환의 범위 초과를 먼저 막는다.
            if(after!=null&&(after.id()==null||after.requestedAt()==null||after.requestedAt().isBefore(Instant.EPOCH)||after.requestedAt().isAfter(Instant.now())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"조회 위치를 확인하세요");
            // 서명·토큰·주소 원문/지문·발송 암호문을 읽지 않는다. 키 장애와 payload 파기 후에도 최소 이력은 유지한다.
            String sql="""
                SELECT r.id,r.created_at,c.expires_at,e.id change_id,e.changed_at
                  FROM customer_email_change_requests r
                  JOIN customer_email_challenges c ON c.id=r.id AND c.user_id=r.user_id
                  LEFT JOIN customer_email_changes e ON e.request_id=r.id AND e.user_id=r.user_id
                 WHERE r.user_id=?
                """;
            String order=" ORDER BY r.created_at DESC,r.id DESC LIMIT 26";
            var rows=after==null?jdbc.queryForList(sql+order,actor):jdbc.queryForList(sql+" AND (r.created_at,r.id)<(?,?)"+order,actor,Timestamp.from(after.requestedAt()),after.id());
            var items=rows.stream().limit(25).map(row->{
                Completion completed=row.get("change_id")==null?null:new Completion((UUID)row.get("change_id"),((Timestamp)row.get("changed_at")).toInstant());
                return new Entry((UUID)row.get("id"),((Timestamp)row.get("created_at")).toInstant(),((Timestamp)row.get("expires_at")).toInstant(),completed);
            }).toList();
            var last=items.isEmpty()?null:items.getLast();
            audit.record(actor,"CUSTOMER_EMAIL_CHANGE_HISTORY_READ","customer:"+actor,"SUCCESS","OWN_REQUEST_AND_COMPLETION_FACTS");
            return new Page(items,rows.size()>25?new Cursor(last.requestedAt(),last.id()):null);
        });}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_EMAIL_CHANGE_HISTORY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
}
