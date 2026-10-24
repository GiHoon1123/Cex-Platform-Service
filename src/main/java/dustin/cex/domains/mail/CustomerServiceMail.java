package dustin.cex.domains.mail;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 고객이 선택한 서비스 알림만 예약한다. 마케팅 동의·법정 고지 수단으로 해석하지 않는다. */
@Service
public class CustomerServiceMail {
    public record Preference(long sequence,boolean enabled) {}
    private final JdbcTemplate jdbc;private final CustomerSessionPolicy sessions;private final CustomerMailRecipientPolicy recipients;
    private final CustomerMailOutbox outbox;private final UserRepository users;private final CddCrypto crypto;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerServiceMail(JdbcTemplate jdbc,CustomerSessionPolicy sessions,CustomerMailRecipientPolicy recipients,CustomerMailOutbox outbox,UserRepository users,CddCrypto crypto,AuditService audit,PlatformTransactionManager manager){this.jdbc=jdbc;this.sessions=sessions;this.recipients=recipients;this.outbox=outbox;this.users=users;this.crypto=crypto;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    @Transactional(propagation=Propagation.NEVER)
    public Preference choose(long actor,UUID sid,UUID key,boolean enabled){return attempt(actor,()->tx.execute(t->{
        var user=sessions.require(actor,sid);crypto.requireConfigured();if(key==null)throw denied(HttpStatus.BAD_REQUEST);
        String address=enabled?recipients.addressMac(user.getEmail()):null;
        var old=jdbc.queryForList("SELECT * FROM customer_service_mail_preferences WHERE user_id=? AND request_key=?",actor,key);
        if(!old.isEmpty()){var row=old.getFirst();if(enabled!=Boolean.TRUE.equals(row.get("enabled"))||!Objects.equals(address,row.get("email_mac"))||!crypto.keyId().equals(row.get("key_id")))throw denied(HttpStatus.CONFLICT);return new Preference(((Number)row.get("sequence")).longValue(),enabled);}
        if(enabled&&!recipients.verified(user))throw denied(HttpStatus.CONFLICT);
        long sequence=jdbc.queryForObject("SELECT coalesce(max(sequence),0)+1 FROM customer_service_mail_preferences WHERE user_id=?",Long.class,actor);
        jdbc.update("INSERT INTO customer_service_mail_preferences(id,user_id,sequence,request_key,enabled,email_mac,key_id) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),actor,sequence,key,enabled,address,crypto.keyId());
        audit.record(actor,"CUSTOMER_SERVICE_MAIL_CHOSEN","customer:"+actor,"SUCCESS",enabled?"ENABLED_VERIFIED_ADDRESS":"DISABLED");return new Preference(sequence,enabled);
    }));}
    public Preference own(long actor,UUID sid){return attempt(actor,()->tx.execute(t->{var user=sessions.require(actor,sid);long sequence=jdbc.queryForObject("SELECT coalesce(max(sequence),0) FROM customer_service_mail_preferences WHERE user_id=?",Long.class,actor);var p=new Preference(sequence,recipients.allowed(user));audit.record(actor,"CUSTOMER_SERVICE_MAIL_STATUS","customer:"+actor,"SUCCESS","CURRENT_ELIGIBILITY");return p;}));}
    /** 공개 회신/승인 기록을 저장한 바로 그 트랜잭션에서만 호출한다. 이전 알림을 소급 발송하지 않는다. */
    public void reserve(long user,CustomerMailOutbox.Kind kind,UUID reference,Instant observedAt){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("공개 알림 업무 트랜잭션이 필요합니다");
        if(kind==null||kind==CustomerMailOutbox.Kind.EMAIL_VERIFICATION||reference==null||observedAt==null)throw new IllegalArgumentException("공개 알림 근거가 필요합니다");
        var owner=users.findById(user).orElseThrow();if(!recipients.allowed(owner))return;
        // 예약 이후 동의를 철회해도 원본 예약을 지우지 않는다. 출력 직전에 다시 검사하고 억제 결과를 남긴다.
        outbox.enqueue(user,kind,reference,owner.getEmail(),null,observedAt.plusSeconds(86400));
    }
    private <T>T attempt(long actor,Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_SERVICE_MAIL_POLICY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 본인 세션과 검증된 수신 주소를 확인하세요");}
}
