package dustin.cex.domains.reconciliation;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 운영자가 실행한 대사만 수행한다. 고객 토큰으로 호출할 수 있는 공개 API는 만들지 않는다. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name="reconciliation.run",havingValue="true")
public class ReconciliationRunner implements ApplicationRunner {
    private final ReconciliationService reconciliation;
    private final org.springframework.context.ConfigurableApplicationContext context;
    public void run(ApplicationArguments arguments) {
        var result=reconciliation.run();log.info("대사 완료: report={}, status={}",result.id(),result.status());
        if(!result.status().equals("MATCHED")) throw new IllegalStateException("대사 미완료 또는 불일치: "+result.id());
        // 명시적인 일회성 점검 프로세스만 종료한다. 별도로 실행 중인 플랫폼·엔진에는 신호를 보내지 않는다.
        context.close();
    }
}
