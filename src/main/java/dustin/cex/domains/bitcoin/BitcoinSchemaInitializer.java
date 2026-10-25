package dustin.cex.domains.bitcoin;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 사용자·원장·감사 표가 준비된 뒤 비트코인 원본과 실행 네트워크를 초기화한다. */
@Component
@DependsOn({"kycSchemaInitializer","ledgerService","securitySchemaInitializer"})
public class BitcoinSchemaInitializer {
    private final JdbcTemplate jdbc;
    private final BitcoinNetworkIdentity identity;
    public BitcoinSchemaInitializer(JdbcTemplate jdbc,BitcoinNetworkIdentity identity){this.jdbc=jdbc;this.identity=identity;}
    @PostConstruct void initialize()throws Exception{jdbc.execute(new ClassPathResource("bitcoin/schema.sql").getContentAsString(StandardCharsets.UTF_8));identity.verifyOrBind();}
}
