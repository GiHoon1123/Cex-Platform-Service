package dustin.ethconfirmer;

import org.springframework.scheduling.annotation.Scheduled;

/** 읽기 전용 체인 확인을 한 건씩 수행하고 불명 응답은 다음 주기에 다시 읽는다. */
public class EthereumConfirmationWorker {
    private final EthereumConfirmationService service;
    public EthereumConfirmationWorker(EthereumConfirmationService service) { this.service = service; }
    @Scheduled(fixedDelayString="${ethereum.confirmation.interval-ms:5000}")
    public void process() { try { service.processNext(); } catch (RuntimeException retryLater) { /* 불변 제출 원본을 다음 주기에 다시 관찰한다. */ } }
}
