package dustin.cex.domains.security;

import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.JwtService;

/**
 * 정상 운영의 역할 부여·변경을 담당한다. 요청자·승인자·대상자는 서로 다른 실제 인물이어야 한다.
 * 초기 관리자 도구와 달리 단독 부여는 없고, 고정한 요청에 대한 별도 보안키 서명을 요구한다.
 */
@Service
public class OperatorRoleService {
    /** 해시 입력의 필드와 순서를 타입으로 고정한다. 문자열 구분자가 사유에 들어가도 충돌하지 않는다. */
    public record Payload(UUID id,long requesterId,UUID requesterPerson,long requesterVersion,long targetId,UUID targetPerson,long targetVersion,
        UserRole previousRole,UserRole requestedRole,Instant accessUntil,String reason,String identityEvidence,
        String notificationEvidence,Instant expiresAt) {}
    public record Request(Payload payload,String payloadHash,String status) {}
    public record Confirmation(Request request,boolean approve,OperatorMfaService.Options authentication) {}
    public record Decision(Request request,String enrollmentToken) {
        @Override public String toString() {return "Decision[등록 비밀 생략]";}
    }
    public record Target(long id,long version,UserRole role,boolean enabled,UUID personId,Instant accessUntil) {}
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final OperatorSessionPolicy policy;
    private final OperatorMfaService mfa;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final JwtService jwt;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public OperatorRoleService(UserRepository users,RefreshTokenRepository tokens,OperatorSessionPolicy policy,
            OperatorMfaService mfa,AuditService audit,JdbcTemplate jdbc,JwtService jwt,ObjectMapper json,PlatformTransactionManager manager) {
        this.users=users;this.tokens=tokens;this.policy=policy;this.mfa=mfa;this.audit=audit;this.jdbc=jdbc;
        this.jwt=jwt;this.json=json;tx=new TransactionTemplate(manager);
    }
    public Request request(long actor,UUID sid,long target,long version,UUID person,UserRole role,Instant until,
            String reason,String identityEvidence,String notificationEvidence) {
        return attempt(actor,"ROLE_REQUEST",()->tx.execute(status->{
            checkInput(person,role,until,reason,identityEvidence,notificationEvidence);
            var locked=lockUsers(actor,target);
            var maker=locked.get(actor);policy.requireFresh(maker,sid,UserRole.OPERATOR);
            var owner=eligibleTarget(locked.get(target),version,person,role);
            different(maker,owner.getId(),person);
            // PostgreSQL의 마이크로초 정밀도로 먼저 고정해, 응답·저장·해시의 시각이 같게 한다.
            var payload=new Payload(UUID.randomUUID(),actor,maker.getOperatorPersonId(),maker.getVersion(),target,person,version,owner.getRole(),role,
                until.truncatedTo(ChronoUnit.MICROS),reason,identityEvidence,notificationEvidence,
                Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS));
            String hash=OperatorMfaService.sha256Hex(json.writeValueAsString(payload));
            jdbc.update("""
                INSERT INTO operator_role_requests(id,requester_id,requester_person,requester_version,target_id,target_person,target_version,
                 previous_role,requested_role,access_until,reason,identity_evidence,notification_evidence,payload_hash,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,payload.id(),actor,payload.requesterPerson(),payload.requesterVersion(),target,person,version,payload.previousRole().name(),role.name(),
                Timestamp.from(payload.accessUntil()),reason,identityEvidence,notificationEvidence,hash,Timestamp.from(payload.expiresAt()));
            mfa.bindApproval(OperatorMfaService.ApprovalKind.ROLE,payload.id(),actor,maker.getVersion(),hash,payload.expiresAt());
            audit.record(actor,"ROLE_REQUEST","request:"+payload.id(),"SUCCESS",hash);
            return read(payload.id(),false);
        }));
    }
    /** 화면이 표시할 내용도 DB의 불변 요청에서 반환한다. 클라이언트가 보낸 업무 내용을 신뢰하지 않는다. */
    public Confirmation options(long actor,UUID sid,UUID id,String expectedHash,boolean approve) {
        return attempt(actor,"ROLE_OPTIONS",()->tx.execute(status->{
            var request=read(id,true);
            checkDecision(actor,sid,request,expectedHash);
            return new Confirmation(request,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.ROLE,id,request.payloadHash(),approve));
        }));
    }
    public Decision decide(long actor,UUID sid,UUID id,String expectedHash,boolean approve,OperatorMfaService.AuthorizationProof proof) {
        return attempt(actor,"ROLE_DECIDE",()->tx.execute(status->{
            var request=read(id,true);
            var owner=checkDecision(actor,sid,request,expectedHash);
            // 인증을 분리된 성공 토큰으로 바꾸지 않는다. 최종 효과 직전에 같은 트랜잭션에서 검증·소비한다.
            mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.ROLE,id,request.payloadHash(),approve,proof);
            String raw=null;
            if(approve) {
                var p=request.payload();checkUntil(p.accessUntil());
                owner.setRole(p.requestedRole());owner.setOperatorPersonId(p.targetPerson());
                owner.setOperatorAccessExpiresAt(p.accessUntil());owner.setOperatorLastReviewedAt(Instant.now());
                users.saveAndFlush(owner);
                tokens.revokeAllByUserId(owner.getId(),LocalDateTime.now());
                jdbc.update("DELETE FROM operator_mfa_challenges WHERE user_id=?",owner.getId());
                jdbc.update("DELETE FROM operator_authorization_challenges WHERE user_id=?",owner.getId());
                jdbc.update("UPDATE operator_enrollment SET consumed=true WHERE user_id=?",owner.getId());
                if(p.previousRole()==UserRole.CUSTOMER) {
                    // 신규 직원은 비밀번호 로그인→별도 등록 비밀→키 등록→서명 인증을 모두 거친다.
                    // 승인 응답 유실 시 원문 비밀은 재조회하지 않는다. 기존 복구 승인 절차로 새 권한을 발급한다.
                    raw=jwt.generateRefreshToken();
                    jdbc.update("""
                        INSERT INTO operator_enrollment(user_id,token_hash,expires_at,consumed)
                        VALUES(?,?,clock_timestamp()+interval '10 minutes',false)
                        """,owner.getId(),OperatorMfaService.sha256Hex(raw));
                }
                // 역할만 바뀐 기존 직원은 이미 확인된 키를 유지하지만 모든 세션은 새로 인증해야 한다.
            }
            jdbc.update("UPDATE operator_role_requests SET status=?,approver_id=? WHERE id=?",approve?"APPROVED":"REJECTED",actor,id);
            audit.record(actor,"ROLE_DECIDE","request:"+id,"SUCCESS",(approve?"APPROVED:":"REJECTED:")+request.payloadHash());
            return new Decision(read(id,false),raw);
        }));
    }
    private User checkDecision(long actor,UUID sid,Request request,String hash) {
        var p=request.payload();var locked=lockUsers(actor,p.requesterId(),p.targetId());
        var checker=locked.get(actor);policy.requireFresh(checker,sid,UserRole.APPROVER);
        var maker=locked.get(p.requesterId());
        if(maker==null || !maker.isAuthenticationAllowed() || maker.getRole()!=UserRole.OPERATOR
                || !p.requesterPerson().equals(maker.getOperatorPersonId()) || maker.getVersion()!=p.requesterVersion()) throw denied(HttpStatus.FORBIDDEN);
        different(checker,p.requesterId(),p.requesterPerson());
        different(checker,p.targetId(),p.targetPerson());different(maker,p.targetId(),p.targetPerson());
        if(!request.status().equals("PENDING") || !p.expiresAt().isAfter(Instant.now()) || !request.payloadHash().equals(hash))
            throw denied(HttpStatus.CONFLICT);
        var owner=eligibleTarget(locked.get(p.targetId()),p.targetVersion(),p.targetPerson(),p.requestedRole());
        if(owner.getRole()!=p.previousRole()) throw denied(HttpStatus.CONFLICT);
        return owner;
    }
    private User eligibleTarget(User owner,long version,UUID person,UserRole requested) {
        if(owner==null || !owner.isEnabled() || owner.getVersion()!=version || owner.getOperatorRecoveryRequestId()!=null
                || owner.getRole()==requested) throw denied(HttpStatus.CONFLICT);
        if(owner.getRole()==UserRole.CUSTOMER) {
            // 주문 접수도 같은 사용자 행을 먼저 잠근다. 고객 주문과 역할 전환의 선후가 한 곳에서 정해진다.
            // 거래 이력이 있는 고객을 직원으로 바꾸거나 금융 기록을 지워 전환하는 경로는 제공하지 않는다.
            if(owner.isTradingRestricted() || owner.getOperatorPersonId()!=null || owner.getOperatorAccessExpiresAt()!=null
                    || jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id=?",Long.class,owner.getId())!=0
                    || jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE owner_id=?",Long.class,owner.getId())!=0
                    || jdbc.queryForObject("SELECT count(*) FROM user_balances WHERE user_id=? AND (available<>0 OR locked<>0)",Long.class,owner.getId())!=0
                    || jdbc.queryForObject("SELECT count(*) FROM operator_enrollment WHERE user_id=?",Long.class,owner.getId())!=0)
                throw denied(HttpStatus.CONFLICT);
        } else if(!person.equals(owner.getOperatorPersonId())) {
            // 사람 식별자 수정은 역할 변경으로 위장할 수 없다. 외부 신원 절차의 별도 검토가 필요하다.
            throw denied(HttpStatus.CONFLICT);
        }
        return owner;
    }
    private void checkInput(UUID person,UserRole role,Instant until,String reason,String identity,String notification) {
        if(person==null || role==null || role==UserRole.CUSTOMER || reason==null || reason.isBlank() || reason.length()>200
                || identity==null || !identity.matches("[A-Za-z0-9_-]{3,100}")
                || notification==null || !notification.matches("[A-Za-z0-9_-]{3,100}")) throw denied(HttpStatus.BAD_REQUEST);
        checkUntil(until);
    }
    private void checkUntil(Instant until) {
        if(until==null || !until.isAfter(Instant.now()) || until.isAfter(Instant.now().plusSeconds(30*86400))) throw denied(HttpStatus.BAD_REQUEST);
    }
    private void different(User user,long id,UUID person) {
        if(user.getId()==id || user.getOperatorPersonId().equals(person)) throw denied(HttpStatus.FORBIDDEN);
    }
    private Map<Long,User> lockUsers(long... ids) {
        var locked=new HashMap<Long,User>();
        Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(user->locked.put(id,user)));return locked;
    }
    public Target target(long id) {
        var user=users.findById(id).orElseThrow(()->denied(HttpStatus.NOT_FOUND));
        return new Target(id,user.getVersion(),user.getRole(),user.isEnabled(),user.getOperatorPersonId(),user.getOperatorAccessExpiresAt());
    }
    public List<Request> page(int page) {
        if(page<0 || page>10000) throw denied(HttpStatus.BAD_REQUEST);
        return jdbc.query("SELECT * FROM operator_role_requests ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?",(r,n)->map(r),page*50);
    }
    private Request read(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM operator_role_requests WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->map(r),id);
        if(rows.isEmpty()) throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();
    }
    private Request map(ResultSet r) throws SQLException {
        return new Request(new Payload(r.getObject("id",UUID.class),r.getLong("requester_id"),r.getObject("requester_person",UUID.class),
            r.getLong("requester_version"),r.getLong("target_id"),r.getObject("target_person",UUID.class),r.getLong("target_version"),UserRole.valueOf(r.getString("previous_role")),
            UserRole.valueOf(r.getString("requested_role")),r.getTimestamp("access_until").toInstant(),r.getString("reason"),
            r.getString("identity_evidence"),r.getString("notification_evidence"),r.getTimestamp("expires_at").toInstant()),r.getString("payload_hash"),r.getString("status"));
    }
    private <T>T attempt(long actor,String action,Supplier<T> work) {
        try {return work.get();}
        catch(ResponseStatusException e) {audit.denied(actor,action,"operator-role","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"역할 변경의 인증·대상·증적을 확인하세요");}
}
