package dustin.cex.domains.security;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.CddService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 신규 주문과 입금 주소 공개가 같은 현재 고객 확인 사실을 사용한다. 사용자 잠금은 호출자가 소유한다. */
@Service
public class CustomerFinancialEligibility {
    private final CddService cdd;
    private final JdbcTemplate jdbc;
    public CustomerFinancialEligibility(CddService cdd,JdbcTemplate jdbc){this.cdd=cdd;this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean permitted(User user){
        if(user==null||!user.isEnabled()||user.getRole()!=UserRole.CUSTOMER||user.isTradingRestricted())return false;
        long id=user.getId();
        // 공급사 확인 시각이 미래이면 신선도가 무한히 연장된 것처럼 취급하지 않는다. DB 시각을 기준으로 양 끝을 검사한다.
        // 종료 개시 결정은 일반 제한 해제와 독립된 차단 근거다. 외부 공급사의 최신 확인도 필요하다.
        return !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM relationship_active_exits WHERE user_id=?)",Boolean.class,id))
            &&cdd.permitted(id)&&Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM kyc_profiles p JOIN kyc_attempts a ON a.id=p.active_attempt
                 WHERE p.user_id=? AND p.identity_status='VERIFIED' AND p.valid_until>clock_timestamp() AND a.environment='live'
                  AND EXISTS(SELECT 1 FROM kyc_reconciliations r WHERE r.attempt_id=a.id
                   AND r.fresh_at>clock_timestamp()-interval '15 minutes' AND r.fresh_at<=clock_timestamp()))
                """,Boolean.class,id));
    }
}
