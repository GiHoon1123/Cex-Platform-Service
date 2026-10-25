package dustin.cex.domains.monitoring;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 기존 금융 원본 위에 읽기 입력·불변 탐지 근거·작업 위치를 준비한다. 원본 재평가나 금융 효과는 없다. */
@Component
@DependsOn("bitcoinKrwPriceSchemaInitializer")
public class DepositMonitoringSchemaInitializer {
    private final JdbcTemplate jdbc;
    public DepositMonitoringSchemaInitializer(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct void initialize()throws Exception{
        // 사건이 참조할 탐지와 조사 자료부터 준비한다. 기존 금융·감사 기록을 삭제하거나 과거 판정을 채우지 않는다.
        jdbc.execute(new ClassPathResource("monitoring/deposit-schema.sql").getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute(new ClassPathResource("monitoring/investigation-schema.sql").getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute(new ClassPathResource("monitoring/case-schema.sql").getContentAsString(StandardCharsets.UTF_8));
    }
}
