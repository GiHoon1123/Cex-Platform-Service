package dustin.cex.domains.travelrule;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 실제 장수 실행기의 비중첩·실패 후 진행·종료와 Spring 자동 시작 경계를 검증한다. 공급사 네트워크는 사용하지 않는다. */
class CodeVaspDepositSearchWorkerTest {
    CodeVaspSettings configured(){return new CodeVaspSettings("http://127.0.0.1:1","http://127.0.0.1:2","worker",Base64.getEncoder().encodeToString(new byte[32]));}
    java.util.Optional<CodeVaspDepositSearchExecution.State> report(String status){return java.util.Optional.of(new CodeVaspDepositSearchExecution.State(UUID.randomUUID(),1L,"POLL",status.equals("SUCCESS")?"PROCESSING":"POLL_FAILED",status.equals("SUCCESS")?null:"PROVIDER_UNAVAILABLE",java.time.Instant.now(),java.time.Instant.now(),null));}
    CodeVaspDepositSearchWorker fast(CodeVaspDepositSearchProcessing service){return new CodeVaspDepositSearchWorker(configured(),service,true,Duration.ofMillis(30));}

    @Test void disabledAndUnconfiguredCannotCreateAutomaticAttempts(){
        var service=mock(CodeVaspDepositSearchProcessing.class);
        for(var worker:List.of(new CodeVaspDepositSearchWorker(configured(),service,false),new CodeVaspDepositSearchWorker(new CodeVaspSettings("","","",""),service,true))){worker.start();worker.stop();}
        verifyNoInteractions(service);
    }
    @Test void oneDedicatedThreadDoesNotOverlapOrBlockOtherScheduler()throws Exception{
        var service=mock(CodeVaspDepositSearchProcessing.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var thread=new AtomicReference<String>();var calls=new AtomicInteger();
        when(service.processNext()).thenAnswer(i->{calls.incrementAndGet();thread.set(Thread.currentThread().getName());entered.countDown();try{release.await();}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();}return report("SUCCESS");});
        var worker=fast(service);var other=new ThreadPoolTaskScheduler();other.setPoolSize(1);other.initialize();var beats=new AtomicInteger();
        try{worker.start();worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));other.scheduleWithFixedDelay(beats::incrementAndGet,Duration.ofMillis(20));await().atMost(Duration.ofSeconds(3)).until(()->beats.get()>=5);assertEquals(1,calls.get());assertTrue(thread.get().startsWith("codevasp-deposit-search-"));}
        finally{worker.stop();release.countDown();other.shutdown();}
        assertTrue(interrupted.await(2,TimeUnit.SECONDS));assertEquals(1,calls.get());
    }
    @Test void fixedDelayIsMeasuredAfterCompletionRatherThanAccumulatingCatchupTasks()throws Exception{
        var service=mock(CodeVaspDepositSearchProcessing.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        when(service.processNext()).thenAnswer(i->{if(calls.incrementAndGet()==1){entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));}return report("SUCCESS");});
        var worker=new CodeVaspDepositSearchWorker(configured(),service,true,Duration.ofMillis(300));
        try{worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));Thread.sleep(450);assertEquals(1,calls.get());release.countDown();await().during(Duration.ofMillis(100)).atMost(Duration.ofMillis(220)).until(()->calls.get()==1);await().atMost(Duration.ofSeconds(2)).until(()->calls.get()>=2);}
        finally{release.countDown();worker.stop();}
    }
    @Test void storedFailureAndThrownPersistenceFailureDoNotCancelFutureAttempts(){
        var service=mock(CodeVaspDepositSearchProcessing.class);var calls=new AtomicInteger();
        when(service.processNext()).thenAnswer(i->{int n=calls.incrementAndGet();if(n==1)return report("FAILED");if(n==2)throw new IllegalStateException("격리 민감 원문");return report("SUCCESS");});
        var worker=fast(service);try{worker.start();await().atMost(Duration.ofSeconds(3)).until(()->calls.get()>=3);}finally{worker.stop();}
    }
    @Test void stoppedWorkerCanRestartAfterOldExecutorTerminates(){
        var service=mock(CodeVaspDepositSearchProcessing.class);var calls=new AtomicInteger();when(service.processNext()).thenAnswer(i->{calls.incrementAndGet();return report("SUCCESS");});var worker=fast(service);
        try{worker.start();await().atMost(Duration.ofSeconds(3)).until(()->calls.get()>=1);worker.stop();int before=calls.get();worker.start();await().atMost(Duration.ofSeconds(3)).until(()->calls.get()>before);}finally{worker.stop();}
    }
    @Test void taskIgnoringShutdownCannotStartOverlappingReplacement()throws Exception{
        var service=mock(CodeVaspDepositSearchProcessing.class);var calls=new AtomicInteger();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(service.processNext()).thenAnswer(i->{calls.incrementAndGet();entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}return report("SUCCESS");});var worker=fast(service);
        try{worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));worker.stop();worker.start();await().during(Duration.ofMillis(150)).atMost(Duration.ofSeconds(1)).until(()->calls.get()==1);}finally{release.countDown();worker.stop();}
    }
    AnnotationConfigApplicationContext context(String profile,boolean enabled,CodeVaspSettings settings,CodeVaspDepositSearchProcessing service){
        var ctx=new AnnotationConfigApplicationContext();ctx.getEnvironment().setActiveProfiles(profile);ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("격리 워커 설정",Map.of("codevasp.processing.enabled",Boolean.toString(enabled))));
        // 스키마 자체는 096의 실제 PG 시험 범위다. 여기서는 자동 수명만 확인하므로 이름 있는 격리 선행 빈을 사용한다.
        ctx.registerBean("codeVaspSchemaInitializer",Object.class,Object::new);ctx.registerBean(CodeVaspSettings.class,()->settings);ctx.registerBean(CodeVaspDepositSearchProcessing.class,()->service);ctx.register(CodeVaspDepositSearchWorker.class);ctx.refresh();return ctx;
    }
    @Test void testProfileCannotAutomaticallyStartEvenWhenConfiguredAndEnabled(){
        var service=mock(CodeVaspDepositSearchProcessing.class);try(var ctx=context("test",true,configured(),service)){assertTrue(ctx.getBeansOfType(CodeVaspDepositSearchWorker.class).isEmpty());verifyNoInteractions(service);}
    }
    @Test void productionContextHonorsFlagAndClosesItsAutomaticallyStartedExecutor(){
        var service=mock(CodeVaspDepositSearchProcessing.class);when(service.processNext()).thenReturn(report("SUCCESS"));
        try(var disabled=context("production",false,configured(),service)){assertEquals(1,disabled.getBeansOfType(CodeVaspDepositSearchWorker.class).size());verifyNoInteractions(service);}
        try(var enabled=context("production",true,configured(),service)){await().atMost(Duration.ofSeconds(3)).untilAsserted(()->verify(service,times(1)).processNext());}
        await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(1)).until(()->Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().startsWith("codevasp-deposit-search-")));
    }
}
