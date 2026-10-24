package dustin.cex.domains.mail;

import dustin.cex.domains.security.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 현재 고객의 주소 소유 확인이다. 신원 확인·비밀번호 복구·다른 주소로의 변경 권한을 부여하지 않는다. */
@Service
public class CustomerEmailVerification {
    public record Status(boolean verified,boolean recentlyVerified) {}
    private final CustomerMailRecipientPolicy recipients;
    private final CustomerSessionPolicy sessions;
    private final CustomerEmailChallenges challenges;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public CustomerEmailVerification(CustomerMailRecipientPolicy recipients,CustomerSessionPolicy sessions,CustomerEmailChallenges challenges,AuditService audit,PlatformTransactionManager manager){
        this.recipients=recipients;this.sessions=sessions;this.challenges=challenges;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Transactional(propagation=Propagation.NEVER)
    public CustomerEmailChallenges.Receipt request(long actor,UUID sid,UUID key){return attempt(actor,()->tx.execute(t->{
        var user=sessions.require(actor,sid);var receipt=challenges.issue(user,key,user.getEmail(),CustomerMailOutbox.Kind.EMAIL_VERIFICATION);
        audit.record(actor,"CUSTOMER_EMAIL_REQUESTED","email-check:"+receipt.challengeId(),"SUCCESS","QUEUED_ONLY");return receipt;
    }));}
    @Transactional(propagation=Propagation.NEVER)
    public Status confirm(long actor,UUID sid,UUID id,String token){return attempt(actor,()->tx.execute(t->{
        var user=sessions.require(actor,sid);challenges.confirmCurrent(user,sid,id,token);
        return new Status(true,recipients.recentlyVerified(user,sid));
    }));}
    public Status status(long actor,UUID sid){return attempt(actor,()->tx.execute(t->{
        var user=sessions.require(actor,sid);var result=new Status(recipients.verified(user),recipients.recentlyVerified(user,sid));
        audit.record(actor,"CUSTOMER_EMAIL_STATUS","customer:"+actor,"SUCCESS","OWN_STATUS");return result;
    }));}
    private <T>T attempt(long actor,java.util.function.Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){if(e.getStatusCode().is4xxClientError())audit.denied(actor,"CUSTOMER_EMAIL_POLICY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
}
