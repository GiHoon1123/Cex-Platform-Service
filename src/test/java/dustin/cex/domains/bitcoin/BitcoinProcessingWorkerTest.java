package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import static org.awaitility.Awaitility.await;

/** 실제 스케줄러로 재시도·직렬 실행·독립 실행·종료를 확인한다. 금융 원본은 별도 실제 노드/PG 시험에서 검증한다. */
class BitcoinProcessingWorkerTest {
    BitcoinSettings configured(){return new BitcoinSettings("http://127.0.0.1:1","worker","isolated-worker-password-at-least-32-bytes","watch","REGTEST");}
    @Test void disabledOrUnconfiguredNeverCreatesTask(){
        var scans=mock(BitcoinScanService.class);var receipts=mock(BitcoinDepositReceiptService.class);
        for(var worker:List.of(new BitcoinProcessingWorker(configured(),scans,receipts,false),
                new BitcoinProcessingWorker(new BitcoinSettings("","","","","REGTEST"),scans,receipts,true))){worker.start();worker.stop();}
        verifyNoInteractions(scans,receipts);
    }
    @Test void blockedProjectionUsesOneDedicatedThreadAndDoesNotOverlapOrBlockOtherScheduler()throws Exception{
        var scans=mock(BitcoinScanService.class);var receipts=mock(BitcoinDepositReceiptService.class);
        var entered=new CountDownLatch(1);var interrupted=new CountDownLatch(1);var name=new AtomicReference<String>();var calls=new AtomicInteger();
        when(receipts.projectNext()).thenAnswer(call->{calls.incrementAndGet();name.set(Thread.currentThread().getName());entered.countDown();
            try{new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();}return Optional.empty();});
        var worker=new BitcoinProcessingWorker(configured(),scans,receipts,true);var unrelated=new ThreadPoolTaskScheduler();
        unrelated.setPoolSize(1);unrelated.initialize();var heartbeats=new AtomicInteger();
        try{
            worker.start();worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));
            unrelated.scheduleWithFixedDelay(heartbeats::incrementAndGet,Duration.ofMillis(20));
            await().atMost(Duration.ofSeconds(3)).until(()->heartbeats.get()>=5);
            assertEquals(1,calls.get());assertTrue(name.get().startsWith("bitcoin-processing-"));verifyNoInteractions(scans);
        }finally{worker.stop();unrelated.shutdown();}
        assertTrue(interrupted.await(3,TimeUnit.SECONDS));assertEquals(1,calls.get());verifyNoInteractions(scans);
    }
    @Test void transientNodeFailureDoesNotCancelFutureTicks()throws Exception{
        var scans=mock(BitcoinScanService.class);var receipts=mock(BitcoinDepositReceiptService.class);var tries=new AtomicInteger();var recovered=new CountDownLatch(1);
        when(scans.scanNext()).thenAnswer(call->{if(tries.incrementAndGet()==1)throw BitcoinRpcClient.unavailable();recovered.countDown();return Optional.empty();});
        var worker=new BitcoinProcessingWorker(configured(),scans,receipts,true);
        try{worker.start();assertTrue(recovered.await(5,TimeUnit.SECONDS));assertTrue(tries.get()>=2);}finally{worker.stop();}
    }
    @Test void projectionFailurePreventsAdditionalScanUntilRetrySucceeds()throws Exception{
        var scans=mock(BitcoinScanService.class);var receipts=mock(BitcoinDepositReceiptService.class);var attempts=new AtomicInteger();var failed=new CountDownLatch(1);var scanned=new CountDownLatch(1);
        when(receipts.projectNext()).thenAnswer(call->{if(attempts.incrementAndGet()==1){failed.countDown();throw new IllegalStateException("격리 원본 장애");}return Optional.empty();});
        when(scans.scanNext()).thenAnswer(call->{scanned.countDown();return Optional.empty();});var worker=new BitcoinProcessingWorker(configured(),scans,receipts,true);
        try{worker.start();assertTrue(failed.await(3,TimeUnit.SECONDS));verifyNoInteractions(scans);assertTrue(scanned.await(5,TimeUnit.SECONDS));}finally{worker.stop();}
    }
    @Test void stoppedWorkerCanStartAgainWithoutParallelOldTask()throws Exception{
        var scans=mock(BitcoinScanService.class);var receipts=mock(BitcoinDepositReceiptService.class);var count=new AtomicInteger();
        when(scans.scanNext()).thenAnswer(call->{count.incrementAndGet();return Optional.empty();});var worker=new BitcoinProcessingWorker(configured(),scans,receipts,true);
        try{
            worker.start();await().atMost(Duration.ofSeconds(3)).until(()->count.get()>=1);worker.stop();int stopped=count.get();
            worker.start();await().atMost(Duration.ofSeconds(3)).until(()->count.get()>stopped);
        }finally{worker.stop();}
    }
    @Test void unresponsiveTaskPreventsSecondExecutorAfterShutdownTimeout()throws Exception{
        var scans=mock(BitcoinScanService.class);var receipts=mock(BitcoinDepositReceiptService.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        when(receipts.projectNext()).thenAnswer(call->{calls.incrementAndGet();entered.countDown();
            // 외부 코드가 인터럽트를 무시하는 장애를 재현한다. 시험은 반드시 해제해 스레드를 남기지 않는다.
            while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}
            return Optional.empty();});
        var worker=new BitcoinProcessingWorker(configured(),scans,receipts,true);
        try{
            worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));worker.stop();worker.start();
            await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until(()->calls.get()==1);verifyNoInteractions(scans);
        }finally{release.countDown();worker.stop();}
    }

}
