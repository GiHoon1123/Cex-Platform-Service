package dustin.cex.domains.valuation;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 공개 참고가격의 불변 원본을 준비한다. 초기화는 외부 가격 조회나 고객 잔액 반영을 하지 않는다. */
@Component
// 기존 공통 불변 트리거 함수를 준비한 뒤 실행한다. 고객 신원/자격을 가격 결정에 사용하는 의존성은 아니다.
@DependsOn({"kycSchemaInitializer","securitySchemaInitializer","bitcoinSchemaInitializer"})
public class BitcoinKrwPriceSchemaInitializer {
    private final JdbcTemplate jdbc;
    public BitcoinKrwPriceSchemaInitializer(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct void initialize()throws Exception{jdbc.execute(new ClassPathResource("valuation/schema.sql").getContentAsString(StandardCharsets.UTF_8));jdbc.execute(new ClassPathResource("valuation/bitcoin-deposit-schema.sql").getContentAsString(StandardCharsets.UTF_8));}
}
