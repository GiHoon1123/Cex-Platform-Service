package dustin.cex.domains.bitcoin;

import dustin.cex.domains.monitoring.DepositMonitoringService;
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

/** 수탁 원장 모니터링을 장수 전용 스레드에서 실행한다. 노드·공급사·엔진을 호출하거나 금융 잔액을 바꾸지 않는다. */
@Component
@Profile("!test")
@DependsOn("depositMonitoringSchemaInitializer")
public class BitcoinDepositMonitoringWorker {
    private static final Logger log=LoggerFactory.getLogger(BitcoinDepositMonitoringWorker.class);
    private final BitcoinSettings settings;
    private final DepositMonitoringService service;
    private final boolean enabled;
    private ThreadPoolTaskScheduler scheduler;
    private volatile boolean active;
    private boolean failed;
    public BitcoinDepositMonitoringWorker(BitcoinSettings settings,DepositMonitoringService service,@Value("${bitcoin.processing.enabled:true}") boolean enabled){this.settings=settings;this.service=service;this.enabled=enabled;}
    @PostConstruct synchronized void start(){
        // 기존 수탁 자동 처리 설정만 사용한다. 노드 건강 상태와 무관하게 DB의 확정 원본을 처리한다.
        if(scheduler!=null||!enabled||!settings.configured())return;
        scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("bitcoin-deposit-monitoring-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(error->{if(active&&!failed){failed=true;log.warn("입금 모니터링 중단. 원본과 작업 임대를 보존하고 재확인합니다");}});
        scheduler.initialize();active=true;scheduler.scheduleWithFixedDelay(this::tick,Duration.ofSeconds(1));
    }
    private void tick(){
        // 전용 스레드에서 실제 준비된 입력을 한 트랜잭션씩 처리한다. 건마다 쉬거나 전체를 한 TX로 묶지 않는다.
        // 작업이 없으면 빠져나가 기존 고정 지연을 적용한다. 종료 요청은 현재 처리 뒤 다음 입력을 가져오기 전에 확인한다.
        while(active){
            var completed=service.processNext();
            if(completed.isEmpty())return;
            // 실패한 작업의 임대 만료를 기다리는 빈 폴링만으로 업무 복구를 선언하지 않는다.
            if(failed){failed=false;log.info("입금 모니터링 처리 재개");}
        }
    }
    @PreDestroy synchronized void stop(){active=false;if(scheduler!=null){scheduler.shutdown();if(scheduler.getScheduledThreadPoolExecutor().isTerminated())scheduler=null;else log.error("입금 모니터링 종료 지연. 기존 작업이 남아 대체 실행기 생성을 차단합니다");}}
}
