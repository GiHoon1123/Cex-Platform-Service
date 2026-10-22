package dustin.cex.domains.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.RefreshTokenRepository;

/** 작은 정수 캐시가 객체 참조 비교 오류를 가리지 않도록 큰 사용자 ID를 별도 객체로 검사한다. */
class OperatorSessionPolicyTest {
    record Fixture(User user,RefreshToken session,OperatorSessionPolicy policy) {}
    Fixture fixture(long id) {
        var user=User.builder().id(id).role(UserRole.OPERATOR).operatorPersonId(UUID.randomUUID())
            .operatorAccessExpiresAt(Instant.now().plusSeconds(3600)).build();
        var session=RefreshToken.builder().userId(id).tokenHash("테스트 전용 미사용 해시")
            .expiresAt(LocalDateTime.now().plusHours(1)).mfaVerifiedAt(LocalDateTime.now()).mfaCredentialFingerprint("a".repeat(64)).build();
        var repository=mock(RefreshTokenRepository.class);when(repository.findBySessionId(session.getSessionId())).thenReturn(Optional.of(session));
        return new Fixture(user,session,new OperatorSessionPolicy(repository));
    }
    @Test void equalLargeUserIdsAreAcceptedAcrossSeparateBoxedInstances() {
        for(long id:new long[]{128,1000,Long.MAX_VALUE}) {
            var f=fixture(id);assertNotSame(f.user().getId(),f.session().getUserId());
            assertDoesNotThrow(()->f.policy().requireFresh(f.user(),f.session().getSessionId(),UserRole.OPERATOR));
        }
    }
    @Test void differentUserIdNeverReceivesOwnersSession() {
        var f=fixture(1000);f.session().setUserId(1001L);
        assertThrows(ResponseStatusException.class,()->f.policy().requireFresh(f.user(),f.session().getSessionId(),UserRole.OPERATOR));
    }
    @Test void revokedStaleOrUnboundSessionsCannotAuthenticate() {
        var revoked=fixture(1000);revoked.session().setRevoked(true);
        assertThrows(ResponseStatusException.class,()->revoked.policy().requireFresh(revoked.user(),revoked.session().getSessionId(),UserRole.OPERATOR));
        var stale=fixture(1000);stale.session().setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));
        assertThrows(ResponseStatusException.class,()->stale.policy().requireFresh(stale.user(),stale.session().getSessionId(),UserRole.OPERATOR));
        var unbound=fixture(1000);unbound.session().setMfaCredentialFingerprint(null);
        assertThrows(ResponseStatusException.class,()->unbound.policy().requireFresh(unbound.user(),unbound.session().getSessionId(),UserRole.OPERATOR));
    }
}
