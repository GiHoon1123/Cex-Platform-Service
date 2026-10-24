package dustin.cex.domains.mail;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** 명시적으로 켠 환경에서만 메일 전용 워커를 만든다. 기본 예약 작업의 스케줄러 선택에는 참여하지 않는다. */
@Component
@ConditionalOnProperty(name="customer-mail.dispatch-enabled",havingValue="true")
public class CustomerMailScheduling {
    private final CustomerMailDispatcher dispatcher;
    private final CustomerMailOutbox outbox;
    private final CustomerSmtpTransport transport;
    private ThreadPoolTaskScheduler scheduler;
    public CustomerMailScheduling(CustomerMailDispatcher dispatcher,CustomerSmtpTransport transport,CustomerMailOutbox outbox){this.outbox=outbox;this.dispatcher=dispatcher;this.transport=transport;}

    @PostConstruct void start(){
        transport.requireReady();
        // TaskScheduler 빈으로 등록하면 Spring의 기본 스케줄러 자동 구성이 물러날 수 있다.
        // 기존 KYC 워커처럼 수명이 긴 전용 인스턴스를 소유해 일반 정산/시세 작업과 분리한다.
        scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("customer-mail-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(15);scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::tick,Duration.ofSeconds(1));
    }
    @PreDestroy void stop(){
        // 정상 종료에는 진행 중인 전송을 기다린다. 강제 종료/시간 초과 뒤 미확정 임대는 DB에서 복구한다.
        if(scheduler!=null)scheduler.shutdown();
    }
    public void tick(){try{dispatcher.dispatchOnce();outbox.pruneExpiredPayloads();}catch(RuntimeException e){System.getLogger(CustomerMailScheduling.class.getName()).log(System.Logger.Level.WARNING,"고객 메일 시도 실패: "+e.getClass().getSimpleName());}}
}
