package dustin.cex.domains.ethereum;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 체인 확인과 엔진 완료를 분리하고 한 주기에 한 원본만 전달한다. */
@Component
@ConditionalOnProperty(name="ethereum.completion.enabled",havingValue="true")
public class EthereumCompletionWorker {
    private final EthereumCompletionDispatches dispatches;
    public EthereumCompletionWorker(EthereumCompletionDispatches dispatches){this.dispatches=dispatches;}
    @Scheduled(fixedDelayString="${ethereum.completion.interval-ms:5000}")
    public void process(){try{dispatches.processNext();}catch(RuntimeException retryLater){/* 영속 확정 원본을 다음 주기에 다시 확인한다. */}}
}
