package dustin.cex.domains.kyc;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** 금융 회귀만을 위한 신원 확인 완료 fixture다. 공급사 검증 증거를 대신하지 않는다. */
public final class KycFixtures {
    private KycFixtures() {}
    public static void verified(JdbcTemplate jdbc,long user) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kyc_profiles WHERE user_id=?)",Boolean.class,user))) return;
        UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO kyc_attempts(id,user_id,request_id,application_id,environment,workflow_id,workflow_version,creation_state)
            VALUES(?,?,?,?,'live',?,1,'READY')
            """,id,user,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        jdbc.update("INSERT INTO kyc_profiles(user_id,active_attempt,identity_status,provider_status,valid_until) VALUES(?,?,'VERIFIED','Approved',clock_timestamp()+interval '1 day')",user,id);
    }
}
