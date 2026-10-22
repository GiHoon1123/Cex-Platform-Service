package dustin.cex.domains.security;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.JwtService;

/**
 * 복구와 권한 재검토의 두 가지 업무만 구현한다. 요청 자료를 임의 명령으로 실행하지 않는다.
 * 권한을 여는 일은 독립된 두 인물의 확인이 필요하고, 침해 확산 차단은 즉시 수행한다.
 */
@Service
public class OperatorLifecycleService {
    public enum Kind { RECOVER_MFA, REVIEW_ACCESS }
    public record Request(UUID id,Kind kind,long requesterId,UUID requesterPerson,long targetId,UUID targetPerson,
        long targetVersion,Instant accessUntil,String reason,String identityEvidence,String notificationEvidence,
        String payloadHash,Instant expiresAt,String status) {}
    public record Decision(Request request,String enrollmentToken) {
        @Override public String toString() {return "Decision[등록 비밀 생략]";}
    }
    public record OperatorStatus(long id,long version,UserRole role,boolean enabled,Instant accessUntil,Instant lastReviewedAt,boolean recoveryPending) {}
    private final UserRepository users;private final RefreshTokenRepository tokens;private final OperatorSessionPolicy policy;
    private final OperatorMfaService mfa;private final AuditService audit;private final JdbcTemplate jdbc;private final JwtService jwt;private final TransactionTemplate tx;
    public OperatorLifecycleService(UserRepository users,RefreshTokenRepository tokens,OperatorSessionPolicy policy,
            AuditService audit,JdbcTemplate jdbc,JwtService jwt,OperatorMfaService mfa,PlatformTransactionManager manager) {
        this.mfa=mfa;this.users=users;this.tokens=tokens;this.policy=policy;this.audit=audit;this.jdbc=jdbc;this.jwt=jwt;tx=new TransactionTemplate(manager);
    }
    public Request request(long actor,UUID sid,long target,long version,Kind kind,Instant accessUntil,
            String reason,String identityEvidence,String notificationEvidence) {
        return attempt(actor,"OPERATOR_SECURITY_REQUEST",()->tx.execute(status->{
            checkText(reason,identityEvidence,notificationEvidence);
            if(kind==null) throw denied(HttpStatus.BAD_REQUEST);
            if(kind==Kind.REVIEW_ACCESS) checkUntil(accessUntil);
            else if(accessUntil!=null) throw denied(HttpStatus.BAD_REQUEST);
            var locked=lockUsers(actor,target);var requester=locked.get(actor);var owner=target(locked.get(target),version);
            policy.requireFresh(requester,sid,UserRole.OPERATOR);different(requester,owner);
            UUID id=UUID.randomUUID();
            if(kind==Kind.RECOVER_MFA) {
                if(owner.getOperatorAccessExpiresAt()==null || !owner.getOperatorAccessExpiresAt().isAfter(Instant.now())) throw denied(HttpStatus.CONFLICT);
                // 분실 신고 즉시 모든 세션·옵션을 회수한다. 거절·만료로 옛 기기의 접근을 자동 복원하지 않는다.
                owner.setOperatorRecoveryRequestId(id);users.saveAndFlush(owner);invalidate(target);
            }
            Instant expires=Instant.now().plusSeconds(600);
            String hash=OperatorMfaService.sha256Hex(id+"|"+kind+"|"+actor+"|"+requester.getOperatorPersonId()+"|"+target+"|"+owner.getOperatorPersonId()
                +"|"+owner.getVersion()+"|"+accessUntil+"|"+identityEvidence+"|"+notificationEvidence+"|"+expires+"|"+reason);
            jdbc.update("""
                INSERT INTO operator_security_requests(id,kind,requester_id,requester_person,target_id,target_person,target_version,
                 access_until,reason,identity_evidence,notification_evidence,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,kind.name(),actor,requester.getOperatorPersonId(),target,owner.getOperatorPersonId(),owner.getVersion(),
                timestamp(accessUntil),reason,identityEvidence,notificationEvidence,hash,Timestamp.from(expires));
            mfa.bindApproval(approvalKind(kind),id,actor,requester.getVersion(),hash,expires);
            audit.record(actor,"OPERATOR_SECURITY_REQUEST","request:"+id,"SUCCESS",kind+":"+hash);
            return read(id,false);
        }));
    }
    public Decision decide(long actor,UUID sid,UUID id,String expectedHash,boolean approve,OperatorMfaService.AuthorizationProof proof) {
        return attempt(actor,"OPERATOR_SECURITY_DECIDE",()->tx.execute(status->{
            var request=read(id,true);
            var owner=checkDecision(actor,sid,request,expectedHash);
            mfa.authorizeApproval(actor,sid,approvalKind(request.kind()),id,request.payloadHash(),approve,proof);
            String raw=null;
            if(approve) {
                if(request.kind()==Kind.RECOVER_MFA) {
                    // 이전 키는 폐기하고 단기 재등록 권한만 준다. 인증된 운영 세션은 발급하지 않는다.
                    int removed=jdbc.update("DELETE FROM user_credentials WHERE user_entity_user_id IN (SELECT id FROM user_entities WHERE name=?)",Long.toString(owner.getId()));
                    invalidate(owner.getId());raw=jwt.generateRefreshToken();
                    jdbc.update("""
                        INSERT INTO operator_enrollment(user_id,token_hash,expires_at,consumed) VALUES(?,?,clock_timestamp()+interval '10 minutes',false)
                        ON CONFLICT(user_id) DO UPDATE SET token_hash=EXCLUDED.token_hash,expires_at=EXCLUDED.expires_at,consumed=false
                        """,owner.getId(),OperatorMfaService.sha256Hex(raw));
                    owner.setOperatorRecoveryRequestId(null);
                    audit.record(actor,"MFA_RECOVERY_REVOKE","user:"+owner.getId(),"SUCCESS","request:"+id+":keys="+removed);
                } else {
                    checkUntil(request.accessUntil());owner.setOperatorAccessExpiresAt(request.accessUntil());
                    owner.setOperatorLastReviewedAt(Instant.now());
                    // 재검토로 연장해도 이전 세션을 되살리지 않는다. 다시 로그인·MFA 해야 한다.
                    invalidate(owner.getId());
                }
                users.saveAndFlush(owner);
            }
            jdbc.update("UPDATE operator_security_requests SET status=?,approver_id=? WHERE id=?",approve?"APPROVED":"REJECTED",actor,id);
            audit.record(actor,"OPERATOR_SECURITY_DECIDE","request:"+id,"SUCCESS",(approve?"APPROVED:":"REJECTED:")+request.payloadHash());
            return new Decision(read(id,false),raw);
        }));
    }
    public record Confirmation(Request request,boolean approve,OperatorMfaService.Options authentication) {}
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve) {
        return attempt(actor,"OPERATOR_SECURITY_OPTIONS",()->tx.execute(status->{
            var request=read(id,true);checkDecision(actor,sid,request,hash);
            return new Confirmation(request,approve,mfa.approvalOptions(actor,sid,approvalKind(request.kind()),id,request.payloadHash(),approve));
        }));
    }
    private User checkDecision(long actor,UUID sid,Request request,String expectedHash) {
        var locked=lockUsers(actor,request.requesterId(),request.targetId());var approver=locked.get(actor);
        policy.requireFresh(approver,sid,UserRole.APPROVER);
        var requester=locked.get(request.requesterId());var owner=target(locked.get(request.targetId()),request.targetVersion());
        if(requester==null || !requester.isAuthenticationAllowed() || requester.getRole()!=UserRole.OPERATOR
                || !request.requesterPerson().equals(requester.getOperatorPersonId())
                || !request.targetPerson().equals(owner.getOperatorPersonId())) throw denied(HttpStatus.FORBIDDEN);
        different(approver,requester);different(approver,owner);different(requester,owner);
        if(!request.status().equals("PENDING") || !request.expiresAt().isAfter(Instant.now())
                || !request.payloadHash().equals(expectedHash)) throw denied(HttpStatus.CONFLICT);
        if(request.kind()==Kind.RECOVER_MFA && !request.id().equals(owner.getOperatorRecoveryRequestId())) throw denied(HttpStatus.CONFLICT);
        return owner;
    }
    private OperatorMfaService.ApprovalKind approvalKind(Kind kind) {
        return kind==Kind.RECOVER_MFA?OperatorMfaService.ApprovalKind.MFA_RECOVERY:OperatorMfaService.ApprovalKind.ACCESS_REVIEW;
    }
    public void revokeAccess(long actor,UUID sid,long target,long version,String reason,String notificationEvidence) {
        attempt(actor,"OPERATOR_ACCESS_REVOKE",()->tx.execute(status->{
            checkText(reason,"REVOCATION",notificationEvidence);
            var locked=lockUsers(actor,target);var requester=locked.get(actor);var owner=target(locked.get(target),version);
            policy.requireFresh(requester,sid,UserRole.OPERATOR,UserRole.APPROVER);different(requester,owner);
            // 퇴직·침해 통지는 승인 대기로 노출을 연장하지 않는다. 비활성 계정을 재활성화하는 API는 없다.
            owner.setEnabled(false);owner.setOperatorRecoveryRequestId(null);users.saveAndFlush(owner);invalidate(target);
            int removed=jdbc.update("DELETE FROM user_credentials WHERE user_entity_user_id IN (SELECT id FROM user_entities WHERE name=?)",Long.toString(target));
            audit.record(actor,"OPERATOR_ACCESS_REVOKE","user:"+target,"SUCCESS",notificationEvidence+":keys="+removed+":"+reason);
            return null;
        }));
    }
    public OperatorStatus operator(long target) {
        var user=users.findById(target).orElseThrow(()->denied(HttpStatus.NOT_FOUND));
        if(user.getRole()==UserRole.CUSTOMER) throw denied(HttpStatus.NOT_FOUND);
        return new OperatorStatus(user.getId(),user.getVersion(),user.getRole(),user.isEnabled(),user.getOperatorAccessExpiresAt(),user.getOperatorLastReviewedAt(),user.getOperatorRecoveryRequestId()!=null);
    }
    public List<Request> page(int page) {
        if(page<0 || page>10000) throw denied(HttpStatus.BAD_REQUEST);
        return jdbc.query("SELECT * FROM operator_security_requests ORDER BY created_at DESC,id DESC LIMIT 50 OFFSET ?",(r,n)->map(r),page*50);
    }
    private void invalidate(long target) {
        tokens.revokeAllByUserId(target,LocalDateTime.now());
        jdbc.update("DELETE FROM operator_mfa_challenges WHERE user_id=?",target);
        jdbc.update("DELETE FROM operator_authorization_challenges WHERE user_id=?",target);
        jdbc.update("UPDATE operator_enrollment SET consumed=true WHERE user_id=?",target);
    }
    private Map<Long,User> lockUsers(long... ids) {
        Map<Long,User> result=new HashMap<>();
        // 모든 보안 업무가 같은 사용자 잠금 순서를 지켜 서로 다른 요청 사이의 교착을 피한다.
        Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(u->result.put(id,u)));return result;
    }
    private User target(User user,long version) {
        if(user==null || user.getRole()==UserRole.CUSTOMER || !user.isEnabled() || user.getOperatorPersonId()==null
                || user.getVersion()!=version) throw denied(HttpStatus.CONFLICT);return user;
    }
    private void different(User first,User second) {
        if(first.getId().equals(second.getId()) || first.getOperatorPersonId().equals(second.getOperatorPersonId())) throw denied(HttpStatus.FORBIDDEN);
    }
    private void checkUntil(Instant until) {
        if(until==null || !until.isAfter(Instant.now()) || until.isAfter(Instant.now().plusSeconds(30*86400))) throw denied(HttpStatus.BAD_REQUEST);
    }
    private void checkText(String reason,String identity,String notification) {
        if(reason==null || reason.isBlank() || reason.length()>200 || identity==null || notification==null
                || !identity.matches("[A-Za-z0-9_-]{3,100}") || !notification.matches("[A-Za-z0-9_-]{3,100}")) throw denied(HttpStatus.BAD_REQUEST);
    }
    private Request read(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM operator_security_requests WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->map(r),id);
        if(rows.isEmpty()) throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();
    }
    private Request map(ResultSet r) throws SQLException {
        var until=r.getTimestamp("access_until");
        return new Request(r.getObject("id",UUID.class),Kind.valueOf(r.getString("kind")),r.getLong("requester_id"),r.getObject("requester_person",UUID.class),
            r.getLong("target_id"),r.getObject("target_person",UUID.class),r.getLong("target_version"),until==null?null:until.toInstant(),r.getString("reason"),
            r.getString("identity_evidence"),r.getString("notification_evidence"),r.getString("payload_hash"),r.getTimestamp("expires_at").toInstant(),r.getString("status"));
    }
    private Timestamp timestamp(Instant value) {return value==null?null:Timestamp.from(value);}
    private <T>T attempt(long actor,String action,Supplier<T> work) {
        try {return work.get();} catch(ResponseStatusException e) {audit.denied(actor,action,"operator-security","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"운영 보안 요청의 인증·상태·증적을 확인하세요");}
}
