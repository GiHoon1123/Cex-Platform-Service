package dustin.cex.domains.valuation;

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

/** BTC 원화 참고가격을 수집해 수취 시점에 사용할 이력을 남긴다. 가격 기록은 금융 승인이나 법적 면제 판정이 아니다. */
@Component
// 테스트 프로필에서는 환경 변수로 활성화를 요청해도 자동 워커를 만들지 않는다. 수명 시험은 직접 소유한 객체만 실행한다.
@Profile("!test")
@DependsOn("bitcoinKrwPriceSchemaInitializer")
public class BitcoinKrwPriceWorker {
    private static final Logger log=LoggerFactory.getLogger(BitcoinKrwPriceWorker.class);
    private final BitcoinKrwPriceService processing;
    private final boolean enabled;
    private final Duration interval;
    private ThreadPoolTaskScheduler scheduler;
    private volatile boolean active;
    private boolean failed;

    @Autowired
    public BitcoinKrwPriceWorker(BitcoinKrwPriceService processing,
            @Value("${valuation.upbit.enabled:false}") boolean enabled){
        this(processing,enabled,Duration.ofSeconds(30));
    }

    // 제품 주기는 30초로 고정한다. 짧은 주기로 실제 실행기 수명을 검증하기 위한 패키지 내부 시험 생성자다.
    BitcoinKrwPriceWorker(BitcoinKrwPriceService processing,boolean enabled,Duration interval){
        this.processing=processing;
        this.enabled=enabled;
        this.interval=interval;
    }

    @PostConstruct synchronized void start(){
        // 플랫폼 실행기의 생성/종료만 보호한다. 체결 코어와는 상태·실행기·잠금을 공유하지 않는다.
        if(scheduler!=null||!enabled)return;
        scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("btc-krw-reference-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(10);
        // 감사/DB 저장 실패도 다음 주기를 취소하지 않는다. 원본은 서비스가 이미 보존한 상태로 둔다.
        scheduler.setErrorHandler(error->markFailed());
        scheduler.initialize();
        active=true;
        // 지연은 앞선 실행 완료부터 센다. 느린 호출 중 같은 워커에 새 호출을 계속 쌓지 않는다.
        scheduler.scheduleWithFixedDelay(this::tick,interval);
    }

    private void tick(){
        if(!active)return;
        var result=processing.refresh();
        if(!active)return;
        if(result.failureCode()!=null){
            markFailed();
        }else if(failed){
            failed=false;
            log.info("BTC 원화 참고가격 요청 처리를 재개했습니다. 금융 승인은 별도로 확인합니다");
        }
    }

    private void markFailed(){
        // 공급사 주소·서명 자료·응답·원문 예외를 출력하지 않고 장애 상태 전환 때만 알린다.
        if(active&&!failed){
            failed=true;
            log.warn("BTC 원화 참고가격 처리 실패. 원본을 유지하고 다음 주기에 다시 확인합니다");
        }
    }

    @PreDestroy synchronized void stop(){
        active=false;
        if(scheduler==null)return;
        scheduler.shutdown();
        // 종료 제한을 무시한 작업이 남으면 새 실행기를 겹쳐 만들지 않는다. 남은 작업의 완료가 현재성을 되돌릴 수는 없다.
        if(scheduler.getScheduledThreadPoolExecutor().isTerminated())scheduler=null;
        else log.error("BTC 원화 참고가격 워커 종료 대기 초과. 기존 작업이 남아 새 워커 생성을 차단합니다");
    }
}
