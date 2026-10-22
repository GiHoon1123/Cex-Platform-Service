package dustin.cex.domains.security;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 필수 감사 기록 실패를 삼키지 않는다. 중요 업무와 함께 롤백시키는 보안 경계다. */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    public AuditService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void schema() throws Exception {
        jdbc.execute(new ClassPathResource("security/schema.sql").getContentAsString(StandardCharsets.UTF_8));
    }
    @Transactional
    public void record(Long actor,String action,String target,String outcome,String detail) {
        // 호출부는 비밀정보와 원본 HTTP 본문을 넘기지 않는다. 길이 초과도 잘라 숨기지 않는다.
        if(action.length()>64 || target.length()>160 || detail.length()>500) throw new IllegalArgumentException("감사 필드 길이 초과");
        var actors=actor==null ? List.<Map<String,Object>>of() : jdbc.queryForList("SELECT role,operator_person_id FROM users WHERE id=?",actor);
        var snapshot=actors.isEmpty() ? Map.<String,Object>of() : actors.getFirst();
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        String source=attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
            ? servlet.getRequest().getRemoteAddr() : "system";
        // 전달 헤더를 신뢰하지 않는다. 프록시를 도입하면 신뢰할 프록시와 주소 해석을 별도로 정한다.
        jdbc.update("INSERT INTO security_audit(id,correlation_id,actor_id,actor_role,actor_person,source_address,action,target,outcome,detail) VALUES(?,?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),correlation(),actor,snapshot.get("role"),snapshot.get("operator_person_id"),source,action,target,outcome,detail);
    }
    private UUID correlation() {
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                && servlet.getRequest().getAttribute("requestId") instanceof UUID id) return id;
        return UUID.randomUUID();
    }
    // 업무 트랜잭션이 거부되어 롤백되더라도 거부 시도는 별도 기록으로 남긴다.
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void denied(Long actor,String action,String target,String reason) { record(actor,action,target,"DENIED",reason); }
    public boolean tooManyFailures(long actor,String action,String target) {
        // 고정된 실패 창은 자동 재시도의 무한 비밀번호 추측을 제한한다.
        return jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action=? AND target=? AND outcome='DENIED' AND detail='WRONG_PASSWORD' AND occurred_at>clock_timestamp()-interval '15 minutes'",Long.class,actor,action,target)>=5;
    }
    public List<Map<String,Object>> page(int page) {
        if(page<0 || page>10000) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"잘못된 감사 페이지");
        return jdbc.queryForList("SELECT * FROM security_audit ORDER BY occurred_at DESC,id DESC LIMIT 50 OFFSET ?",page*50);
    }
}
