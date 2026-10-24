package dustin.cex.domains.security;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.*;

/** 예비 키로 새로 인증한 사람이 분실 키를 폐기한다. 마지막 키 제거는 복구 승인 업무로 보낸다. */
@Service
public class OperatorDeviceService {
    private final UserRepository users;private final RefreshTokenRepository tokens;
    private final OperatorSessionPolicy policy;private final AuditService audit;private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;private final JdbcPublicKeyCredentialUserEntityRepository identities;
    private final WebAuthnCredentialRepository credentials;
    public OperatorDeviceService(UserRepository users,RefreshTokenRepository tokens,OperatorSessionPolicy policy,
            AuditService audit,JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.users=users;this.tokens=tokens;this.policy=policy;this.audit=audit;this.jdbc=jdbc;tx=new TransactionTemplate(manager);
        identities=new JdbcPublicKeyCredentialUserEntityRepository(jdbc);credentials=new WebAuthnCredentialRepository(jdbc);
    }
    public record Device(String fingerprint,String label,Instant created,Instant lastUsed) {}
    public List<Device> devices(long actor,UUID sid) {
        return tx.execute(status->{
            var user=users.lockById(actor).orElseThrow();policy.requireFresh(user,sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);
            var identity=identities.findByUsername(Long.toString(actor));
            if(identity==null) return List.of();
            audit.record(actor,"MFA_DEVICE_READ","user:"+actor,"SUCCESS","OWN_DEVICES");
            return credentials.findByUserId(identity.getId()).stream().map(k->new Device(OperatorMfaService.sha256Hex(k.getCredentialId().toBase64UrlString()),k.getLabel(),k.getCreated(),k.getLastUsed())).toList();
        });
    }
    public void revoke(long actor,UUID sid,String fingerprint) {
        try {
            tx.executeWithoutResult(status->{
                var user=users.lockById(actor).orElseThrow();var session=policy.requireFresh(user,sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);
                var identity=identities.findByUsername(Long.toString(actor));
                if(identity==null || fingerprint==null || !fingerprint.matches("[0-9a-f]{64}")) throw denied();
                var keys=credentials.findByUserId(identity.getId());
                var lost=keys.stream().filter(k->OperatorMfaService.sha256Hex(k.getCredentialId().toBase64UrlString()).equals(fingerprint)).findFirst().orElseThrow(OperatorDeviceService::denied);
                // 토큰에 결합된 실제 서명 키가 살아 있으며 폐기 대상과 다른지 확인한다.
                boolean differentVerifiedKey=keys.stream().anyMatch(k->{
                    String key=OperatorMfaService.sha256Hex(k.getCredentialId().toBase64UrlString());
                    return key.equals(session.getMfaCredentialFingerprint()) && !key.equals(fingerprint);
                });
                if(keys.size()<2 || !differentVerifiedKey) throw denied();
                credentials.delete(lost.getCredentialId());tokens.revokeAllByUserId(actor,LocalDateTime.now());
                jdbc.update("DELETE FROM operator_mfa_challenges WHERE user_id=?",actor);
                jdbc.update("UPDATE operator_enrollment SET consumed=true WHERE user_id=?",actor);
                audit.record(actor,"MFA_DEVICE_REVOKE","user:"+actor,"SUCCESS",fingerprint);
            });
        } catch(ResponseStatusException e) {audit.denied(actor,"MFA_DEVICE_REVOKE","user:"+actor,"POLICY_DENIED");throw e;}
    }
    private static ResponseStatusException denied() {return new ResponseStatusException(HttpStatus.FORBIDDEN,"다른 보안키로 인증한 뒤 다시 시도하세요");}
}
