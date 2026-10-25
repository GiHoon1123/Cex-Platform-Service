package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 보류 금융 실행기의 분리·재시도·종료를 실제 스케줄러로 확인한다. 금융 근거는 실제 노드/PG 통합 검증을 사용한다. */
class BitcoinPendingDepositWorkerTest {
    BitcoinSettings configured(){return new BitcoinSettings("http://127.0.0.1:1","worker","isolated-observation-password-at-least-32-bytes","watch","REGTEST");}
    @Test void disabledOrUnconfiguredNeverClaimsWork(){
        var jobs=mock(BitcoinPendingDepositService.class);
        for(var worker:List.of(new BitcoinPendingDepositWorker(configured(),jobs,false),
                new BitcoinPendingDepositWorker(new BitcoinSettings("","","","","REGTEST"),jobs,true))){worker.start();worker.stop();}
        verifyNoInteractions(jobs);
    }
    @Test void blockedObservationHasOneDedicatedTaskWhileOtherSchedulerProgresses()throws Exception{
        var jobs=mock(BitcoinPendingDepositService.class);var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var name=new AtomicReference<String>();var calls=new AtomicInteger();
        when(jobs.recognizeNext()).thenAnswer(call->{calls.incrementAndGet();name.set(Thread.currentThread().getName());entered.countDown();
            try{new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();}return Optional.empty();});
        var worker=new BitcoinPendingDepositWorker(configured(),jobs,true);var other=new ThreadPoolTaskScheduler();other.setPoolSize(1);other.initialize();var heartbeats=new AtomicInteger();
        try{
            worker.start();worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));other.scheduleWithFixedDelay(heartbeats::incrementAndGet,Duration.ofMillis(20));
            await().atMost(Duration.ofSeconds(3)).until(()->heartbeats.get()>=5);assertEquals(1,calls.get());assertTrue(name.get().startsWith("bitcoin-pending-ledger-"));
        }finally{worker.stop();other.shutdown();}
        assertTrue(interrupted.await(3,TimeUnit.SECONDS));assertEquals(1,calls.get());
    }
    @Test void unexpectedQueueFailureDoesNotCancelFutureScheduledAttempt()throws Exception{
        var jobs=mock(BitcoinPendingDepositService.class);var attempts=new AtomicInteger();var recovered=new CountDownLatch(1);
        when(jobs.recognizeNext()).thenAnswer(call->{if(attempts.incrementAndGet()==1)throw new IllegalStateException("격리 큐 장애");recovered.countDown();return Optional.empty();});
        var worker=new BitcoinPendingDepositWorker(configured(),jobs,true);
        try{worker.start();assertTrue(recovered.await(5,TimeUnit.SECONDS));assertTrue(attempts.get()>=2);}finally{worker.stop();}
    }
    @Test void terminatedWorkerCanRestartWithoutDuplicateOldTask(){
        var jobs=mock(BitcoinPendingDepositService.class);var attempts=new AtomicInteger();when(jobs.recognizeNext()).thenAnswer(call->{attempts.incrementAndGet();return Optional.empty();});
        var worker=new BitcoinPendingDepositWorker(configured(),jobs,true);
        try{worker.start();await().atMost(Duration.ofSeconds(3)).until(()->attempts.get()>=1);worker.stop();int previous=attempts.get();worker.start();await().atMost(Duration.ofSeconds(3)).until(()->attempts.get()>previous);}finally{worker.stop();}
    }
    @Test void taskIgnoringInterruptPreventsOverlappingReplacementAfterShutdownTimeout()throws Exception{
        var jobs=mock(BitcoinPendingDepositService.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var attempts=new AtomicInteger();
        when(jobs.recognizeNext()).thenAnswer(call->{attempts.incrementAndGet();entered.countDown();
            // 종료 신호를 무시하는 외부 장애를 재현하되 마지막에 반드시 해제한다.
            while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}return Optional.empty();});
        var worker=new BitcoinPendingDepositWorker(configured(),jobs,true);
        try{worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));worker.stop();worker.start();await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(()->attempts.get()==1);}
        finally{release.countDown();worker.stop();}
    }
}
