package dustin.cex.domains.security;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 메일 암호문 표와 보안 감사가 준비된 뒤 보존 정지·파기 증적 제약을 설치한다. */
@Component
@DependsOn("kycSchemaInitializer")
public class DataRetentionSchemaInitializer {
    private final JdbcTemplate jdbc;
    public DataRetentionSchemaInitializer(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct void initialize()throws Exception{jdbc.execute(new ClassPathResource("retention/schema.sql").getContentAsString(StandardCharsets.UTF_8));}
}
