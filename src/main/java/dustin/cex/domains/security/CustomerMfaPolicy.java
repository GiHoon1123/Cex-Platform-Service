package dustin.cex.domains.security;

import dustin.cex.domains.auth.model.entity.RefreshToken;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 고객의 다중 인증 필요 여부는 불변 등록 사실에서 읽고, 검증 수준은 현재 영속 세션에서 확인한다. */
@Component
public class CustomerMfaPolicy {
    private final JdbcTemplate jdbc;
    public CustomerMfaPolicy(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean required(long actor){
        // 공개키를 모두 잃어도 등록 사실을 삭제해 비밀번호 인증으로 돌아가지 않는다.
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_mfa_enrollments WHERE user_id=?)",Boolean.class,actor);
    }
    public boolean fresh(long actor,RefreshToken session){
        return session!=null&&session.hasVerifiedMfa()&&session.getMfaVerifiedAt().plusMinutes(5).isAfter(LocalDateTime.now())&&satisfied(actor,session);
    }
    // 이 메서드는 다중 인증 수준만 검사한다. 계정/세션의 소유·회수·기한은 호출자가 먼저 확인한다.
    public boolean satisfied(long actor,RefreshToken session){
        if(!required(actor))return true;
        if(session==null||session.getUserId()!=actor||!session.hasVerifiedMfa()
                ||!session.getMfaVerifiedAt().plusHours(12).isAfter(LocalDateTime.now()))return false;
        // 회수된 키에 결합된 인증 시각만 남아 있어도 현재 자격으로 인정하지 않는다.
        return credentialActive(actor,session.getMfaCredentialFingerprint());
    }
    // 장기 업무에 결합한 서명 자격도 아직 살아 있는지 확인한다. 호출 업무가 사용자 잠금을 유지한다.
    boolean credentialActive(long actor,String fingerprint){
        return jdbc.queryForList("SELECT c.credential_id FROM user_credentials c JOIN user_entities u ON u.id=c.user_entity_user_id WHERE u.name=?",String.class,Long.toString(actor))
            .stream().anyMatch(id->OperatorMfaService.sha256Hex(id).equals(fingerprint));
    }
}
