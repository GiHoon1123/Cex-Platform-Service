package dustin.cex.domains.security;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.*;

/** 전용 관리 프로세스에서만 호출하는 최초 부여 업무다. 일반 서버 시작에는 실행 훅이 없다. */
public class OperatorBootstrapService {
    private final TransactionTemplate tx;private final UserRepository users;private final RefreshTokenRepository tokens;
    private final AuditService audit;private final JdbcTemplate jdbc;
    public OperatorBootstrapService(PlatformTransactionManager manager,UserRepository users,RefreshTokenRepository tokens,
            AuditService audit,JdbcTemplate jdbc) {
        tx=new TransactionTemplate(manager);this.users=users;this.tokens=tokens;this.audit=audit;this.jdbc=jdbc;
    }
    public void grant(long userId,UUID personId,UserRole role,String enrollmentToken) {
        if(personId==null || role==null) throw new IllegalArgumentException("운영 인물과 역할이 필요합니다");
        if(enrollmentToken==null || enrollmentToken.length()<43 || enrollmentToken.length()>256) throw new IllegalArgumentException("충분히 무작위인 등록 비밀이 필요합니다");
        if(role==UserRole.CUSTOMER) throw new IllegalArgumentException("운영 역할을 지정해야 합니다");
        tx.executeWithoutResult(status->{
            var user=users.lockById(userId).orElseThrow();
            // 고객 이력의 존재만 읽는다. 관리 DB 역할에 신고 암호문·확인 링크 조회 권한은 주지 않는다.
            if(user.getRole()!=UserRole.CUSTOMER || !user.isEnabled()
                    || jdbc.queryForObject("SELECT count(user_id) FROM cdd_declarations WHERE user_id=?",Long.class,userId)!=0
                    || jdbc.queryForObject("SELECT count(user_id) FROM kyc_attempts WHERE user_id=?",Long.class,userId)!=0
                    || jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id=?",Long.class,userId)!=0
                    || jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE owner_id=?",Long.class,userId)!=0
                    || jdbc.queryForObject("SELECT count(*) FROM user_balances WHERE user_id=? AND (available<>0 OR locked<>0)",Long.class,userId)!=0)
                throw new IllegalStateException("고객 확인·금융 이력이 없는 전용 운영 계정만 초기화할 수 있습니다");
            user.setRole(role);user.setOperatorPersonId(personId);
            user.setOperatorAccessExpiresAt(java.time.Instant.now().plusSeconds(30*86400));
            user.setOperatorLastReviewedAt(java.time.Instant.now());users.saveAndFlush(user);
            tokens.revokeAllByUserId(userId,LocalDateTime.now());
            // 등록 권한은 사용자·10분 만료에 묶고 원본 비밀은 저장하지 않는다.
            jdbc.update("INSERT INTO operator_enrollment(user_id,token_hash,expires_at) VALUES(?,?,clock_timestamp()+interval '10 minutes')",
                userId,OperatorMfaService.sha256Hex(enrollmentToken));
            audit.record(null,"OPERATOR_BOOTSTRAP","user:"+userId,"SUCCESS",role+":"+personId+":until="+user.getOperatorAccessExpiresAt());
        });
    }
}
