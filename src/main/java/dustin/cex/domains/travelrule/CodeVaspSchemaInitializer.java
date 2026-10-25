package dustin.cex.domains.travelrule;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 공급사 자료/조회 이력의 원본 표를 준비한다. 설정 유무로 기존 이력을 지우거나 노드에 접속하지 않는다. */
@Component
@DependsOn({"kycSchemaInitializer","securitySchemaInitializer","bitcoinSchemaInitializer"})
public class CodeVaspSchemaInitializer {
    private final JdbcTemplate jdbc;
    public CodeVaspSchemaInitializer(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct void initialize()throws Exception{jdbc.execute(new ClassPathResource("travelrule/schema.sql").getContentAsString(StandardCharsets.UTF_8));}
}
