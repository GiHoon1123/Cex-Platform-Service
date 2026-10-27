package dustin.cex.domains.ethereum;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;

/** 관찰과 금융 전달을 짧은 반복 단위로 나눠 한 장애가 무한 반복으로 다른 업무를 막지 않게 한다. */
@Component @ConditionalOnProperty(name="ethereum.processing.enabled",havingValue="true")
public class EthereumDepositWorker {
    private final EthereumDepositService service;public EthereumDepositWorker(EthereumDepositService service){this.service=service;}
    @Scheduled(fixedDelayString="${ethereum.processing.interval-ms:5000}") public void process(){
        try{
            // 한 실행이 오래 점유하지 않도록 확정 블록을 최대 스무 개만 전진시킨 뒤 금융 전달 기회를 준다.
            for(int i=0;i<20&&service.scanNext();i++){
                // 실제 전진은 scanNext 안에서 영속 위치와 함께 원자적으로 확정된다.
            }
            service.dispatchNext();
        }catch(RuntimeException unavailable){
            // 다음 주기에 같은 영속 위치와 원본으로 다시 확인한다.
        }
    }
}
