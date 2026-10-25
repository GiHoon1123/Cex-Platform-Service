package dustin.cex.domains.bitcoin;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 기존 불변 원본 트리거와 사용자 표가 준비된 일반 서버에서 주소 표를 초기화한다. */
@Component
@DependsOn({"kycSchemaInitializer","ledgerService"})
public class BitcoinSchemaInitializer {
    private final JdbcTemplate jdbc;
    public BitcoinSchemaInitializer(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct void initialize()throws Exception{jdbc.execute(new ClassPathResource("bitcoin/schema.sql").getContentAsString(StandardCharsets.UTF_8));}
}
