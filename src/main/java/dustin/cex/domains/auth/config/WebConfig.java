package dustin.cex.domains.auth.config;

import org.springframework.context.annotation.Configuration;

/**
 * Web 설정
 * Web Configuration
 * JWT 필터는 @Component로 자동 등록됨
 */
@Configuration
public class WebConfig {
    // JWT 필터는 JwtAuthenticationFilter의 @Component로 자동 등록됨
}
