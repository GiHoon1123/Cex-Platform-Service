package dustin.cex.domains.mail;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.cdd.CddCrypto;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 현재 주소 확인과 새 주소 변경이 공유하는 일회 토큰·발송 제한·불변 확인 사실이다.
 * 계정/서명 권한과 주소 변경은 호출 업무가 담당한다. 호출자는 고객 행 잠금과 트랜잭션을 유지한다.
 */
@Component
@Transactional(propagation=Propagation.MANDATORY)
public class CustomerEmailChallenges {
    public record Receipt(UUID challengeId,Instant expiresAt) {}
    private final JdbcTemplate jdbc;
    private final CddCrypto crypto;
    private final CustomerMailRecipientPolicy recipients;
    private final CustomerMailOutbox outbox;
    private final dustin.cex.domains.security.AuditService audit;
    public CustomerEmailChallenges(JdbcTemplate jdbc,CddCrypto crypto,CustomerMailRecipientPolicy recipients,CustomerMailOutbox outbox,dustin.cex.domains.security.AuditService audit){this.jdbc=jdbc;this.crypto=crypto;this.recipients=recipients;this.outbox=outbox;this.audit=audit;}
    public Receipt issue(User user,UUID key,String destination,CustomerMailOutbox.Kind kind){
        long actor=user.getId();if(kind==null||!kind.verification())throw denied(HttpStatus.BAD_REQUEST);
        crypto.requireConfigured();if(key==null)throw denied(HttpStatus.BAD_REQUEST);
        validateAddress(destination);String address=addressMac(destination);
        var old=jdbc.queryForList("SELECT id,expires_at,email_mac,key_id FROM customer_email_challenges WHERE user_id=? AND request_key=?",actor,key);
        // 응답 유실 재전송은 새 토큰이나 새 메일을 만들지 않는다. 주소가 달라졌다면 같은 키를 재사용할 수 없다.
        if(!old.isEmpty()){var row=old.getFirst();if(!address.equals(row.get("email_mac"))||!crypto.keyId().equals(row.get("key_id")))throw denied(HttpStatus.CONFLICT);if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_mail_jobs WHERE reference_id=? AND user_id=? AND kind=?)",Boolean.class,row.get("id"),actor,kind.name()))throw denied(HttpStatus.CONFLICT);return receipt(row);}
        String rate=crypto.fingerprint("customer-email-rate-v1",destination.toLowerCase(Locale.ROOT));
        // 계정 잠금은 동일 고객을, 이 주소별 트랜잭션 잠금은 대소문자 별칭 계정의 발송 경쟁을 제한한다.
        // 해시 충돌은 불필요한 직렬화만 늘린다. 이 잠금은 Java 메일 접수에만 있고 Rust 코어와 무관하다.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rate);
        if(jdbc.queryForObject("SELECT count(*) FROM customer_email_challenges WHERE (user_id=? OR rate_mac=?) AND created_at>clock_timestamp()-interval '60 seconds'",Long.class,actor,rate)>0||jdbc.queryForObject("SELECT count(*) FROM customer_email_challenges WHERE (user_id=? OR rate_mac=?) AND created_at>clock_timestamp()-interval '1 hour'",Long.class,actor,rate)>=3)throw denied(HttpStatus.TOO_MANY_REQUESTS);
        UUID id=UUID.randomUUID();byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Arrays.fill(bytes,(byte)0);
        Instant expires=Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.MICROS);
        jdbc.update("INSERT INTO customer_email_challenges(id,user_id,request_key,email_mac,rate_mac,key_id,token_hash,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,actor,key,address,rate,crypto.keyId(),digest(id,token),Timestamp.from(expires));
        outbox.enqueue(actor,kind,id,destination,token,expires);
        return new Receipt(id,expires);
    }
    /** 코드의 소유·형식·실패 한도·만료만 확인한다. 토큰만으로 주소나 고객 권한을 변경하지 않는다. */
    public void verifyToken(long actor,UUID id,String token){
        crypto.requireConfigured();if(id==null||token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw denied(HttpStatus.BAD_REQUEST);
        var rows=jdbc.queryForList("SELECT * FROM customer_email_challenges WHERE id=? AND user_id=?",id,actor);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();
        // 인증 전용 실패 한도다. 비밀번호 실패 집계와 섞지 않으며 원본 토큰을 감사에 넣지 않는다.
        if(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_EMAIL_CHECK' AND detail='WRONG_TOKEN' AND occurred_at>clock_timestamp()-interval '15 minutes'",Long.class,actor)>=5)throw denied(HttpStatus.TOO_MANY_REQUESTS);
        if(!MessageDigest.isEqual(digest(id,token).getBytes(StandardCharsets.US_ASCII),((String)row.get("token_hash")).getBytes(StandardCharsets.US_ASCII))){audit.denied(actor,"CUSTOMER_EMAIL_CHECK","email-check:"+id,"WRONG_TOKEN");throw denied(HttpStatus.FORBIDDEN);}

        if(!crypto.keyId().equals(row.get("key_id")))throw denied(HttpStatus.CONFLICT);
        boolean confirmed=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_email_confirmations WHERE challenge_id=?)",Boolean.class,id);
        if(!confirmed&&!((Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now()))throw denied(HttpStatus.GONE);
    }
    public void confirmCurrent(User user,UUID sid,UUID id,String token){
        long actor=user.getId();verifyToken(actor,id,token);
        var row=jdbc.queryForMap("SELECT email_mac,key_id FROM customer_email_challenges WHERE id=? AND user_id=?",id,actor);
        if(!recipients.addressMac(user.getEmail()).equals(row.get("email_mac")))throw denied(HttpStatus.CONFLICT);
        // 소비된 토큰은 새 세션의 최근 확인으로 다시 결합하지 않는다.
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_email_confirmations WHERE challenge_id=?)",Boolean.class,id))return;
        jdbc.update("INSERT INTO customer_email_confirmations(challenge_id,user_id,email_mac,key_id) VALUES(?,?,?,?)",id,actor,row.get("email_mac"),crypto.keyId());
        jdbc.update("INSERT INTO customer_email_confirmation_sessions(challenge_id,session_id) VALUES(?,?)",id,sid);
        audit.record(actor,"CUSTOMER_EMAIL_VERIFIED","email-check:"+id,"SUCCESS","CURRENT_ADDRESS_ONLY");
    }
    public static void validateAddress(String value){
        try{if(value==null||value.length()>254)throw new IllegalArgumentException();var a=new InternetAddress(value,true);a.validate();if(!a.getAddress().equals(value)||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException();}
        catch(Exception e){throw denied(HttpStatus.BAD_REQUEST);}
    }
    private String addressMac(String email){return recipients.addressMac(email);}
    private Receipt receipt(Map<String,Object> row){return new Receipt((UUID)row.get("id"),((Timestamp)row.get("expires_at")).toInstant());}
    private String digest(UUID id,String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((id+":"+token).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("토큰 해시 알고리즘이 없습니다");}}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 고객 세션·주소·확인 기한을 확인하세요");}
}
