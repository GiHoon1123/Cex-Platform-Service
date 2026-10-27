package dustin.ethconfirmer;

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

/** 웹·Kafka·JPA·서명 권한 없이 ETH 체인 확정 사실만 기록하는 별도 프로세스다. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@EnableAutoConfiguration(exclude={org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class,
        org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,
        org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class})
@Import(AuditService.class)
public class EthereumConfirmerApplication {
    @Bean EthereumConfirmationSettings settings(org.springframework.core.env.Environment environment) {
        String url = environment.getRequiredProperty("ethereum.confirmation.rpc-url");
        String chain = environment.getRequiredProperty("ethereum.confirmation.chain-id");
        int required = environment.getProperty("ethereum.confirmation.required", Integer.class, 12);
        return new EthereumConfirmationSettings(new EthereumSettings(url, chain, "0", required, ""), required);
    }
    @Bean(destroyMethod="close") EthereumJsonRpcClient rpc(EthereumConfirmationSettings settings) { return new EthereumJsonRpcClient(settings.node()); }
    @Bean EthereumConfirmationService confirmation(JdbcTemplate jdbc, EthereumConfirmationSettings settings,
            EthereumJsonRpcClient rpc, AuditService audit, PlatformTransactionManager manager) { return new EthereumConfirmationService(jdbc, settings, rpc, audit, manager); }
    @Bean EthereumConfirmationWorker worker(EthereumConfirmationService service) { return new EthereumConfirmationWorker(service); }
    public static SpringApplication application() {
        var app = new SpringApplication(EthereumConfirmerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(new org.springframework.core.env.StandardEnvironment());
        app.setApplicationContextFactory(type -> new org.springframework.context.annotation.AnnotationConfigApplicationContext());
        app.addInitializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ethereum-confirmer-boundary",
                Map.of("spring.sql.init.mode", "never", "spring.main.web-application-type", "none", "spring.jpa.hibernate.ddl-auto", "none"))));
        return app;
    }
    public static void main(String[] args) { application().run(args); }
}
