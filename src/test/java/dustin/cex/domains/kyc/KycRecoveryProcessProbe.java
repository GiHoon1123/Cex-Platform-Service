package dustin.cex.domains.kyc;

import dustin.cex.CexApplication;
import dustin.cex.domains.auth.model.entity.RefreshToken;
import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.RefreshTokenRepository;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.TradingAccessPolicy;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 별도 JVM을 강제 종료·재기동하는 테스트 진입점이다. 제품 JAR·제품 설정에는 포함하지 않는다.
 * 제품 빈은 그대로 사용하고 공급사 HTTP 주소만 이 실행이 소유한 루프백 대역으로 제한한다.
 */
public class KycRecoveryProcessProbe {
    // 컴포넌트 탐색 대상이 아닌 직접 등록 설정이다. 일반 테스트·제품 실행에 자동 주입되지 않는다.
    static class Provider {
        @Bean
        @Primary
        DiditClient probeClient(DiditSettings settings, ObjectMapper json) {
            URI uri=URI.create(System.getenv("CEX_KYC_PROBE_PROVIDER"));
            if(!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()))
                throw new IllegalArgumentException("검증 소유 루프백 공급사가 필요합니다");
            return new DiditClient(settings,json,uri);
        }
    }

    public static void main(String[] args) {
        var application=new SpringApplication(CexApplication.class,Provider.class);
        application.setAdditionalProfiles("test");
        try(var context=application.run(args)) {
            var jdbc=context.getBean(JdbcTemplate.class);
            var service=context.getBean(KycService.class);
            var reconciliation=context.getBean(KycReconciliationService.class);
            if("prepare".equals(System.getenv("CEX_KYC_PROBE_PHASE"))) {
                var user=context.getBean(UserRepository.class).saveAndFlush(User.builder()
                    .email(UUID.randomUUID()+"@process.test.invalid").passwordHash("격리 검증 계정").build());
                var session=context.getBean(RefreshTokenRepository.class).saveAndFlush(RefreshToken.builder()
                    .userId(user.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
                var attempt=service.start(user.getId(),session.getSessionId(),UUID.randomUUID()).status().attemptId();
                jdbc.update("UPDATE kyc_reconciliations SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE attempt_id=?",attempt);
                reconciliation.reconcileDue();
                throw new AssertionError("첫 JVM은 공급사 조회 중 실행기에 의해 강제 종료돼야 합니다");
            }
            var row=jdbc.queryForMap("SELECT user_id,active_attempt FROM kyc_profiles");
            long user=((Number)row.get("user_id")).longValue();UUID attempt=(UUID)row.get("active_attempt");
            if(!reconciliation.reconcileDue()) throw new AssertionError("영속 임대 회수 실패");
            if(!"VERIFIED".equals(service.status(user).identityStatus())) throw new AssertionError("승인 복원 실패");
            var until=service.status(user).validUntil();
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status->context.getBean(TradingAccessPolicy.class).requireNewOrder(user));
            if(jdbc.queryForObject("SELECT count(*) FROM kyc_events",Long.class)!=1) throw new AssertionError("수신 효과 중복");
            jdbc.update("UPDATE kyc_reconciliations SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE attempt_id=?",attempt);
            if(!reconciliation.reconcileDue()) throw new AssertionError("재조회 실패");
            if(jdbc.queryForObject("SELECT count(*) FROM kyc_events",Long.class)!=1
                    || !until.equals(service.status(user).validUntil())) throw new AssertionError("재조회가 원래 판정을 변경했습니다");
            if(jdbc.queryForObject("SELECT count(*) FROM kyc_reconciliation_runs",Long.class)!=2) throw new AssertionError("대사 실행 이력 누락");
            System.out.println("KYC_PROCESS_RECOVERY_CONFIRMED");
        }
    }
}
