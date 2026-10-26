package dustin.broadcaster;

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

/** 웹·Kafka·JPA·개인키 없이 확정 서명 결과만 공개하는 별도 프로세스 진입점이다. */
@Configuration(proxyBeanMethods=false)
@EnableAutoConfiguration(exclude={org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class,org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class})
@EnableScheduling
@Import({BitcoinSignerEnvelopeCrypto.class,AuditService.class})
public class BitcoinBroadcasterApplication {
    @Bean BitcoinBroadcastSettings broadcastSettings(org.springframework.core.env.Environment e){return new BitcoinBroadcastSettings(e.getRequiredProperty("bitcoin.broadcaster.rpc-url"),e.getRequiredProperty("bitcoin.broadcaster.rpc-user"),e.getRequiredProperty("bitcoin.broadcaster.rpc-password"),e.getRequiredProperty("bitcoin.broadcaster.wallet"),e.getProperty("bitcoin.broadcaster.network","REGTEST"));}
    @Bean(destroyMethod="close") BitcoinBroadcastRpcClient broadcastRpc(BitcoinBroadcastSettings settings,ObjectMapper json){return new BitcoinBroadcastRpcClient(settings,json);}
    @Bean BitcoinBroadcastService broadcast(JdbcTemplate jdbc,BitcoinSignerEnvelopeCrypto crypto,AuditService audit,BitcoinBroadcastRpcClient rpc,PlatformTransactionManager manager){return new BitcoinBroadcastService(jdbc,crypto,audit,rpc,manager);}
    @Bean BitcoinBroadcasterWorker worker(BitcoinBroadcastService service){return new BitcoinBroadcasterWorker(service);}
    public static void main(String[] args){var app=new SpringApplication(BitcoinBroadcasterApplication.class);app.setWebApplicationType(WebApplicationType.NONE);app.addInitializers(context->context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("bitcoin-broadcaster-boundary",Map.of("spring.sql.init.mode","never","spring.main.web-application-type","none","spring.jpa.hibernate.ddl-auto","none"))));app.run(args);}
}
