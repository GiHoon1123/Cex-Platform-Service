package dustin.confirmer;

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

/** 웹·Kafka·JPA·서명·방송 권한 없이 체인 확정 사실만 기록하는 프로세스 진입점이다. */
@Configuration(proxyBeanMethods=false) @EnableScheduling
@EnableAutoConfiguration(exclude={org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class})
@Import(AuditService.class)
public class BitcoinConfirmerApplication {
    @Bean BitcoinConfirmationSettings settings(org.springframework.core.env.Environment e){return new BitcoinConfirmationSettings(e.getRequiredProperty("bitcoin.confirmation.rpc-url"),e.getRequiredProperty("bitcoin.confirmation.rpc-user"),e.getRequiredProperty("bitcoin.confirmation.rpc-password"),e.getProperty("bitcoin.confirmation.network","REGTEST"),e.getProperty("bitcoin.confirmation.required",Integer.class,6));}
    @Bean(destroyMethod="close") BitcoinConfirmationRpcClient rpc(BitcoinConfirmationSettings settings,ObjectMapper json){return new BitcoinConfirmationRpcClient(settings,json);}
    @Bean BitcoinConfirmationService confirmation(JdbcTemplate jdbc,AuditService audit,BitcoinConfirmationRpcClient rpc,PlatformTransactionManager manager){return new BitcoinConfirmationService(jdbc,audit,rpc,manager);}
    @Bean BitcoinConfirmerWorker worker(BitcoinConfirmationService service){return new BitcoinConfirmerWorker(service);}
    /** 읽기 전용 체인 확인 프로세스가 외부 설정 때문에 HTTP 진입점을 갖지 않도록 일반 컨텍스트를 강제한다. */
    public static SpringApplication application(){
        var app=new SpringApplication(BitcoinConfirmerApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(new org.springframework.core.env.StandardEnvironment());
        app.setApplicationContextFactory(type->new org.springframework.context.annotation.AnnotationConfigApplicationContext());
        app.addInitializers(context->context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("bitcoin-confirmer-boundary",Map.of("spring.sql.init.mode","never","spring.main.web-application-type","none","spring.jpa.hibernate.ddl-auto","none"))));
        return app;
    }
    public static void main(String[] args){application().run(args);}
}
