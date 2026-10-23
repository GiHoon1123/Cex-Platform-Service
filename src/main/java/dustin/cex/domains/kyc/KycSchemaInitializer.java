package dustin.cex.domains.kyc;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 일반 서버에서만 KYC 표를 준비한다. 비웹 운영자 초기화 도구에는 포함하지 않는다. */
@Component
public class KycSchemaInitializer {
    private final JdbcTemplate jdbc;
    public KycSchemaInitializer(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @PostConstruct void initialize() throws Exception {jdbc.execute(new ClassPathResource("kyc/schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("cdd/schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("edd/schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("edd/evidence-schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("edd/address-review-schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("edd/funds-review-schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("edd/wealth-review-schema.sql").getContentAsString(StandardCharsets.UTF_8));}
}
