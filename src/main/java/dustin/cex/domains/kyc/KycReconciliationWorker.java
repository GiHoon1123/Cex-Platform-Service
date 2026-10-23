package dustin.cex.domains.kyc;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 공급사 조회가 기본 스케줄러의 주문 재전송·시장 공개를 막지 않도록 전용 스레드 하나를 사용한다.
 * 실행 주기와 재시도 시각은 구분한다. 매초 한 건을 확인하지만 실제 재시도 기한은 DB에 남는다.
 */
@Component
@org.springframework.context.annotation.DependsOn("kycSchemaInitializer")
public class KycReconciliationWorker {
    private static final Logger log = LoggerFactory.getLogger(KycReconciliationWorker.class);
    private final DiditSettings settings;
    private final KycReconciliationService reconciliation;
    private final boolean enabled;
    private ThreadPoolTaskScheduler scheduler;

    public KycReconciliationWorker(DiditSettings settings, KycReconciliationService reconciliation,
            @Value("${kyc.reconciliation.enabled:true}") boolean enabled) {
        this.settings = settings;
        this.reconciliation = reconciliation;
        this.enabled = enabled;
    }

    @PostConstruct
    void start() {
        if (!enabled || !settings.configured()) return;
        // 스케줄러 빈으로 공개하지 않아 기존 작업의 기본 스케줄러 선택에도 영향을 주지 않는다.
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kyc-reconciliation-");
        scheduler.setErrorHandler(error -> log.error("고객 확인 대사 실행 실패. 저장된 임대 만료 후 재시도합니다"));
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(reconciliation::reconcileDue, Duration.ofSeconds(1));
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdown();
    }
}
