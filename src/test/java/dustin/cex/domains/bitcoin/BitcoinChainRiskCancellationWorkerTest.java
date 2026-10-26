package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** 실제 전용 스케줄러를 열고 닫아 중복 시작·장애 지연·종료 뒤 다음 요청 금지를 검증한다. */
class BitcoinChainRiskCancellationWorkerTest {
    @Test void disabledWorkerDoesNotContactTheDispatchService(){var service=mock(BitcoinChainRiskOrderCancellations.class);var worker=new BitcoinChainRiskCancellationWorker(service,false,Duration.ofMillis(5),50,50);try{worker.start();verifyNoInteractions(service);}finally{worker.stop();}}
    @Test void oneLongLivedWorkerDrainsReadyRequestsAndDoesNotStartTwice()throws Exception{
        var service=mock(BitcoinChainRiskOrderCancellations.class);var drained=new CountDownLatch(3);var threads=ConcurrentHashMap.<String>newKeySet();var identities=ConcurrentHashMap.<Long>newKeySet();var calls=new AtomicInteger();
        when(service.stageNext()).thenAnswer(call->{threads.add(Thread.currentThread().getName());identities.add(Thread.currentThread().threadId());drained.countDown();return calls.incrementAndGet()<3?java.util.Optional.of(new BitcoinChainRiskOrderCancellations.Plan(1,2,java.util.UUID.randomUUID(),4)):java.util.Optional.empty();});
        var worker=new BitcoinChainRiskCancellationWorker(service,true,Duration.ofMillis(5),50,50);
        try{worker.start();worker.start();assertTrue(drained.await(3,TimeUnit.SECONDS));}finally{worker.stop();}
        assertEquals(1,identities.size());assertEquals(1,threads.size());assertTrue(threads.iterator().next().startsWith("bitcoin-risk-cancel-"));
    }
    @Test void storageFailureBacksOffBeforeTheNextOwnedAttempt()throws Exception{
        var service=mock(BitcoinChainRiskOrderCancellations.class);var retried=new CountDownLatch(1);var first=new AtomicLong();var second=new AtomicLong();
        when(service.stageNext()).thenAnswer(call->{long now=System.nanoTime();if(first.compareAndSet(0,now))throw new IllegalStateException("격리 저장 장애");second.compareAndSet(0,now);retried.countDown();return java.util.Optional.empty();});
        var worker=new BitcoinChainRiskCancellationWorker(service,true,Duration.ofMillis(5),80,80);
        try{worker.start();assertTrue(retried.await(3,TimeUnit.SECONDS));}finally{worker.stop();}
        assertTrue(second.get()-first.get()>=Duration.ofMillis(70).toNanos());
    }
    @Test void shutdownDoesNotTakeAnotherRequestAfterTheInFlightCall()throws Exception{
        var service=mock(BitcoinChainRiskOrderCancellations.class);var entered=new CountDownLatch(1);var released=new CountDownLatch(1);var interrupted=new AtomicBoolean();
        when(service.stageNext()).thenAnswer(call->{entered.countDown();try{assertTrue(released.await(3,TimeUnit.SECONDS));}catch(InterruptedException stopping){interrupted.set(true);Thread.currentThread().interrupt();}return java.util.Optional.of(new BitcoinChainRiskOrderCancellations.Plan(1,2,java.util.UUID.randomUUID(),4));});
        var worker=new BitcoinChainRiskCancellationWorker(service,true,Duration.ofMillis(5),50,50);
        try{worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));worker.stop();}finally{released.countDown();worker.stop();}
        assertTrue(interrupted.get());verify(service,times(1)).stageNext();
    }
}
