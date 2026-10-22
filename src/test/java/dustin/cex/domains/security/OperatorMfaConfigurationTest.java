package dustin.cex.domains.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.JwtService;

/** 배포 설정 실수가 인증 출처를 넓히지 않도록 서버 시작 단계에서 거부하는지 검사한다. */
class OperatorMfaConfigurationTest {
    OperatorMfaService service(String rp,String origin) {
        return new OperatorMfaService(mock(JdbcTemplate.class),mock(UserRepository.class),mock(RefreshTokenRepository.class),
            mock(AuditService.class),mock(PlatformTransactionManager.class),mock(JwtService.class),rp,origin);
    }
    @ParameterizedTest
    @CsvSource({"example.com,http://admin.example.com","example.com,https://evil.example.net",
        "example.com,https://admin.example.com/","example.com,https://admin.example.com?origin=evil",
        "example.com,https://admin.example.com#fragment","example.com,https://user:secret@admin.example.com",
        "localhost,http://evil.localhost:3003","example.com,https://*.example.com"})
    void invalidRpOriginConfigurationFailsBeforeServingRequests(String rp,String origin) {
        assertThrows(IllegalArgumentException.class,()->service(rp,origin));
    }
    @ParameterizedTest
    @CsvSource({"localhost,http://localhost:3003","example.com,https://admin.example.com","admin.example.com,https://admin.example.com"})
    void explicitHttpsOrLocalhostOriginIsAccepted(String rp,String origin) {assertNotNull(service(rp,origin));}
    @Test void corsAllowsExactCustomerAndOperatorOriginsWithoutCredentialsOrExtraHeaders() {
        var config=new dustin.cex.domains.auth.config.WebConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(config,"origin","https://exchange.example.com");
        org.springframework.test.util.ReflectionTestUtils.setField(config,"operatorOrigin","https://admin.example.com");
        class Registry extends org.springframework.web.servlet.config.annotation.CorsRegistry {
            org.springframework.web.cors.CorsConfiguration api() {return getCorsConfigurations().get("/api/**");}
        }
        var registry=new Registry();config.addCorsMappings(registry);var cors=registry.api();
        assertEquals("https://exchange.example.com",cors.checkOrigin("https://exchange.example.com"));
        assertEquals("https://admin.example.com",cors.checkOrigin("https://admin.example.com"));
        assertNull(cors.checkOrigin("https://admin.example.com.evil.test"));
        assertNull(cors.checkHeaders(java.util.List.of("X-Role")));
        assertNotEquals(Boolean.TRUE,cors.getAllowCredentials());
    }

}
