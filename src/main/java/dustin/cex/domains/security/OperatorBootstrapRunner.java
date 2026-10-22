package dustin.cex.domains.security;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.*;

/** 공개 역할 부여 API를 만들지 않고 운영 시스템 접근권한을 가진 관리자가 일회성으로 실행한다. */
@Component
@ConditionalOnProperty(name="operator.bootstrap.enabled",havingValue="true")
public class OperatorBootstrapRunner implements ApplicationRunner {
    private final TransactionTemplate tx;private final UserRepository users;private final RefreshTokenRepository tokens;
    private final AuditService audit;private final JdbcTemplate jdbc;private final ConfigurableApplicationContext context;
    private final long userId;private final UUID personId;private final UserRole role;
    public OperatorBootstrapRunner(PlatformTransactionManager manager,UserRepository users,RefreshTokenRepository tokens,
            AuditService audit,JdbcTemplate jdbc,ConfigurableApplicationContext context,
            @Value("${operator.bootstrap.user-id}") long userId,@Value("${operator.bootstrap.person-id}") UUID personId,
            @Value("${operator.bootstrap.role}") UserRole role) {
        this.tx=new TransactionTemplate(manager);this.users=users;this.tokens=tokens;this.audit=audit;
        this.jdbc=jdbc;this.context=context;this.userId=userId;this.personId=personId;this.role=role;
    }
    public void run(ApplicationArguments args) {
        if(role==UserRole.CUSTOMER) throw new IllegalArgumentException("운영 역할을 지정해야 합니다");
        tx.executeWithoutResult(status->{
            var user=users.lockById(userId).orElseThrow();
            if(user.getRole()!=UserRole.CUSTOMER || !user.isEnabled()
                    || jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id=?",Long.class,userId)!=0
                    || jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE owner_id=?",Long.class,userId)!=0
                    || jdbc.queryForObject("SELECT count(*) FROM user_balances WHERE user_id=? AND (available<>0 OR locked<>0)",Long.class,userId)!=0)
                throw new IllegalStateException("금융 이력이 없는 전용 운영 계정만 초기화할 수 있습니다");
            user.setRole(role);user.setOperatorPersonId(personId);users.saveAndFlush(user);
            tokens.revokeAllByUserId(userId,LocalDateTime.now());
            audit.record(null,"OPERATOR_BOOTSTRAP","user:"+userId,"SUCCESS",role+":"+personId);
        });
        // 이 프로세스만 종료한다. 상시 서비스에서 자동으로 권한을 부여하지 않는다.
        context.close();
    }
}
