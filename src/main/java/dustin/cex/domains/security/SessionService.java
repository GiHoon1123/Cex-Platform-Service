package dustin.cex.domains.security;

import java.time.LocalDateTime;
import java.util.UUID;
import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 토큰에 박제된 역할 대신 현재 사용자와 회수 가능한 세션을 확인한다. */
@Service
public class SessionService {
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final CustomerMfaPolicy customerMfa;
    public SessionService(UserRepository users,RefreshTokenRepository tokens,CustomerMfaPolicy customerMfa) { this.users=users;this.tokens=tokens;this.customerMfa=customerMfa; }
    @Transactional(readOnly=true)
    public boolean hasCustomerMfa(long userId,UUID sessionId){
        return customerMfa.satisfied(userId,tokens.findBySessionId(sessionId).orElseThrow());
    }
    // 다음 메서드는 고객 세션에는 적용하지 않는다. 운영 역할과 인증 수준은 각각 현재 서버 상태로 확인한다.
    @Transactional(readOnly=true)
    public boolean hasOperatorMfa(UUID sessionId) {
        var session=tokens.findBySessionId(sessionId).orElseThrow();
        return session.hasVerifiedMfa() && session.getMfaVerifiedAt().plusHours(12).isAfter(LocalDateTime.now());
    }
    @Transactional(readOnly=true)
    public boolean hasFreshOperatorMfa(UUID sessionId) {
        var session=tokens.findBySessionId(sessionId).orElseThrow();
        return session.hasVerifiedMfa() && session.getMfaVerifiedAt().plusMinutes(5).isAfter(LocalDateTime.now());
    }
    @Transactional(readOnly=true)
    public User authenticate(long userId,UUID sessionId) {
        var user=users.findById(userId).orElseThrow(()->new IllegalArgumentException("유효하지 않은 인증"));
        var session=tokens.findBySessionId(sessionId).orElseThrow(()->new IllegalArgumentException("유효하지 않은 인증"));
        if(!user.isAuthenticationAllowed() || session.getUserId()!=userId || session.getRevoked()
                || !session.getExpiresAt().isAfter(LocalDateTime.now())) throw new IllegalArgumentException("유효하지 않은 인증");
        return user;
    }
}
