package dustin.ethsigner;

import dustin.cex.domains.ethereum.EthereumJsonRpcClient;
import dustin.cex.domains.ethereum.EthereumSettings;
import dustin.cex.domains.security.AuditService;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/** 웹·Kafka·JPA 없이 승인된 ETH 지급만 서명 노드에 전달하는 별도 프로세스 진입점이다. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@EnableAutoConfiguration(exclude={org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,
        org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class,
        org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class})
@Import(AuditService.class)
public class EthereumSignerApplication {
    @Bean EthereumSignerSettings settings(org.springframework.core.env.Environment environment) {
        String url = environment.getRequiredProperty("ethereum.signer.rpc-url");
        String chain = environment.getRequiredProperty("ethereum.signer.chain-id");
        var node = new EthereumSettings(url, chain, "0", 1, "");
        return new EthereumSignerSettings(node, environment.getRequiredProperty("ethereum.signer.from"));
    }
    @Bean(destroyMethod="close") EthereumJsonRpcClient rpc(EthereumSignerSettings settings) { return new EthereumJsonRpcClient(settings.node()); }
    @Bean EthereumPayoutService payout(JdbcTemplate jdbc, EthereumSignerSettings settings, EthereumJsonRpcClient rpc,
            AuditService audit, PlatformTransactionManager manager) { return new EthereumPayoutService(jdbc, settings, rpc, audit, manager); }
    @Bean EthereumPayoutWorker worker(EthereumPayoutService service) { return new EthereumPayoutWorker(service); }

    public static SpringApplication application() {
        var app = new SpringApplication(EthereumSignerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(new org.springframework.core.env.StandardEnvironment());
        app.setApplicationContextFactory(type -> new org.springframework.context.annotation.AnnotationConfigApplicationContext());
        app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ethereum-signer-boundary",
                Map.of("spring.sql.init.mode", "never", "spring.main.web-application-type", "none", "spring.jpa.hibernate.ddl-auto", "none"))));
        return app;
    }
    public static void main(String[] args) { application().run(args); }
}
