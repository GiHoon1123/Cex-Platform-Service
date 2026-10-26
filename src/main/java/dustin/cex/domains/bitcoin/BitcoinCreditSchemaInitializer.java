package dustin.cex.domains.bitcoin;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 실제 출처·현재 조사·원장 표 위에 입금 의도/실행/확정 수신 표를 준비한다. 과거 보류를 자동 승인하거나 엔진을 호출하지 않는다. */
@Component
@DependsOn({"depositMonitoringSchemaInitializer","codeVaspSchemaInitializer"})
public class BitcoinCreditSchemaInitializer {
    private final JdbcTemplate jdbc;
    public BitcoinCreditSchemaInitializer(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct void initialize()throws Exception{jdbc.execute(new ClassPathResource("bitcoin/credit-schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("bitcoin/credit-execution-schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("bitcoin/credit-processing-schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("bitcoin/credit-chain-risk-schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("bitcoin/chain-recovery-proof-schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("bitcoin/chain-recovery-clearance-schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("bitcoin/chain-risk-cancellation-schema.sql").getContentAsString(StandardCharsets.UTF_8));}
}
