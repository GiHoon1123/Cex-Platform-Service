package dustin.cex.domains.auth.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.beans.factory.annotation.Value;

/** 프론트는 플랫폼만 호출한다. 허용 출처와 주문 중복 방지 헤더를 명시한다. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Value("${frontend.origin:http://localhost:3003}") private String origin;
    // 향후 별도 어드민 화면도 정확히 지정한 출처만 허용한다. 와일드카드·자동 반사 출처는 쓰지 않는다.
    @Value("${operator.webauthn.origin:http://localhost:3003}") private String operatorOrigin;
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(java.util.stream.Stream.of(origin,operatorOrigin).distinct().toArray(String[]::new))
            .allowedMethods("GET","POST","DELETE","OPTIONS")
            .allowedHeaders("Authorization","Content-Type","Idempotency-Key");
    }
}
