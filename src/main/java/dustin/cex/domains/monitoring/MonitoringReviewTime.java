package dustin.cex.domains.monitoring;

import java.time.Clock;
import org.springframework.context.annotation.*;

/** 불변 요청을 수정하거나 호스트 시계를 바꾸지 않고 만료 경계를 검증할 수 있도록 표준 시계를 주입한다. */
@Configuration(proxyBeanMethods=false)
class MonitoringReviewTime {
    @Bean("monitoringReviewClock") Clock clock(){return Clock.systemUTC();}
}
