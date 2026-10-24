package dustin.cex.shared.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import dustin.cex.shared.http.EngineHttpClient;

/**
 * 플랫폼 시작을 금융 거래로 해석하지 않는지 검증한다.
 *
 * 전체 Spring Boot 앱을 띄우면 봇·스케줄러·Kafka 소비자가 함께 실행될 수 있다.
 * 여기서는 실제 잔액 서비스 패키지와 Spring의 이벤트 처리를 사용하되,
 * DB는 테스트마다 생성한 H2, 엔진은 Mockito 객체로 격리한다.
 * 따라서 실제 PostgreSQL·Kafka·Rust 엔진에 요청하거나 고객 데이터를 수정하지 않는다.
 */
class PlatformBalanceStartupTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private EngineHttpClient engineHttpClient;

    @BeforeEach
    void createIsolatedBalances() {
        // generateUniqueName은 병렬 테스트 사이에도 같은 인메모리 DB가 섞이지 않게 한다.
        // 애플리케이션 전체 스키마 대신, 시작 시 잔액 조회에 필요한 테이블만 만든다.
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        jdbcTemplate.execute("""
                CREATE TABLE user_balances (
                    user_id BIGINT NOT NULL,
                    mint_address VARCHAR(32) NOT NULL,
                    available DECIMAL(30, 8) NOT NULL,
                    locked DECIMAL(30, 8) NOT NULL,
                    PRIMARY KEY (user_id, mint_address)
                )
                """);
        jdbcTemplate.update("INSERT INTO user_balances VALUES (?, ?, ?, ?)",
                101L, "USDT", new BigDecimal("100.25"), new BigDecimal("30.50"));
        jdbcTemplate.update("INSERT INTO user_balances VALUES (?, ?, ?, ?)",
                102L, "SOL", BigDecimal.ZERO, new BigDecimal("2.75"));

        engineHttpClient = mock(EngineHttpClient.class);

    }

    @AfterEach
    void closeIsolatedDatabase() {
        database.shutdown();
    }

    @Test
    @DisplayName("플랫폼 준비 이벤트는 기존 잔액을 엔진에 입금하지 않는다")
    void readyEventDoesNotSendExistingBalancesAsDeposits() {
        try (var context = createPlatformBalanceContext()) {
            publishReadyEvent(context);

            // DB 잔액이 그대로인 것만 검사하면 엔진 메모리만 늘어나는 버그를 놓친다.
            // 엔진 클라이언트에 요청 자체가 없었다는 조건을 함께 검증한다.
            assertNoFinancialCommandSent();
            assertPersistedBalancesUnchanged();
        }
    }

    @Test
    @DisplayName("준비 이벤트를 반복 발행해도 잔액 변경 요청이 없다")
    void repeatedReadyEventsDoNotReplayDeposits() {
        try (var context = createPlatformBalanceContext()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                publishReadyEvent(context);
            }

            assertNoFinancialCommandSent();
            assertPersistedBalancesUnchanged();
        }
    }

    @Test
    @DisplayName("플랫폼 컨텍스트를 다시 만들어도 기존 잔액을 재입금하지 않는다")
    void restartingPlatformContextDoesNotReplayDeposits() {
        // 같은 DB·엔진 연결을 유지하고 Spring 컨텍스트만 새로 생성한다.
        // 실제 프로세스 전체 재시작 대신 시작 훅 재등록·재실행 경계를 시험한다.
        for (int restart = 0; restart < 3; restart++) {
            try (var context = createPlatformBalanceContext()) {
                publishReadyEvent(context);
            }
        }

        assertNoFinancialCommandSent();
        assertPersistedBalancesUnchanged();
    }

    private AnnotationConfigApplicationContext createPlatformBalanceContext() {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> jdbcTemplate);
        context.registerBean(EngineHttpClient.class, () -> engineHttpClient);
        // 특정 삭제 대상 클래스를 직접 등록하지 않는다. 실제 서비스 패키지를
        // 스캔하므로 시작 시 재입금 리스너가 다시 들어오면 회귀 테스트가 실패한다.
        context.scan("dustin.cex.shared.services");
        context.refresh();
        return context;
    }

    private void publishReadyEvent(AnnotationConfigApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], context, Duration.ZERO));
    }

    private void assertNoFinancialCommandSent() {
        // Spring의 생성 후 초기화만 허용한다. 현재/미래의 금융 명령 호출은 모두 거부한다.
        // 삭제한 메서드 이름에 의존하지 않아도 시작 훅이 어떤 명령을 추가하는지 검출한다.
        assertThat(org.mockito.Mockito.mockingDetails(engineHttpClient).getInvocations())
                .allSatisfy(call -> assertThat(call.getMethod().getName()).isEqualTo("init"));
    }

    private void assertPersistedBalancesUnchanged() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available FROM user_balances WHERE user_id = 101", BigDecimal.class))
                .isEqualByComparingTo("100.25");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT locked FROM user_balances WHERE user_id = 101", BigDecimal.class))
                .isEqualByComparingTo("30.50");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available FROM user_balances WHERE user_id = 102", BigDecimal.class))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT locked FROM user_balances WHERE user_id = 102", BigDecimal.class))
                .isEqualByComparingTo("2.75");
    }
}
