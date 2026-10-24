package dustin.cex.domains.security;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** 고객 업무는 현재 계정을 잠근 뒤 실제 세션의 소유·회수·기한을 다시 확인한다. */
@Service
public class CustomerSessionPolicy {
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    public CustomerSessionPolicy(UserRepository users,RefreshTokenRepository tokens){this.users=users;this.tokens=tokens;}
    public User require(long actor,UUID sid){
        // 서비스 직접 호출도 같은 경계를 지킨다. 호출자가 트랜잭션을 열어 잠금의 수명을 결정한다.
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("고객 업무 트랜잭션이 필요합니다");
        var owner=users.lockById(actor).orElse(null);
        var token=sid==null?null:tokens.findBySessionId(sid).orElse(null);
        if(owner==null||owner.getRole()!=UserRole.CUSTOMER||!owner.isAuthenticationAllowed()||token==null||token.getUserId()!=actor||token.getRevoked()||!token.getExpiresAt().isAfter(LocalDateTime.now()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"현재 고객 계정과 본인 세션을 확인하세요");
        return owner;
    }
}
