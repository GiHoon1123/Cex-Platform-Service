package dustin.cex.domains.security;

import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 고객 확인과 신규 주문 제한은 플랫폼의 예약 트랜잭션에서 결정한다. 엔진은 이 정책을 모른다. */
@Service
public class TradingAccessPolicy {
    private final UserRepository users;private final AuditService audit;private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public TradingAccessPolicy(UserRepository users,AuditService audit,org.springframework.jdbc.core.JdbcTemplate jdbc) {this.users=users;this.audit=audit;this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireNewOrder(long userId) {
        var user=users.lockById(userId).orElse(null);
        if(user==null || !user.isEnabled() || user.getRole()!=UserRole.CUSTOMER || user.isTradingRestricted()
                || !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM kyc_profiles p JOIN kyc_attempts a ON a.id=p.active_attempt
                     WHERE p.user_id=? AND p.identity_status='VERIFIED' AND p.valid_until>clock_timestamp() AND a.environment='live'
                      AND EXISTS(SELECT 1 FROM kyc_reconciliations r WHERE r.attempt_id=a.id
                       AND r.fresh_at>clock_timestamp()-interval '15 minutes'))
                    """,Boolean.class,userId))) {
            audit.denied(userId,"NEW_ORDER","user:"+userId,"TRADING_NOT_PERMITTED");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"신규 주문이 제한되어 있습니다");
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void recordCancellationIntent(long userId,long orderId) {
        audit.record(userId,"ORDER_CANCEL_REQUESTED","order:"+orderId,"SUCCESS","INTENT_ONLY_ENGINE_RESULT_FOLLOWS");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void recordReservation(long userId,long orderId) {
        audit.record(userId,"ORDER_RESERVED","order:"+orderId,"SUCCESS","RESERVATION_COMMITTED");
    }
}
