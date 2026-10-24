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

/** 현재 비밀번호·등록된 고객의 내용 결합 서명·해시·전체 회수·변경 원본·감사를 함께 확정한다. 서버 장애는 고객 실패로 집계하지 않는다. */
@Service
public class CustomerPasswordChange {
    public record Input(String currentPassword,String newPassword,CustomerPasswordAuthorization.Proof proof){@Override public String toString(){return "Input[비밀번호 생략]";}}
    public record Receipt(UUID id,Instant changedAt,boolean requiresLogin) {}
    private record Outcome(Receipt receipt,HttpStatus denial) {}
    private final CustomerPasswordNotice notice;
    private final CustomerMfaPolicy mfa;
    private final CustomerPasswordAuthorization authorization;
    private final CustomerSessionPolicy sessions;private final UserRepository users;private final RefreshTokenRepository tokens;
    private final PasswordService passwords;private final CustomerPasswordPolicy policy;private final JdbcTemplate jdbc;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerPasswordChange(CustomerSessionPolicy sessions,UserRepository users,RefreshTokenRepository tokens,PasswordService passwords,CustomerPasswordPolicy policy,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager,CustomerPasswordNotice notice,CustomerMfaPolicy mfa,CustomerPasswordAuthorization authorization){this.mfa=mfa;this.authorization=authorization;this.notice=notice;this.sessions=sessions;this.users=users;this.tokens=tokens;this.passwords=passwords;this.policy=policy;this.jdbc=jdbc;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    @Transactional(propagation=Propagation.NEVER)
    public Receipt change(long actor,UUID sid,Input input){
        try{
            Outcome result=tx.execute(t->{
                var user=sessions.require(actor,sid);HttpStatus failure=validate(actor,user,input);if(failure!=null)return new Outcome(null,failure);
                UUID id=UUID.randomUUID();
                if(mfa.required(actor))authorization.authorize(actor,sid,input.newPassword(),input.proof(),id);
                else if(input.proof()!=null)throw denied(HttpStatus.BAD_REQUEST);
                user.setPasswordHash(passwords.hash(input.newPassword()));users.saveAndFlush(user);
                // 현재 세션도 남기지 않는다. 갱신/로그인이 같은 사용자 잠금을 사용하므로 옛 자격으로 새 세션을 만들지 못한다.
                tokens.revokeAllByUserId(actor,LocalDateTime.now());
                jdbc.update("INSERT INTO customer_password_changes(id,user_id,session_id) VALUES(?,?,?)",id,actor,sid);
                Instant at=jdbc.queryForObject("SELECT changed_at FROM customer_password_changes WHERE id=?",Timestamp.class,id).toInstant();
                notice.reserve(actor,id,at);
                audit.record(actor,"CUSTOMER_PASSWORD_CHANGED","password-change:"+id,"SUCCESS","ALL_SESSIONS_REVOKED");
                return new Outcome(new Receipt(id,at,true),null);
            });
            if(result.denial()!=null)throw denied(result.denial());return result.receipt();
        }catch(ResponseStatusException e){if(e.getStatusCode().is4xxClientError())audit.denied(actor,"CUSTOMER_PASSWORD_POLICY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private record Prepared(CustomerPasswordAuthorization.Options options,HttpStatus denial){}
    @Transactional(propagation=Propagation.NEVER)
    public CustomerPasswordAuthorization.Options options(long actor,UUID sid,Input input){
        try{
            var result=tx.execute(t->{
                var user=sessions.require(actor,sid);HttpStatus failure=validate(actor,user,input);
                if(failure!=null)return new Prepared(null,failure);
                return new Prepared(authorization.prepare(actor,sid,input.newPassword()),null);
            });
            if(result.denial()!=null)throw denied(result.denial());return result.options();
        }catch(ResponseStatusException e){if(e.getStatusCode().is4xxClientError())audit.denied(actor,"CUSTOMER_PASSWORD_POLICY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private HttpStatus validate(long actor,dustin.cex.domains.auth.model.entity.User user,Input input){
        if(input==null||!policy.accepts(input.newPassword()))throw denied(HttpStatus.BAD_REQUEST);
        String target="customer:"+actor;
        if(audit.tooManyFailures(actor,"CUSTOMER_PASSWORD_CHANGE",target)){audit.record(actor,"CUSTOMER_PASSWORD_CHANGE",target,"DENIED","RATE_LIMITED");return HttpStatus.TOO_MANY_REQUESTS;}
        if(!passwords.verify(input.currentPassword(),user.getPasswordHash())){
            // 사용자 잠금 안에서 실패까지 커밋한다. 호출자는 트랜잭션 밖에서 오류 응답으로 변환한다.
            audit.record(actor,"CUSTOMER_PASSWORD_CHANGE",target,"DENIED","WRONG_PASSWORD");return HttpStatus.FORBIDDEN;
        }
        if(input.currentPassword().equals(input.newPassword()))throw denied(HttpStatus.BAD_REQUEST);
        return null;
    }
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 본인 세션·비밀번호와 새 비밀번호 정책을 확인하세요");}
}
