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
        jdbc.update("INSERT INTO kyc_reconciliations(attempt_id,fresh_at) VALUES(?,clock_timestamp())",id);
        jdbc.update("INSERT INTO kyc_profiles(user_id,active_attempt,identity_status,provider_status,valid_until,status_digest) VALUES(?,?,'VERIFIED','Approved',clock_timestamp()+interval '1 day','financial-fixture')",user,id);
        trustedCdd(jdbc,user,id);
    }

    /** 금융 처리만 검증하는 테스트의 명시적인 사전 조건이다. 제품에는 이 우회가 포함되지 않는다. */
    private static void trustedCdd(JdbcTemplate jdbc,long user,UUID attempt) {
        UUID declaration=UUID.randomUUID(),event=UUID.randomUUID(),assessment=UUID.randomUUID();
        String key="ff55c97976a840b4ced964ed49e3794594ba3f675238b5fd25d282b60f70a194";
        jdbc.update("INSERT INTO cdd_declarations(id,user_id,request_id,ciphertext,content_mac,key_id) VALUES(?,?,?,?,?,?)",declaration,user,UUID.randomUUID(),new byte[]{0},"financial-fixture",key);
        jdbc.update("INSERT INTO cdd_attempt_bindings(attempt_id,declaration_id) VALUES(?,?)",attempt,declaration);
        jdbc.update("INSERT INTO kyc_events(event_id,attempt_id,payload_hash,provider_time,provider_status,disposition) VALUES(?,?,'financial-fixture',0,'Approved','APPLIED')",event,attempt);
        jdbc.update("INSERT INTO cdd_assessments(id,user_id,declaration_id,attempt_id,event_id,status_digest,key_id,policy_version,outcome,reasons) VALUES(?,?,?,?,?,'financial-fixture',?,?,'STANDARD','FINANCIAL_FIXTURE')",assessment,user,declaration,attempt,event,key,dustin.cex.domains.cdd.CddService.POLICY);
        jdbc.update("INSERT INTO cdd_profiles(user_id,declaration_id,assessment_id) VALUES(?,?,?)",user,declaration,assessment);
    }
}
