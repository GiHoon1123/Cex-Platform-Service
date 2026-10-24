package dustin.cex.domains.security;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.mail.*;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** 실제 불변 키 변경에 보안 안내를 예약한다. 키 지문/이름/인증 자료는 외부 메일로 복사하지 않는다. */
@Component
public class CustomerMfaNotice {
    private final JdbcTemplate jdbc;private final UserRepository users;private final CustomerMailRecipientPolicy recipients;private final CustomerMailOutbox outbox;
    public CustomerMfaNotice(JdbcTemplate jdbc,UserRepository users,CustomerMailRecipientPolicy recipients,CustomerMailOutbox outbox){this.jdbc=jdbc;this.users=users;this.recipients=recipients;this.outbox=outbox;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void reserve(long actor,UUID id){
        var row=jdbc.queryForMap("SELECT user_id,created_at FROM customer_mfa_key_changes WHERE id=?",id);
        if(((Number)row.get("user_id")).longValue()!=actor)throw new IllegalStateException("키 변경 통지의 소유자가 다릅니다");
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_email_confirmations WHERE user_id=?)",Boolean.class,actor))return;
        var user=users.findById(actor).orElseThrow();if(!recipients.verified(user))return;
        // 원본 확정 시각을 기준으로 만료한다. 수신 선택과 구분하되 확인 주소의 키 오류는 삼키지 않는다.
        outbox.enqueue(actor,CustomerMailOutbox.Kind.MFA_CHANGED,id,user.getEmail(),null,((Timestamp)row.get("created_at")).toInstant().plusSeconds(86400));
    }
}
