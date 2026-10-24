package dustin.cex.domains.security;

import dustin.cex.domains.auth.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 고객 확인과 신규 주문 제한은 플랫폼의 예약 트랜잭션에서 결정한다. 엔진은 이 정책을 모른다. */
@Service
public class TradingAccessPolicy {
    private final UserRepository users;private final AuditService audit;private final CustomerFinancialEligibility eligibility;
    public TradingAccessPolicy(UserRepository users,AuditService audit,CustomerFinancialEligibility eligibility) {this.users=users;this.audit=audit;this.eligibility=eligibility;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireNewOrder(long userId) {
        var user=users.lockById(userId).orElse(null);
        if(!eligibility.permitted(user)) {
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
