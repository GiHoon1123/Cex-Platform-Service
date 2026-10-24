package dustin.cex.domains.mail;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.CddCrypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 읽기 전용 수신 자격이다. 토큰 발급·메일 예약에 의존하지 않아 발송 경로와 순환 의존하지 않는다. */
@Component
public class CustomerMailRecipientPolicy {
    private final JdbcTemplate jdbc;
    private final CddCrypto crypto;
    public CustomerMailRecipientPolicy(JdbcTemplate jdbc,CddCrypto crypto){this.jdbc=jdbc;this.crypto=crypto;}
    public String addressMac(String email){return crypto.fingerprint("customer-email-exact-v1",email);}
    public boolean verified(User user){return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_email_confirmations WHERE user_id=? AND email_mac=? AND key_id=?)",Boolean.class,user.getId(),addressMac(user.getEmail()),crypto.keyId());}
    public boolean allowed(User user){
        // 설정 시 확인한 주소에만 동의가 적용된다. 나중에 주소를 바꿔 재검증해도 옛 동의를 이전하지 않는다.
        if(user==null||user.getRole()!=UserRole.CUSTOMER||!user.isAuthenticationAllowed())return false;
        var rows=jdbc.queryForList("SELECT enabled,email_mac,key_id FROM customer_service_mail_preferences WHERE user_id=? ORDER BY sequence DESC LIMIT 1",user.getId());
        if(rows.isEmpty())return false;var p=rows.getFirst();return Boolean.TRUE.equals(p.get("enabled"))&&verified(user)&&addressMac(user.getEmail()).equals(p.get("email_mac"))&&crypto.keyId().equals(p.get("key_id"));
    }
}
