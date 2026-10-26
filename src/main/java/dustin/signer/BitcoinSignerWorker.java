package dustin.signer;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;

/** 서명 전용 프로세스의 단일 소비자다. 실패 원문이나 PSBT·원시 거래를 로그에 쓰지 않는다. */
public final class BitcoinSignerWorker {
    private static final Logger log=LoggerFactory.getLogger(BitcoinSignerWorker.class);private final BitcoinSigningService signing;private boolean failed;
    public BitcoinSignerWorker(BitcoinSigningService signing){this.signing=signing;}
    @Scheduled(fixedDelayString="${bitcoin.signer.poll-millis:1000}") public void tick(){
        try{var result=signing.processNext();if(result.isPresent()&&"REJECTED".equals(result.get().status()))log.error("고정 비트코인 서명 요청이 검증을 통과하지 못해 보류됐습니다");
            if(failed){failed=false;log.info("비트코인 서명 처리를 재개했습니다");}}
        catch(RuntimeException error){if(!failed){failed=true;log.warn("비트코인 서명 처리가 지연됩니다. 고정 요청과 입력 잠금을 보존합니다");}}
    }
}
