package dustin.cex.domains.security;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 일반 서버의 스키마 준비와 관리 명령을 분리해 관리 명령이 DDL을 실행하지 않게 한다. */
@Component
public class SecuritySchemaInitializer {
    private final AuditService audit;
    public SecuritySchemaInitializer(AuditService audit) {this.audit=audit;}
    @PostConstruct public void initialize() throws Exception {audit.schema();}
}
