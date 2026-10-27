package dustin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dustin.broadcaster.*;
import dustin.confirmer.*;
import dustin.ethconfirmer.*;
import dustin.ethsigner.*;
import dustin.operator.OperatorAdminApplication;
import dustin.signer.*;
import jakarta.persistence.EntityManagerFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.servlet.DispatcherServlet;

/** 분리 실행 파일이 고객 웹 플랫폼의 HTTP·Kafka·JPA 권한을 함께 얻지 않는지 검증한다. */
class IsolatedProcessBoundaryTest {
    private static final String PASSWORD="p".repeat(32);

    @Test void isolatedEntrypointsUseExplicitConfigurationInsteadOfCustomerComponentScanning(){
        for(var source:List.of(BitcoinSignerApplication.class,BitcoinBroadcasterApplication.class,
                BitcoinConfirmerApplication.class,EthereumSignerApplication.class,
                EthereumConfirmerApplication.class,OperatorAdminApplication.class)){
            assertNotNull(source.getAnnotation(Configuration.class),source.getName());
            assertNull(source.getAnnotation(SpringBootApplication.class),source.getName());
            assertNotNull(source.getAnnotation(EnableAutoConfiguration.class),source.getName());
        }
        var applications=Map.of(
                BitcoinSignerApplication.class,BitcoinSignerApplication.application(),
                BitcoinBroadcasterApplication.class,BitcoinBroadcasterApplication.application(),
                BitcoinConfirmerApplication.class,BitcoinConfirmerApplication.application(),
                EthereumSignerApplication.class,EthereumSignerApplication.application(),
                EthereumConfirmerApplication.class,EthereumConfirmerApplication.application(),
                OperatorAdminApplication.class,OperatorAdminApplication.application());
        applications.forEach((source,application)->{
            assertEquals(WebApplicationType.NONE,application.getWebApplicationType(),source.getName());
            assertEquals(Set.of(source),application.getAllSources(),source.getName());
        });
    }

    @Test void signerIgnoresAnExternalServletRequestAndLoadsOnlyItsWorker(){
        try(var context=run(BitcoinSignerApplication.application(),"signer",
                "--bitcoin.signer.rpc-url=http://127.0.0.1:1","--bitcoin.signer.rpc-user=signer",
                "--bitcoin.signer.rpc-password="+PASSWORD,"--bitcoin.signer.wallet=keys")){
            assertBeanBoundary(context,BitcoinSignerWorker.class);
            assertTrue(context.getBeansOfType(BitcoinBroadcasterWorker.class).isEmpty());
            assertTrue(context.getBeansOfType(BitcoinConfirmerWorker.class).isEmpty());
        }
    }

    @Test void broadcasterIgnoresAnExternalServletRequestAndLoadsOnlyItsWorker(){
        try(var context=run(BitcoinBroadcasterApplication.application(),"broadcaster",
                "--bitcoin.broadcaster.rpc-url=http://127.0.0.1:1","--bitcoin.broadcaster.rpc-user=broadcaster",
                "--bitcoin.broadcaster.rpc-password="+PASSWORD,"--bitcoin.broadcaster.wallet=watch")){
            assertBeanBoundary(context,BitcoinBroadcasterWorker.class);
            assertTrue(context.getBeansOfType(BitcoinSignerWorker.class).isEmpty());
            assertTrue(context.getBeansOfType(BitcoinConfirmerWorker.class).isEmpty());
        }
    }

    @Test void confirmerIgnoresAnExternalServletRequestAndLoadsOnlyItsWorker(){
        try(var context=run(BitcoinConfirmerApplication.application(),"confirmer",
                "--bitcoin.confirmation.rpc-url=http://127.0.0.1:1","--bitcoin.confirmation.rpc-user=confirmer",
                "--bitcoin.confirmation.rpc-password="+PASSWORD)){
            assertBeanBoundary(context,BitcoinConfirmerWorker.class);
            assertTrue(context.getBeansOfType(BitcoinSignerWorker.class).isEmpty());
            assertTrue(context.getBeansOfType(BitcoinBroadcasterWorker.class).isEmpty());
        }
    }

    @Test void ethereumSignerLoadsNoCustomerWebOrKafkaBoundary(){
        try(var context=run(EthereumSignerApplication.application(),"ethereum-signer",
                "--ethereum.signer.rpc-url=http://127.0.0.1:1","--ethereum.signer.chain-id=31337",
                "--ethereum.signer.from=0x0000000000000000000000000000000000000001")){
            assertBeanBoundary(context,EthereumPayoutWorker.class);
            assertTrue(context.getBeansOfType(EthereumConfirmationWorker.class).isEmpty());
        }
    }

    @Test void ethereumConfirmerHasNoSignerOrCustomerWebBoundary(){
        try(var context=run(EthereumConfirmerApplication.application(),"ethereum-confirmer",
                "--ethereum.confirmation.rpc-url=http://127.0.0.1:1","--ethereum.confirmation.chain-id=31337",
                "--ethereum.confirmation.required=1")){
            assertBeanBoundary(context,EthereumConfirmationWorker.class);
            assertTrue(context.getBeansOfType(EthereumPayoutWorker.class).isEmpty());
        }
    }

    private ConfigurableApplicationContext run(SpringApplication app,String name,String... settings){
        var arguments=new ArrayList<>(List.of(settings));
        // 공격적 외부 설정을 가장 높은 명령행 우선순위로 넣어도 실행 파일은 웹 컨텍스트를 만들면 안 된다.
        arguments.add("--spring.main.web-application-type=servlet");
        arguments.add("--spring.datasource.url=jdbc:h2:mem:"+name+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        arguments.add("--spring.datasource.username=sa");
        arguments.add("--spring.datasource.password=");
        arguments.add("--spring.task.scheduling.enabled=false");
        return app.run(arguments.toArray(String[]::new));
    }

    private void assertBeanBoundary(ConfigurableApplicationContext context,Class<?> expectedWorker){
        assertInstanceOf(AnnotationConfigApplicationContext.class,context);
        assertFalse(context.getBeansOfType(expectedWorker).isEmpty());
        assertTrue(context.getBeansOfType(DispatcherServlet.class).isEmpty());
        assertTrue(context.getBeansOfType(KafkaTemplate.class).isEmpty());
        assertTrue(context.getBeansOfType(EntityManagerFactory.class).isEmpty());
        // 스키마 이력의 단일 소유자는 고객 플랫폼이다. 격리 워커가 기동 중 마이그레이션을 실행하면 안 된다.
        assertTrue(context.getBeansOfType(Flyway.class).isEmpty());
    }
}
