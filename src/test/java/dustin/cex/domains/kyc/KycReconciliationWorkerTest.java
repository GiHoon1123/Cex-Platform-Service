package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 실제 전용 스케줄러의 수명과 비활성 조건을 검증한다. 공급사 HTTP는 호출하지 않는다. */
class KycReconciliationWorkerTest {
    @Test
    void dedicatedThreadRunsAndShutdownInterruptsBlockedWork() throws Exception {
        var service=mock(KycReconciliationService.class);
        var entered=new CountDownLatch(1);
        var stopped=new CountDownLatch(1);
        var thread=new AtomicReference<String>();
        when(service.reconcileDue()).thenAnswer(call->{
            thread.set(Thread.currentThread().getName());entered.countDown();
            try {new CountDownLatch(1).await();}
            catch(InterruptedException e) {Thread.currentThread().interrupt();stopped.countDown();}
            return false;
        });
        var worker=new KycReconciliationWorker(DiditWebhookVerifierTest.settings("live"),service,true);
        try {
            worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));
            assertTrue(thread.get().startsWith("kyc-reconciliation-"));
            worker.stop();assertTrue(stopped.await(3,TimeUnit.SECONDS));verify(service,times(1)).reconcileDue();
        } finally {worker.stop();}
    }

    @Test
    void disabledOrUnconfiguredWorkerNeverStartsProviderWork() {
        var service=mock(KycReconciliationService.class);
        var disabled=new KycReconciliationWorker(DiditWebhookVerifierTest.settings("live"),service,false);
        var empty=new KycReconciliationWorker(new DiditSettings("","","","",0,"","",""),service,true);
        disabled.start();empty.start();disabled.stop();empty.stop();verifyNoInteractions(service);
    }
}
