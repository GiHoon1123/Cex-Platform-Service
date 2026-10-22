package dustin.cex.domains.security;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.RefreshTokenRepository;

/**
 * 보안 변경 서비스는 사용자 행을 잠근 뒤 현재 세션을 다시 확인한다.
 * 필터를 지난 직후 회수·복구·권한 만료가 일어나도 업무 직전의 상태를 기준으로 판단한다.
 */
@Service
public class OperatorSessionPolicy {
    private final RefreshTokenRepository tokens;
    public OperatorSessionPolicy(RefreshTokenRepository tokens) {this.tokens=tokens;}
    public RefreshToken requireFresh(User user,UUID sid,UserRole... roles) {
        if(user==null || !user.isAuthenticationAllowed() || user.getRole()==UserRole.CUSTOMER
                || Arrays.stream(roles).noneMatch(r->r==user.getRole())) throw denied();
        var session=tokens.findBySessionId(sid).orElseThrow(OperatorSessionPolicy::denied);
        LocalDateTime now=LocalDateTime.now();
        if(session.getUserId()!=user.getId() || session.getRevoked() || !session.getExpiresAt().isAfter(now)
                || !session.hasVerifiedMfa() || !session.getMfaVerifiedAt().plusMinutes(5).isAfter(now)) throw denied();
        return session;
    }
    private static ResponseStatusException denied() {return new ResponseStatusException(HttpStatus.FORBIDDEN,"현재 운영 인증을 다시 확인하세요");}
}
