package dustin.cex.domains.account;

import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 고객 본인의 세션 발급/종료 기록만 제공한다. 토큰 원문·해시·운영자 인증 키는 조회하지 않는다. */
@Service
public class CustomerSessionControl {
    public enum State { ACTIVE, REVOKED, EXPIRED }
    public record Cursor(LocalDateTime createdAt,UUID id) {}
    public record Session(UUID id,LocalDateTime createdAt,LocalDateTime expiresAt,State state,boolean current) {}
    public record Page(List<Session> items,Cursor next,LocalDateTime observedAt){public Page{items=List.copyOf(items);}}
    public record Revocation(UUID id,boolean requiresLogin) {}
    private final CustomerSessionPolicy sessions;private final JdbcTemplate jdbc;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerSessionControl(CustomerSessionPolicy sessions,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){this.sessions=sessions;this.jdbc=jdbc;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public Page own(long actor,UUID sid,Cursor after){return attempt(actor,()->tx.execute(t->{
        sessions.require(actor,sid);LocalDateTime now=LocalDateTime.now();
        if(after!=null&&(after.createdAt()==null||after.id()==null||after.createdAt().isAfter(now)))throw denied(HttpStatus.BAD_REQUEST);
        String sql="SELECT session_id,created_at,expires_at,revoked FROM refresh_tokens WHERE user_id=? ";
        var rows=after==null?jdbc.queryForList(sql+"ORDER BY created_at DESC,session_id DESC LIMIT 25",actor):jdbc.queryForList(sql+"AND (created_at,session_id)<(?,?) ORDER BY created_at DESC,session_id DESC LIMIT 25",actor,Timestamp.valueOf(after.createdAt()),after.id());
        // 생성 시각은 세션 발급 시각이다. 토큰 갱신도 새 세션을 만들므로 마지막 활동/기기 로그인 시각으로 표현하지 않는다.
        var items=rows.stream().map(r->{LocalDateTime expires=time(r,"expires_at");State state=Boolean.TRUE.equals(r.get("revoked"))?State.REVOKED:expires.isAfter(now)?State.ACTIVE:State.EXPIRED;UUID id=(UUID)r.get("session_id");return new Session(id,time(r,"created_at"),expires,state,id.equals(sid));}).toList();
        var last=rows.isEmpty()?null:rows.getLast();Cursor next=rows.size()==25?new Cursor(time(last,"created_at"),(UUID)last.get("session_id")):null;
        audit.record(actor,"CUSTOMER_SESSIONS_LISTED","customer:"+actor,"SUCCESS","OWN_METADATA_ONLY");return new Page(items,next,now);
    }));}
    @Transactional(propagation=Propagation.NEVER)
    public Revocation revoke(long actor,UUID sid,UUID target){return attempt(actor,()->tx.execute(t->{
        sessions.require(actor,sid);if(target==null)throw denied(HttpStatus.BAD_REQUEST);
        // 로그인/갱신도 같은 사용자 행부터 잠근다. 회수와 갱신의 경쟁에서 한쪽의 옛 검사를 재사용하지 않는다.
        var rows=jdbc.queryForList("SELECT revoked FROM refresh_tokens WHERE user_id=? AND session_id=?",actor,target);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);
        boolean already=Boolean.TRUE.equals(rows.getFirst().get("revoked"));if(!already)jdbc.update("UPDATE refresh_tokens SET revoked=true,updated_at=? WHERE user_id=? AND session_id=?",Timestamp.valueOf(LocalDateTime.now()),actor,target);
        audit.record(actor,"CUSTOMER_SESSION_REVOKED","session:"+target,"SUCCESS",already?"ALREADY_REVOKED":"REVOKED");return new Revocation(target,target.equals(sid));
    }));}
    private LocalDateTime time(Map<String,Object> row,String key){return ((Timestamp)row.get(key)).toLocalDateTime();}
    private <T>T attempt(long actor,java.util.function.Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_SESSION_POLICY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 본인 세션과 조회 위치·회수 대상을 확인하세요");}
}
