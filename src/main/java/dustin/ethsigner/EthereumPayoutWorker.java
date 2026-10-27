package dustin.ethsigner;

import org.springframework.scheduling.annotation.Scheduled;

/** 한 주기마다 지급 하나만 처리해 노드 장애가 다른 승인 업무를 오래 점유하지 않게 한다. */
public class EthereumPayoutWorker {
    private final EthereumPayoutService service;
    public EthereumPayoutWorker(EthereumPayoutService service) { this.service = service; }
    @Scheduled(fixedDelayString="${ethereum.signer.interval-ms:5000}")
    public void process() { try { service.processNext(); } catch (RuntimeException retryLater) { /* 영속 원본을 다음 주기에 다시 확인한다. */ } }
}
