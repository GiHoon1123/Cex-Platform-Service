package dustin.signer;

import dustin.cex.domains.bitcoin.BitcoinSignerEnvelopeCrypto;
import dustin.cex.domains.security.AuditService;
import java.util.Map;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** 웹·Kafka·JPA 없이 고정 서명 요청만 소비하는 별도 프로세스 진입점이다. */
@Configuration(proxyBeanMethods=false)
@EnableAutoConfiguration(exclude={org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class,
    org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,
    org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class,
    org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
    org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class})
@EnableScheduling
@Import({BitcoinSignerEnvelopeCrypto.class,AuditService.class})
public class BitcoinSignerApplication {
    @Bean BitcoinSignerSettings signerSettings(org.springframework.core.env.Environment e){return new BitcoinSignerSettings(e.getRequiredProperty("bitcoin.signer.rpc-url"),e.getRequiredProperty("bitcoin.signer.rpc-user"),e.getRequiredProperty("bitcoin.signer.rpc-password"),e.getRequiredProperty("bitcoin.signer.wallet"),e.getProperty("bitcoin.signer.network","REGTEST"));}
    @Bean(destroyMethod="close") BitcoinSignerRpcClient signerRpc(BitcoinSignerSettings settings,ObjectMapper json){return new BitcoinSignerRpcClient(settings,json);}
    @Bean BitcoinSigningService signing(JdbcTemplate jdbc,BitcoinSignerEnvelopeCrypto crypto,ObjectMapper json,AuditService audit,BitcoinSignerRpcClient rpc,PlatformTransactionManager manager){return new BitcoinSigningService(jdbc,crypto,json,audit,rpc,manager);}
    @Bean BitcoinSignerWorker worker(BitcoinSigningService signing){return new BitcoinSignerWorker(signing);}
    /**
     * 외부 설정이 웹 실행을 요구해도 일반 비웹 컨텍스트만 만들도록 실행 경계를 고정한다.
     * 반환값을 테스트에서 직접 검사해 진입점과 검증 코드가 서로 다른 구성을 만들지 않게 한다.
     */
    public static SpringApplication application(){
        var app=new SpringApplication(BitcoinSignerApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(new org.springframework.core.env.StandardEnvironment());
        app.setApplicationContextFactory(type->new org.springframework.context.annotation.AnnotationConfigApplicationContext());
        app.addInitializers(context->context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("bitcoin-signer-boundary",Map.of(
            "spring.sql.init.mode","never","spring.main.web-application-type","none","spring.jpa.hibernate.ddl-auto","none"))));
        return app;
    }
    public static void main(String[] args){application().run(args);}
}
