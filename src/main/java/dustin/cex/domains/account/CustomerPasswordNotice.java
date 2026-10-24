package dustin.cex.domains.account;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.mail.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 실제 비밀번호 변경에만 보안 통지를 예약한다. 일반 답변 알림의 수신 선택과 구분한다. */
@Service
public class CustomerPasswordNotice {
    private final JdbcTemplate jdbc;private final UserRepository users;private final CustomerMailRecipientPolicy recipients;private final CustomerMailOutbox outbox;
    public CustomerPasswordNotice(JdbcTemplate jdbc,UserRepository users,CustomerMailRecipientPolicy recipients,CustomerMailOutbox outbox){this.jdbc=jdbc;this.users=users;this.recipients=recipients;this.outbox=outbox;}
    public void reserve(long actor,UUID id,Instant at){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("비밀번호 변경 트랜잭션이 필요합니다");
        var row=jdbc.queryForMap("SELECT user_id,changed_at FROM customer_password_changes WHERE id=?",id);
        if(((Number)row.get("user_id")).longValue()!=actor||!((Timestamp)row.get("changed_at")).toInstant().equals(at))throw new IllegalStateException("보안 통지 근거가 일치하지 않습니다");
        // 확인 사실 자체가 없으면 외부 메일을 만들지 않는다. 확인된 주소의 키/무결성 오류는 삼키지 않는다.
        if(!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_email_confirmations WHERE user_id=?)",Boolean.class,actor))return;
        var user=users.findById(actor).orElseThrow();if(!recipients.verified(user))return;
        // 원문 비밀번호/해시/기기 정보는 전달하지 않고24시간짜리 고정 보안 안내만 예약한다.
        outbox.enqueue(actor,CustomerMailOutbox.Kind.PASSWORD_CHANGED,id,user.getEmail(),null,at.plusSeconds(86400));
    }
}
