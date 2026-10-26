package dustin.confirmer;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;

/** 확인 전용 단일 소비자다. 일시 미확정은 조용히 보존하고 충돌만 운영 경보로 남긴다. */
public final class BitcoinConfirmerWorker {
    private static final Logger log=LoggerFactory.getLogger(BitcoinConfirmerWorker.class);private final BitcoinConfirmationService service;private boolean failed;
    public BitcoinConfirmerWorker(BitcoinConfirmationService service){this.service=service;}
    @Scheduled(fixedDelayString="${bitcoin.confirmation.poll-millis:10000}") public void tick(){try{var result=service.processNext();if(result.isPresent()&&"CONFLICT".equals(result.get().status()))log.error("방송 비트코인 거래가 충돌 상태여서 완료를 중단했습니다");if(failed){failed=false;log.info("비트코인 체인 확인을 재개했습니다");}}catch(RuntimeException error){if(!failed){failed=true;log.warn("비트코인 체인 확인이 지연됩니다. 방송 결과와 확인 이력을 보존합니다");}}}
}
