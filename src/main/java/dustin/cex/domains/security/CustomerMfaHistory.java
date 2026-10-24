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

/** 현재 자격을 통과한 고객이 본인의 키 추가/폐기 사실을 읽는다. 조회를 승인/열람 확약으로 기록하지 않는다. */
@Service
public class CustomerMfaHistory {
    public record Cursor(Instant createdAt,UUID id){}
    public record Change(UUID id,Instant createdAt,CustomerMfaKeyChanges.Kind kind,String fingerprint){}
    public record Page(List<Change> items,Cursor next){public Page{items=List.copyOf(items);}}
    private final CustomerSessionPolicy sessions;private final JdbcTemplate jdbc;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerMfaHistory(CustomerSessionPolicy sessions,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){this.sessions=sessions;this.jdbc=jdbc;this.audit=audit;tx=new TransactionTemplate(manager);}
    public Page own(long actor,UUID sid,Cursor after){try{return tx.execute(t->{
        // 보안 이력 커서는 UTC의1970년부터 현재까지다. JDBC 변환의 범위 초과를 먼저 막는다.
        sessions.require(actor,sid);if(after!=null&&(after.id()==null||after.createdAt()==null||after.createdAt().isBefore(Instant.EPOCH)||after.createdAt().isAfter(Instant.now())))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"조회 위치를 확인하세요");
        String sql="SELECT id,created_at,kind,credential_fingerprint FROM customer_mfa_key_changes WHERE user_id=? ";
        var rows=after==null?jdbc.queryForList(sql+"ORDER BY created_at DESC,id DESC LIMIT 25",actor):jdbc.queryForList(sql+"AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT 25",actor,Timestamp.from(after.createdAt()),after.id());
        var items=rows.stream().map(r->new Change((UUID)r.get("id"),((Timestamp)r.get("created_at")).toInstant(),CustomerMfaKeyChanges.Kind.valueOf((String)r.get("kind")),(String)r.get("credential_fingerprint"))).toList();var last=items.isEmpty()?null:items.getLast();
        audit.record(actor,"CUSTOMER_MFA_HISTORY_READ","customer:"+actor,"SUCCESS","OWN_CHANGE_FACTS_ONLY");return new Page(items,rows.size()==25?new Cursor(last.createdAt(),last.id()):null);
    });}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_MFA_HISTORY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
}
