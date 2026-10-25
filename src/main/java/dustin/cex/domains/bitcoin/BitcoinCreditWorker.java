package dustin.cex.domains.bitcoin;

import dustin.cex.shared.http.EngineDepositClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.*;

/** 준비 입금을 전용 장수 스레드에서 처리한다. 금융 진행은 DB에 보존하고 메모리 지연은 장애 부하만 제한한다. */
@Component
@Profile("!test")
@DependsOn("bitcoinCreditSchemaInitializer")
public class BitcoinCreditWorker {
    private static final Logger log=LoggerFactory.getLogger(BitcoinCreditWorker.class);
    private final BitcoinSettings settings;private final BitcoinCreditProcessing processing;private final boolean enabled;private final Duration idle;
    private final ExponentialBackOff backoff;private BackOffExecution retries;
    private ThreadPoolTaskScheduler scheduler;private volatile boolean active;private boolean failed;
    private long failedAtNanos,delayNanos;
    @Autowired public BitcoinCreditWorker(BitcoinSettings settings,BitcoinCreditProcessing processing,@Value("${bitcoin.processing.enabled:true}") boolean enabled){this(settings,processing,enabled,Duration.ofSeconds(1),5000,60000);}
    // 제품 설정을 늘리지 않는다. 이 생성자는 실제 실행기의 수명/대기를 짧게 시험하기 위한 패키지 내부 경계다.
    BitcoinCreditWorker(BitcoinSettings settings,BitcoinCreditProcessing processing,boolean enabled,Duration idle,long initialRetry,long maximumRetry){
        this.settings=settings;this.processing=processing;this.enabled=enabled;this.idle=idle;
        backoff=new ExponentialBackOff(initialRetry,2);backoff.setMaxInterval(maximumRetry);
    }
    @PostConstruct synchronized void start(){
        if(scheduler!=null||!enabled||!settings.configured())return;
        scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("bitcoin-credit-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);scheduler.setAwaitTerminationSeconds(10);
        retries=backoff.start();delayNanos=0;scheduler.setErrorHandler(error->cooldown());scheduler.initialize();active=true;
        scheduler.scheduleWithFixedDelay(this::tick,idle);
    }
    private void tick(){
        // 단조 시각으로 대기하므로 벽시계 조정이 장애 요청을 한꺼번에 재개시키지 않는다.
        // 이 지연이 사라져도 영속 시도/작업의 DB 대기 조건은 그대로 유지된다.
        if(!active||(delayNanos>0&&System.nanoTime()-failedAtNanos<delayNanos))return;
        while(active){
            var result=processing.processNext();if(result.isEmpty())return;
            var transport=result.get().transportOutcome();
            if(transport==EngineDepositClient.Outcome.NOT_ACCEPTED||transport==EngineDepositClient.Outcome.OUTCOME_UNKNOWN){cooldown();return;}
            if(transport==EngineDepositClient.Outcome.DURABLY_CREDITED||transport==EngineDepositClient.Outcome.DURABLY_REJECTED){
                retries=backoff.start();delayNanos=0;if(failed){failed=false;log.info("입금 명령의 엔진 응답 확인을 재개했습니다. 플랫폼 금융 확정은 별도 결과를 따릅니다");}
            }
            // 준비 작업은 건마다 TX를 끝내고 이어 처리한다. 종료 요청 뒤에는 다음 입금을 가져오지 않는다.
        }
    }
    private void cooldown(){
        if(!active)return;
        if(!failed){failed=true;log.warn("입금 자동 처리 실패. 금융 원본과 임대를 보존하고 재확인 요청 간격을 늘립니다");}
        long delay=retries.nextBackOff();if(delay==BackOffExecution.STOP){active=false;log.error("입금 재확인 실행기의 대기 한도에 도달했습니다. 영속 작업을 보존하고 운영 확인을 기다립니다");return;}
        failedAtNanos=System.nanoTime();delayNanos=Duration.ofMillis(delay).toNanos();
    }
    @PreDestroy synchronized void stop(){
        active=false;if(scheduler==null)return;scheduler.shutdown();
        if(scheduler.getScheduledThreadPoolExecutor().isTerminated())scheduler=null;
        else log.error("입금 자동 처리 워커 종료 지연. 기존 실행이 남아 대체 실행기 생성을 차단합니다");
    }
}
