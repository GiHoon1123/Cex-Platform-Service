package dustin.broadcaster;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;

/** 방송 전용 단일 소비자다. 원시 거래나 노드 오류 원문은 로그에 남기지 않는다. */
public final class BitcoinBroadcasterWorker {
    private static final Logger log=LoggerFactory.getLogger(BitcoinBroadcasterWorker.class);private final BitcoinBroadcastService service;private boolean failed;
    public BitcoinBroadcasterWorker(BitcoinBroadcastService service){this.service=service;}
    @Scheduled(fixedDelayString="${bitcoin.broadcaster.poll-millis:1000}") public void tick(){try{var result=service.processNext();if(result.isPresent()&&"REJECTED".equals(result.get().status()))log.error("고정 비트코인 거래가 노드 검증을 통과하지 못해 보류됐습니다");if(failed){failed=false;log.info("비트코인 방송 처리를 재개했습니다");}}catch(RuntimeException error){if(!failed){failed=true;log.warn("비트코인 방송 처리가 지연됩니다. 서명 결과와 재시도 이력을 보존합니다");}}}
}
