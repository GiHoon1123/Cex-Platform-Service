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
    public SessionService(UserRepository users,RefreshTokenRepository tokens) { this.users=users;this.tokens=tokens; }
    @Transactional(readOnly=true)
    public User authenticate(long userId,UUID sessionId) {
        var user=users.findById(userId).orElseThrow(()->new IllegalArgumentException("유효하지 않은 인증"));
        var session=tokens.findBySessionId(sessionId).orElseThrow(()->new IllegalArgumentException("유효하지 않은 인증"));
        if(!user.isEnabled() || session.getUserId()!=userId || session.getRevoked()
                || !session.getExpiresAt().isAfter(LocalDateTime.now())) throw new IllegalArgumentException("유효하지 않은 인증");
        return user;
    }
}
