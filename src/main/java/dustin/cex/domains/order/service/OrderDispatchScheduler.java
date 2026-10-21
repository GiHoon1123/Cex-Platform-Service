package dustin.cex.domains.order.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

/** 운영에서는 저장된 대기 주문을 자동 재확인한다. 외부 의존성 없는 테스트에서는 명시적으로 끈다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="order.dispatch.enabled",havingValue="true",matchIfMissing=true)
public class OrderDispatchScheduler {
    private final OrderDispatchService dispatch;
    @Scheduled(fixedDelay=1000)
    public void retryPending() { dispatch.retryDue(); }
}
