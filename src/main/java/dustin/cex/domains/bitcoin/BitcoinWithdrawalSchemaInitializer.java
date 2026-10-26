package dustin.cex.domains.bitcoin;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 기존 고객·원장·견적·수신 키가 준비된 뒤 출금의 서명/예약/영속 전송 계약을 준비한다. */
@Component
@DependsOn("bitcoinCreditSchemaInitializer")
public class BitcoinWithdrawalSchemaInitializer {
    private final JdbcTemplate jdbc;
    public BitcoinWithdrawalSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @PostConstruct void initialize() throws Exception {
        jdbc.execute(new ClassPathResource("bitcoin/withdrawal-reservation-schema.sql")
            .getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute(new ClassPathResource("bitcoin/withdrawal-cancellation-schema.sql")
            .getContentAsString(StandardCharsets.UTF_8));
        jdbc.execute(new ClassPathResource("bitcoin/withdrawal-valuation-schema.sql")
            .getContentAsString(StandardCharsets.UTF_8));
    }
}
