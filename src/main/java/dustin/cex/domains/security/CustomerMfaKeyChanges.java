package dustin.cex.domains.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** 현재 공개키의 추가/폐기와 함께 불변 변경 사실을 남긴다. 실제 키/세션 업무가 트랜잭션을 소유한다. */
@Component
public class CustomerMfaKeyChanges {
    public enum Kind { ADDED, REVOKED }
    private final JdbcTemplate jdbc;
    private final CustomerMfaNotice notice;
    public CustomerMfaKeyChanges(JdbcTemplate jdbc,CustomerMfaNotice notice){this.jdbc=jdbc;this.notice=notice;}
    @Transactional(propagation=Propagation.MANDATORY)
    public UUID append(long actor,UUID sid,Kind kind,String fingerprint){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO customer_mfa_key_changes(id,user_id,session_id,kind,credential_fingerprint) VALUES(?,?,?,?,?)",id,actor,sid,kind.name(),fingerprint);
        notice.reserve(actor,id);
        return id;
    }
}
