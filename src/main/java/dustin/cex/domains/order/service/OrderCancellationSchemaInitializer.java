package dustin.cex.domains.order.service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** JPA 주문/취소 표와 보안 감사가 준비된 뒤 원본 보존·필수 감사 제약을 설치한다. 외부 전송은 하지 않는다. */
@Component
@DependsOn({"entityManagerFactory","securitySchemaInitializer","ledgerService","bitcoinCreditSchemaInitializer"})
public class OrderCancellationSchemaInitializer {
    private final JdbcTemplate jdbc;
    public OrderCancellationSchemaInitializer(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct void initialize()throws Exception{jdbc.execute(new ClassPathResource("order/cancellation-schema.sql").getContentAsString(StandardCharsets.UTF_8));}
}
