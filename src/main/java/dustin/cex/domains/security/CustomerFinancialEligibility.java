package dustin.cex.domains.security;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.CddService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 신규 금융 권한이 현재 고객 확인과 입금 실행 후 체인 위험을 함께 확인한다. 고객 잠금은 호출자가 소유한다. */
@Service
@org.springframework.context.annotation.DependsOn("bitcoinCreditSchemaInitializer")
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
                """,Boolean.class,id))
            // 확정 수 감소/충돌/출력 불일치는 이미 실행 권한을 준 입금의 위험이다.
            // 과거 금융 결과는 취소하지 않고 신규 금융 권한만 막는다. 단순 관찰 지연은 이 근거에 포함하지 않는다.
            &&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_credit_chain_risks WHERE user_id=?)",Boolean.class,id));
    }
}
