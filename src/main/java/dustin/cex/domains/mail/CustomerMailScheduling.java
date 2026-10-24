package dustin.cex.domains.mail;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 명시적으로 켠 환경에만 수명이 긴 전용 메일 스케줄러를 둔다. 금융 스케줄러/엔진을 SMTP로 기다리게 하지 않는다. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="customer-mail.dispatch-enabled",havingValue="true")
public class CustomerMailScheduling {
    private final CustomerMailDispatcher dispatcher;private final CustomerMailOutbox outbox;private final CustomerSmtpTransport transport;
    public CustomerMailScheduling(CustomerMailDispatcher dispatcher,CustomerSmtpTransport transport,CustomerMailOutbox outbox){this.outbox=outbox;this.dispatcher=dispatcher;this.transport=transport;}
    @PostConstruct void validate(){transport.requireReady();}
    @Bean(name="customerMailScheduler") ThreadPoolTaskScheduler scheduler(){var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("customer-mail-");scheduler.setWaitForTasksToCompleteOnShutdown(true);scheduler.setAwaitTerminationSeconds(15);return scheduler;}
    @Scheduled(fixedDelay=1000,scheduler="customerMailScheduler") public void tick(){try{dispatcher.dispatchOnce();outbox.pruneExpiredPayloads();}catch(RuntimeException e){System.getLogger(CustomerMailScheduling.class.getName()).log(System.Logger.Level.WARNING,"고객 메일 시도 실패: "+e.getClass().getSimpleName());}}
}
