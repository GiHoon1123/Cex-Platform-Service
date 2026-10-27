package dustin.operator;

import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;

/**
 * HTTP·Kafka 소비자·봇·정산 스케줄러를 구성하지 않는 전용 관리 진입점이다.
 * 일반 앱의 컴포넌트 탐색 범위 밖에 두고 필요한 저장소와 업무만 명시적으로 불러온다.
 */
@Configuration(proxyBeanMethods=false)
@EnableAutoConfiguration(exclude={org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration.class,
    org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class,
    org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration.class})
@EntityScan(basePackageClasses=User.class)
@EnableJpaRepositories(basePackageClasses=UserRepository.class)
@Import({AuditService.class,OperatorBootstrapService.class})
public class OperatorAdminApplication {
    /** 운영자 최초 권한 부여에 필요한 비웹 실행 경계를 테스트와 실제 실행이 함께 사용한다. */
    public static SpringApplication application() {
        var app=new SpringApplication(OperatorAdminApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
        // 외부 설정이 웹 모드를 요청해도 웹 컨텍스트·서블릿 환경 자체를 생성하지 않는다.
        app.setEnvironment(new org.springframework.core.env.StandardEnvironment());
        app.setApplicationContextFactory(type->new org.springframework.context.annotation.AnnotationConfigApplicationContext());
        // 관리 프로세스가 운영 스키마를 자동 수정하거나 SQL 초기화 파일을 실행하지 못하게 강제한다.
        app.addInitializers(context->context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("operator-admin-boundary",Map.of(
            "spring.jpa.hibernate.ddl-auto","validate","spring.sql.init.mode","never","spring.main.web-application-type","none"))));
        return app;
    }
    public static void main(String[] args) {
        try(var context=application().run(args)) {
            var env=context.getEnvironment();
            context.getBean(OperatorBootstrapService.class).grant(
                Long.parseLong(env.getRequiredProperty("operator.bootstrap.user-id")),
                UUID.fromString(env.getRequiredProperty("operator.bootstrap.person-id")),
                UserRole.valueOf(env.getRequiredProperty("operator.bootstrap.role")),
                env.getRequiredProperty("operator.bootstrap.enrollment-token"));
        }
    }
}
