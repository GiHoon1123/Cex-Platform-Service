package dustin.cex.domains.bitcoin;

import dustin.cex.shared.http.EngineWithdrawalClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

/** 출금 예약 명령만 전용 장수 스레드에서 재확인한다. 금융 원본과 대기 기준은 DB에 남긴다. */
@Component
@Profile("!test")
@DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinWithdrawalWorker {
    private static final Logger log = LoggerFactory.getLogger(BitcoinWithdrawalWorker.class);
    private final BitcoinSettings settings;
    private final BitcoinWithdrawalDispatches dispatch;
    private final boolean enabled;
    private final ExponentialBackOff backoff = new ExponentialBackOff(5000, 2);
    private ThreadPoolTaskScheduler scheduler;
    private BackOffExecution retries;
    private volatile boolean active;
    private long failedAtNanos, delayNanos;

    public BitcoinWithdrawalWorker(BitcoinSettings settings, BitcoinWithdrawalDispatches dispatch,
            @Value("${bitcoin.processing.enabled:true}") boolean enabled) {
        this.settings = settings;
        this.dispatch = dispatch;
        this.enabled = enabled;
        backoff.setMaxInterval(60000);
    }

    @PostConstruct synchronized void start() {
        if (scheduler != null || !enabled || !settings.configured()) return;
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("bitcoin-withdrawal-reserve-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(error -> cooldown());
        scheduler.initialize();
        retries = backoff.start();
        active = true;
        scheduler.scheduleWithFixedDelay(this::tick, Duration.ofSeconds(1));
    }

    private void tick() {
        if (!active || (delayNanos > 0 && System.nanoTime() - failedAtNanos < delayNanos)) return;
        while (active) {
            var next = dispatch.processNext();
            if (next.isEmpty()) return;
            var outcome = next.get().transport();
            if (outcome == EngineWithdrawalClient.Outcome.NOT_ACCEPTED
                    || outcome == EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN) {
                cooldown();
                return;
            }
            retries = backoff.start();
            delayNanos = 0;
        }
    }

    private void cooldown() {
        if (!active) return;
        if (delayNanos == 0) log.warn("출금 예약 명령 재확인이 지연됩니다. 금융 원본과 잠긴 잔액은 보존합니다");
        long delay = retries.nextBackOff();
        if (delay == BackOffExecution.STOP) {
            active = false;
            log.error("출금 예약 명령 재확인 대기 한도에 도달했습니다. 영속 원본을 보존합니다");
            return;
        }
        failedAtNanos = System.nanoTime();
        delayNanos = Duration.ofMillis(delay).toNanos();
    }

    @PreDestroy synchronized void stop() {
        active = false;
        if (scheduler == null) return;
        scheduler.shutdown();
        if (!scheduler.getScheduledThreadPoolExecutor().isTerminated())
            log.error("출금 예약 명령 실행기의 종료가 지연됐습니다. 대체 실행기를 시작하지 않습니다");
    }
}
