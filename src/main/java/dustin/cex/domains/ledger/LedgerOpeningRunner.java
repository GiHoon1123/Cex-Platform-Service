package dustin.cex.domains.ledger;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/** 운영자가 최초 반입을 명시한 실행에서만 수행한다. 자동 잔고 보정이나 재시작 재입금이 아니다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="ledger.initialize",havingValue="true")
public class LedgerOpeningRunner implements ApplicationRunner {
    private final LedgerService ledger;
    public void run(ApplicationArguments arguments) { ledger.initializeOpening(); }
}
