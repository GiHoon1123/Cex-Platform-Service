package dustin.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 운영 기본 설정과 명시적인 로컬 설정의 경계가 다시 섞이지 않는지 검사한다. */
class DeploymentConfigurationTest {
    @Test
    void productionDefaultsRequireExternalEndpointsAndCredentials() throws Exception {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"));

        assertTrue(properties.contains("spring.datasource.url=${DATABASE_URL}"));
        assertTrue(properties.contains("spring.datasource.username=${DATABASE_USERNAME}"));
        assertTrue(properties.contains("spring.datasource.password=${DATABASE_PASSWORD}"));
        assertTrue(properties.contains("spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS}"));
        assertTrue(properties.contains("engine.http.url=${ENGINE_HTTP_URL}"));
        assertTrue(properties.contains("spring.jpa.hibernate.ddl-auto=validate"));
        assertFalse(properties.contains("localhost:5432"));
        assertFalse(properties.contains("password=1234"));
    }

    @Test
    void localCredentialsExistOnlyInTheExplicitLocalProfile() throws Exception {
        String properties = Files.readString(Path.of("src/main/resources/application-local.properties"));

        assertTrue(properties.contains("127.0.0.1:5433"));
        assertTrue(properties.contains("spring.jpa.hibernate.ddl-auto=update"));
        assertTrue(properties.contains("operator.webauthn.origin=http://localhost:3004"));
    }
}
