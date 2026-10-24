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
        UUID id=record(actor,sid,kind,fingerprint);
        notice.reserve(actor,id);
        return id;
    }
    /** 복구로 여러 키가 바뀌어도 고객 메일은 복구 업무 한 건으로 모은다. 개별 변경 원본은 모두 연결해 보존한다. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void appendForRecovery(long actor,UUID sid,Kind kind,String fingerprint,UUID recovery){
        UUID id=record(actor,sid,kind,fingerprint);
        // 복구 원본을 뒤에서 만들더라도 커밋 때 실제 같은 고객의 원본이 존재해야 한다.
        jdbc.update("INSERT INTO customer_recovery_key_changes(event_id,user_id,change_id) VALUES(?,?,?)",recovery,actor,id);
    }
    private UUID record(long actor,UUID sid,Kind kind,String fingerprint){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO customer_mfa_key_changes(id,user_id,session_id,kind,credential_fingerprint) VALUES(?,?,?,?,?)",id,actor,sid,kind.name(),fingerprint);
        return id;
    }

}
