package dustin.cex.domains.market;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 체결·취소 반영보다 먼저 시장 감시의 불변 입력과 평가 표를 준비한다. */
@Component
public class MarketSurveillanceSchemaInitializer {
    private final JdbcTemplate jdbc;
    public MarketSurveillanceSchemaInitializer(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct void initialize()throws Exception{
        jdbc.execute(new ClassPathResource("market/surveillance-schema.sql").getContentAsString(StandardCharsets.UTF_8));
    }
}
