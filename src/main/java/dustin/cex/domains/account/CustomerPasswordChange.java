package dustin.cex.domains.account;

import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.*;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 현재 비밀번호 재확인·새 해시·전체 세션 회수·변경 사실·필수 감사를 함께 확정한다. */
@Service
public class CustomerPasswordChange {
    public record Input(String currentPassword,String newPassword){@Override public String toString(){return "Input[비밀번호 생략]";}}
    public record Receipt(UUID id,Instant changedAt,boolean requiresLogin) {}
    private record Outcome(Receipt receipt,HttpStatus denial) {}
    private final CustomerSessionPolicy sessions;private final UserRepository users;private final RefreshTokenRepository tokens;
    private final PasswordService passwords;private final CustomerPasswordPolicy policy;private final JdbcTemplate jdbc;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerPasswordChange(CustomerSessionPolicy sessions,UserRepository users,RefreshTokenRepository tokens,PasswordService passwords,CustomerPasswordPolicy policy,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){this.sessions=sessions;this.users=users;this.tokens=tokens;this.passwords=passwords;this.policy=policy;this.jdbc=jdbc;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    @Transactional(propagation=Propagation.NEVER)
    public Receipt change(long actor,UUID sid,Input input){
        try{
            Outcome result=tx.execute(t->{
                var user=sessions.require(actor,sid);if(input==null||!policy.accepts(input.newPassword()))throw denied(HttpStatus.BAD_REQUEST);
                String target="customer:"+actor;
                if(audit.tooManyFailures(actor,"CUSTOMER_PASSWORD_CHANGE",target)){audit.record(actor,"CUSTOMER_PASSWORD_CHANGE",target,"DENIED","RATE_LIMITED");return new Outcome(null,HttpStatus.TOO_MANY_REQUESTS);}
                if(!passwords.verify(input.currentPassword(),user.getPasswordHash())){
                    // 사용자 잠금 안에서 실패까지 커밋한다. 밖에서 예외로 변환해 실패 집계가 롤백되지 않게 한다.
                    audit.record(actor,"CUSTOMER_PASSWORD_CHANGE",target,"DENIED","WRONG_PASSWORD");return new Outcome(null,HttpStatus.FORBIDDEN);
                }
                if(input.currentPassword().equals(input.newPassword()))throw denied(HttpStatus.BAD_REQUEST);
                user.setPasswordHash(passwords.hash(input.newPassword()));users.saveAndFlush(user);
                // 현재 세션도 남기지 않는다. 갱신/로그인이 같은 사용자 잠금을 사용하므로 옛 자격으로 새 세션을 만들지 못한다.
                tokens.revokeAllByUserId(actor,LocalDateTime.now());UUID id=UUID.randomUUID();
                jdbc.update("INSERT INTO customer_password_changes(id,user_id,session_id) VALUES(?,?,?)",id,actor,sid);
                Instant at=jdbc.queryForObject("SELECT changed_at FROM customer_password_changes WHERE id=?",Timestamp.class,id).toInstant();
                audit.record(actor,"CUSTOMER_PASSWORD_CHANGED","password-change:"+id,"SUCCESS","ALL_SESSIONS_REVOKED");
                return new Outcome(new Receipt(id,at,true),null);
            });
            if(result.denial()!=null)throw denied(result.denial());return result.receipt();
        }catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_PASSWORD_POLICY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 본인 세션·비밀번호와 새 비밀번호 정책을 확인하세요");}
}
