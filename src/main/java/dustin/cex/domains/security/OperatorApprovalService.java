package dustin.cex.domains.security;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.auth.service.PasswordService;

/** 불변 요청의 확인과 효과·감사를 하나의 트랜잭션에 묶는다. 별도 실행 우회 경로는 없다. */
@Service
public class OperatorApprovalService {
    private final UserRepository users;private final PasswordService passwords;
    private final AuditService audit;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public OperatorApprovalService(UserRepository users,PasswordService passwords,AuditService audit,
            JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.users=users;this.passwords=passwords;this.audit=audit;this.jdbc=jdbc;tx=new TransactionTemplate(manager);
    }
    public record Request(UUID id,long requesterId,UUID requesterPerson,long targetId,long targetVersion,
        boolean restricted,String reason,String payloadHash,Instant expiresAt,String status) {}
    public Request request(long actor,long target,long version,boolean restricted,String reason) {
        return attempt(actor,"RESTRICTION_REQUEST","user:"+target,()->tx.execute(status->{
            if(reason==null || reason.isBlank() || reason.length()>300) throw deny(HttpStatus.BAD_REQUEST,"사유가 필요합니다");
            var locked=lockUsers(actor,target); var requester=requireRole(locked.get(actor),UserRole.OPERATOR);
            var customer=locked.get(target);
            if(customer==null || customer.getRole()!=UserRole.CUSTOMER || !customer.isEnabled()
                    || customer.getVersion()!=version || customer.isTradingRestricted()==restricted)
                throw deny(HttpStatus.CONFLICT,"고객 상태가 요청 조건과 다릅니다");
            UUID id=UUID.randomUUID();Instant expiry=Instant.now().plusSeconds(600);
            // 길이와 타입이 정해진 필드 및 마지막 사유를 명시적 순서로 직렬화한다.
            String hash=hash(id+"|"+actor+"|"+requester.getOperatorPersonId()+"|"+target+"|"+version+"|"+restricted+"|"+expiry+"|"+reason);
            jdbc.update("INSERT INTO operator_requests(id,requester_id,requester_person,target_id,target_version,restricted,reason,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id,actor,requester.getOperatorPersonId(),target,version,restricted,reason,hash,java.sql.Timestamp.from(expiry));
            audit.record(actor,"RESTRICTION_REQUEST","request:"+id,"SUCCESS",hash);
            return read(id,false);
        }));
    }
    public Request decide(long actor,UUID id,String expectedHash,String password,boolean approve) {
        return attempt(actor,approve?"RESTRICTION_APPROVE":"RESTRICTION_REJECT","request:"+id,()->tx.execute(status->{
            Request request=read(id,true);
            var locked=lockUsers(actor,request.requesterId(),request.targetId());
            var approver=requireRole(locked.get(actor),UserRole.APPROVER);
            var requester=requireRole(locked.get(request.requesterId()),UserRole.OPERATOR);
            if(approver.getId().equals(requester.getId()) || approver.getOperatorPersonId().equals(request.requesterPerson())
                    || !requester.getOperatorPersonId().equals(request.requesterPerson()))
                throw deny(HttpStatus.FORBIDDEN,"요청자와 승인자는 다른 사람이어야 합니다");
            if(!"PENDING".equals(request.status()) || !request.expiresAt().isAfter(Instant.now())
                    || !request.payloadHash().equals(expectedHash)) throw deny(HttpStatus.CONFLICT,"승인 대상이 유효하지 않습니다");
            String target="request:"+id;
            if(audit.tooManyFailures(actor,"OPERATOR_REAUTHENTICATION",target))
                throw deny(HttpStatus.TOO_MANY_REQUESTS,"재인증 시도 한도를 초과했습니다");
            if(!passwords.verify(password,approver.getPasswordHash())) {
                // 요청 행 잠금을 풀기 전에 실패 기록을 확정한다. 업무 트랜잭션은 이후 롤백한다.
                audit.denied(actor,"OPERATOR_REAUTHENTICATION",target,"WRONG_PASSWORD");
                throw deny(HttpStatus.FORBIDDEN,"재인증 실패");
            }
            var customer=locked.get(request.targetId());
            if(approve) {
                if(customer==null || !customer.isEnabled() || customer.getRole()!=UserRole.CUSTOMER
                        || customer.getVersion()!=request.targetVersion() || customer.isTradingRestricted()==request.restricted())
                    throw deny(HttpStatus.CONFLICT,"고객 상태가 변경되었습니다");
                customer.setTradingRestricted(request.restricted());users.saveAndFlush(customer);
            }
            jdbc.update("UPDATE operator_requests SET status=?,approver_id=? WHERE id=?",approve?"APPROVED":"REJECTED",actor,id);
            audit.record(actor,approve?"RESTRICTION_APPROVE":"RESTRICTION_REJECT","request:"+id,"SUCCESS",request.payloadHash());
            return read(id,false);
        }));
    }
    public List<Request> page(int page) {
        if(page<0 || page>10000) throw deny(HttpStatus.BAD_REQUEST,"잘못된 요청 페이지");
        return jdbc.query("SELECT * FROM operator_requests ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?",(rs,row)->map(rs),page*50);
    }
    private Request read(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM operator_requests WHERE id=?"+(lock?" FOR UPDATE":""),(rs,row)->map(rs),id);
        if(rows.isEmpty()) throw deny(HttpStatus.NOT_FOUND,"승인 요청을 찾을 수 없습니다");return rows.getFirst();
    }
    private Request map(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Request(r.getObject("id",UUID.class),r.getLong("requester_id"),r.getObject("requester_person",UUID.class),
            r.getLong("target_id"),r.getLong("target_version"),r.getBoolean("restricted"),r.getString("reason"),
            r.getString("payload_hash"),r.getTimestamp("expires_at").toInstant(),r.getString("status"));
    }
    private Map<Long,User> lockUsers(long... ids) {
        var result=new HashMap<Long,User>();
        // 서로 다른 승인 요청이 같은 고객·직원을 잠가도 잠금 순서는 항상 같다.
        Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(user->result.put(id,user)));
        return result;
    }
    private User requireRole(User user,UserRole role) {
        if(user==null || !user.isAuthenticationAllowed() || user.getRole()!=role || user.getOperatorPersonId()==null)
            throw deny(HttpStatus.FORBIDDEN,"현재 운영 권한이 없습니다");return user;
    }
    private <T>T attempt(long actor,String action,String target,java.util.function.Supplier<T> work) {
        try {return work.get();}
        catch(ResponseStatusException rejected) {
            audit.denied(actor,action,target,"POLICY_DENIED_"+rejected.getStatusCode().value());throw rejected;
        }
    }
    private ResponseStatusException deny(HttpStatus status,String reason) {return new ResponseStatusException(status,reason);}
    private String hash(String text) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException failure) {throw new IllegalStateException(failure);}
    }
}
