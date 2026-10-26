package dustin.cex.domains.order.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.*;

/** 취소 재확인을 전용 장수 스레드에서 이어간다. 메모리 지연은 장애 부하만 줄이고 금융 진행은 DB에 남긴다. */
@Component
@Profile("!test")
public class OrderCancellationWorker {
    private static final Logger log=LoggerFactory.getLogger(OrderCancellationWorker.class);
    private final OrderCancellationService service;private final boolean enabled;private final Duration idle;
    private final ExponentialBackOff backoff;private BackOffExecution retries;private ThreadPoolTaskScheduler scheduler;
    private volatile boolean active;private boolean failed;private long failedAtNanos,delayNanos;
    @Autowired public OrderCancellationWorker(OrderCancellationService service,@Value("${order.dispatch.enabled:true}") boolean enabled){this(service,enabled,Duration.ofSeconds(1),5000,60000);}
    // 시험만 짧은 간격을 주입한다. 제품에 별도 주기/재시도 설정을 늘리지 않는다.
    OrderCancellationWorker(OrderCancellationService service,boolean enabled,Duration idle,long initialRetry,long maximumRetry){
        this.service=service;this.enabled=enabled;this.idle=idle;backoff=new ExponentialBackOff(initialRetry,2);backoff.setMaxInterval(maximumRetry);
    }
    @PostConstruct synchronized void start(){
        if(scheduler!=null||!enabled)return;
        scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("order-cancel-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);scheduler.setAwaitTerminationSeconds(10);scheduler.setErrorHandler(error->cooldown());scheduler.initialize();
        retries=backoff.start();active=true;scheduler.scheduleWithFixedDelay(this::tick,idle);
    }
    private void tick(){
        if(!active||(delayNanos>0&&System.nanoTime()-failedAtNanos<delayNanos))return;
        // 건별로 DB TX가 끝난 뒤 다음 작업을 선택한다. 종료 요청 뒤 새 취소를 더 가져오지 않는다.
        while(active&&service.retryNext()){}
        if(!active)return;
        retries=backoff.start();delayNanos=0;if(failed){failed=false;log.info("저장된 취소 요청의 재확인을 재개했습니다");}
    }
    private void cooldown(){
        if(!active)return;
        if(!failed){failed=true;log.warn("취소 재확인 실패. 원본/임대를 보존하고 재확인 간격을 늘립니다");}
        long delay=retries.nextBackOff();if(delay==BackOffExecution.STOP){active=false;return;}
        failedAtNanos=System.nanoTime();delayNanos=Duration.ofMillis(delay).toNanos();
    }
    @PreDestroy synchronized void stop(){
        active=false;if(scheduler==null)return;scheduler.shutdown();
        if(scheduler.getScheduledThreadPoolExecutor().isTerminated())scheduler=null;
        else log.error("취소 워커 종료 지연. 기존 실행이 남아 대체 실행기 생성을 차단합니다");
    }
}
