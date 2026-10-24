package dustin.cex.domains.mail;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.*;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 이메일 소유 확인은 수신 주소 검증이다. 신원 확인·비밀번호 복구·거래 허가를 부여하지 않는다. */
@Service
public class CustomerEmailVerification {
    public record Receipt(UUID challengeId,Instant expiresAt) {}
    public record Status(boolean verified,boolean recentlyVerified) {}
    private final CustomerMailRecipientPolicy recipients;
    private final JdbcTemplate jdbc;
    private final CddCrypto crypto;
    private final CustomerSessionPolicy sessions;
    private final CustomerMailOutbox outbox;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public CustomerEmailVerification(JdbcTemplate jdbc,CddCrypto crypto,CustomerSessionPolicy sessions,CustomerMailOutbox outbox,AuditService audit,PlatformTransactionManager manager,CustomerMailRecipientPolicy recipients){this.recipients=recipients;this.jdbc=jdbc;this.crypto=crypto;this.sessions=sessions;this.outbox=outbox;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}

    @Transactional(propagation=Propagation.NEVER)
    public Receipt request(long actor,UUID sid,UUID key){return attempt(actor,()->tx.execute(t->{
        var user=sessions.require(actor,sid);crypto.requireConfigured();if(key==null)throw denied(HttpStatus.BAD_REQUEST);
        String address=addressMac(user.getEmail());validateAddress(user.getEmail());
        var old=jdbc.queryForList("SELECT id,expires_at,email_mac,key_id FROM customer_email_challenges WHERE user_id=? AND request_key=?",actor,key);
        // 응답 유실 재전송은 새 토큰이나 새 메일을 만들지 않는다. 주소가 달라졌다면 같은 키를 재사용할 수 없다.
        if(!old.isEmpty()){var row=old.getFirst();if(!address.equals(row.get("email_mac"))||!crypto.keyId().equals(row.get("key_id")))throw denied(HttpStatus.CONFLICT);return receipt(row);}
        String rate=crypto.fingerprint("customer-email-rate-v1",user.getEmail().toLowerCase(Locale.ROOT));
        // 계정 잠금은 동일 고객을, 이 주소별 트랜잭션 잠금은 대소문자 별칭 계정의 발송 경쟁을 제한한다.
        // 해시 충돌은 불필요한 직렬화만 늘린다. 이 잠금은 Java 메일 접수에만 있고 Rust 코어와 무관하다.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rate);
        if(jdbc.queryForObject("SELECT count(*) FROM customer_email_challenges WHERE (user_id=? OR rate_mac=?) AND created_at>clock_timestamp()-interval '60 seconds'",Long.class,actor,rate)>0||jdbc.queryForObject("SELECT count(*) FROM customer_email_challenges WHERE (user_id=? OR rate_mac=?) AND created_at>clock_timestamp()-interval '1 hour'",Long.class,actor,rate)>=3)throw denied(HttpStatus.TOO_MANY_REQUESTS);
        UUID id=UUID.randomUUID();byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires=Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO customer_email_challenges(id,user_id,request_key,email_mac,rate_mac,key_id,token_hash,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,actor,key,address,rate,crypto.keyId(),digest(id,token),Timestamp.from(expires));
        outbox.enqueue(actor,CustomerMailOutbox.Kind.EMAIL_VERIFICATION,id,user.getEmail(),token,expires);
        audit.record(actor,"CUSTOMER_EMAIL_REQUESTED","email-check:"+id,"SUCCESS","QUEUED_ONLY");return new Receipt(id,expires);
    }));}

    @Transactional(propagation=Propagation.NEVER)
    public Status confirm(long actor,UUID sid,UUID id,String token){return attempt(actor,()->tx.execute(t->{
        var user=sessions.require(actor,sid);crypto.requireConfigured();if(id==null||token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw denied(HttpStatus.BAD_REQUEST);
        var rows=jdbc.queryForList("SELECT * FROM customer_email_challenges WHERE id=? AND user_id=?",id,actor);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();
        // 인증 전용 실패 한도다. 비밀번호 실패 집계와 섞지 않으며 원본 토큰을 감사에 넣지 않는다.
        if(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_EMAIL_CHECK' AND detail='WRONG_TOKEN' AND occurred_at>clock_timestamp()-interval '15 minutes'",Long.class,actor)>=5)throw denied(HttpStatus.TOO_MANY_REQUESTS);
        if(!MessageDigest.isEqual(digest(id,token).getBytes(StandardCharsets.US_ASCII),((String)row.get("token_hash")).getBytes(StandardCharsets.US_ASCII))){audit.denied(actor,"CUSTOMER_EMAIL_CHECK","email-check:"+id,"WRONG_TOKEN");throw denied(HttpStatus.FORBIDDEN);}
        if(!crypto.keyId().equals(row.get("key_id"))||!addressMac(user.getEmail()).equals(row.get("email_mac")))throw denied(HttpStatus.CONFLICT);
        // 소모된 토큰의 같은 요청은 기존 사실만 반환한다. 새 검증 효과나 다른 주소에 대한 권한은 만들지 않는다.
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_email_confirmations WHERE challenge_id=?)",Boolean.class,id))return new Status(true,recipients.recentlyVerified(user,sid));
        if(!((Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now()))throw denied(HttpStatus.GONE);
        jdbc.update("INSERT INTO customer_email_confirmations(challenge_id,user_id,email_mac,key_id) VALUES(?,?,?,?)",id,actor,row.get("email_mac"),crypto.keyId());
        // 실제 토큰을 확인한 현재 세션을 불변 사실로 남긴다. 소비된 토큰의 재호출로 이 결합을 갱신하지 않는다.
        jdbc.update("INSERT INTO customer_email_confirmation_sessions(challenge_id,session_id) VALUES(?,?)",id,sid);
        audit.record(actor,"CUSTOMER_EMAIL_VERIFIED","email-check:"+id,"SUCCESS","CURRENT_ADDRESS_ONLY");return new Status(true,recipients.recentlyVerified(user,sid));
    }));}
    public Status status(long actor,UUID sid){return attempt(actor,()->tx.execute(t->{var user=sessions.require(actor,sid);var result=new Status(verified(user),recipients.recentlyVerified(user,sid));audit.record(actor,"CUSTOMER_EMAIL_STATUS","customer:"+actor,"SUCCESS","OWN_STATUS");return result;}));}
    /** 현재 User의 주소와 완전히 같은 지문만 검증 사실로 인정한다. 주소 변경은 자동으로 미검증이 된다. */
    public boolean verified(User user){return recipients.verified(user);}
    public String addressMac(String email){return recipients.addressMac(email);}
    private Receipt receipt(Map<String,Object> row){return new Receipt((UUID)row.get("id"),((Timestamp)row.get("expires_at")).toInstant());}
    private void validateAddress(String value){try{var a=new InternetAddress(value,true);a.validate();if(!a.getAddress().equals(value)||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();}catch(Exception e){throw denied(HttpStatus.BAD_REQUEST);}}
    private String digest(UUID id,String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((id+":"+token).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("토큰 해시 알고리즘이 없습니다");}}
    private <T>T attempt(long actor,Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_EMAIL_POLICY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 고객 세션·주소·확인 기한을 확인하세요");}
}
