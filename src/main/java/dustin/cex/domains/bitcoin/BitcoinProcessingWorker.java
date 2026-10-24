package dustin.cex.domains.bitcoin;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** 블록 조회와 입금 식별을 전용 장수 스레드에서 잇는다. 진행 상태는 워커 메모리가 아닌 불변 DB 원본에 있다. */
@Component
@DependsOn("bitcoinSchemaInitializer")
public class BitcoinProcessingWorker {
    private static final Logger log=LoggerFactory.getLogger(BitcoinProcessingWorker.class);
    private final BitcoinSettings settings;
    private final BitcoinScanService scans;
    private final BitcoinDepositReceiptService receipts;
    private final boolean enabled;
    private ThreadPoolTaskScheduler scheduler;
    private volatile boolean active;
    private boolean failed;
    public BitcoinProcessingWorker(BitcoinSettings settings,BitcoinScanService scans,BitcoinDepositReceiptService receipts,
            @Value("${bitcoin.processing.enabled:true}") boolean enabled){
        this.settings=settings;this.scans=scans;this.receipts=receipts;this.enabled=enabled;
    }
    @PostConstruct synchronized void start(){
        // 이 잠금은 플랫폼 워커의 생성/종료에만 사용한다. 거래 엔진 코어의 상태나 명령 처리를 잠그지 않는다.
        if(scheduler!=null||!enabled||!settings.configured())return;
        scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("bitcoin-processing-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(error->{
            // 노드 주소·인증·원문 예외를 기록하지 않는다. 계속 실패할 때 매초 같은 경고를 쌓지 않는다.
            if(active&&!failed){failed=true;log.warn("비트코인 입금 발견 중단. 원본을 보존하고 다음 주기에 재확인합니다");}
        });
        scheduler.initialize();active=true;
        // 스케줄러 빈으로 공개하지 않아 기존 주문/고객 확인 작업의 실행 스레드를 빼앗지 않는다.
        scheduler.scheduleWithFixedDelay(this::tick,Duration.ofSeconds(1));
    }
    private void tick(){
        // 먼저 남은 식별을 처리한다. 여기서 실패하면 추가 블록을 읽기 전에 원인을 해결해야 한다.
        receipts.projectNext();if(!active)return;scans.scanNext();
        if(failed){failed=false;log.info("비트코인 입금 발견 재확인 성공. 저장된 진행 위치에서 계속합니다");}
    }
    @PreDestroy synchronized void stop(){
        active=false;
        if(scheduler!=null){
            scheduler.shutdown();
            // 인터럽트를 무시하는 DB/외부 코드가 남았으면 같은 객체에서 새 실행기를 만들지 않는다.
            if(scheduler.getScheduledThreadPoolExecutor().isTerminated())scheduler=null;
            else log.error("비트코인 워커 종료 대기 초과. 기존 작업이 남아 새 워커 생성을 차단합니다");
        }
    }
}
