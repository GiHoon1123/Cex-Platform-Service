package dustin.cex.domains.security;

import dustin.cex.domains.auth.repository.RefreshTokenRepository;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 고객은 다른 살아 있는 키로 최근 인증한 뒤 분실 키를 폐기한다. 마지막 키는 복구 업무로 처리한다. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class CustomerDeviceService {
    public record Device(String fingerprint,String label,Instant created,Instant lastUsed){}
    private final CustomerSessionPolicy sessions;
    private final CustomerMfaPolicy mfa;
    private final RefreshTokenRepository tokens;
    private final CustomerMfaKeyChanges changes;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcPublicKeyCredentialUserEntityRepository identities;
    private final WebAuthnCredentialRepository credentials;
    public CustomerDeviceService(CustomerSessionPolicy sessions,CustomerMfaPolicy mfa,RefreshTokenRepository tokens,
            CustomerMfaKeyChanges changes,AuditService audit,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.sessions=sessions;this.mfa=mfa;this.tokens=tokens;this.changes=changes;this.audit=audit;this.jdbc=jdbc;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        identities=new JdbcPublicKeyCredentialUserEntityRepository(jdbc);credentials=new WebAuthnCredentialRepository(jdbc);
    }
    public List<Device> own(long actor,UUID sid){return attempt(actor,()->tx.execute(t->{
        sessions.require(actor,sid);var identity=identities.findByUsername(Long.toString(actor));
        audit.record(actor,"CUSTOMER_MFA_DEVICES_READ","customer:"+actor,"SUCCESS","OWN_METADATA_ONLY");
        if(identity==null)return List.of();
        return credentials.findByUserId(identity.getId()).stream().map(k->new Device(OperatorMfaService.sha256Hex(k.getCredentialId().toBase64UrlString()),k.getLabel(),k.getCreated(),k.getLastUsed())).toList();
    }));}
    public void revoke(long actor,UUID sid,String fingerprint){attempt(actor,()->tx.execute(t->{
        sessions.require(actor,sid);var session=tokens.findBySessionId(sid).orElseThrow();
        if(!mfa.fresh(actor,session)||fingerprint==null||!fingerprint.matches("[0-9a-f]{64}"))throw denied();
        var identity=identities.findByUsername(Long.toString(actor));if(identity==null)throw denied();
        var keys=credentials.findByUserId(identity.getId());
        var lost=keys.stream().filter(k->OperatorMfaService.sha256Hex(k.getCredentialId().toBase64UrlString()).equals(fingerprint)).findFirst().orElseThrow(CustomerDeviceService::denied);
        // 실제 서명한 키가 현재 남아 있으며 폐기 대상과 다른지 확인한다. 시각만 있는 세션은 허용하지 않는다.
        if(keys.size()<2||session.getMfaCredentialFingerprint().equals(fingerprint))throw denied();
        credentials.delete(lost.getCredentialId());UUID event=changes.append(actor,sid,CustomerMfaKeyChanges.Kind.REVOKED,fingerprint);
        tokens.revokeAllByUserId(actor,LocalDateTime.now());jdbc.update("DELETE FROM customer_mfa_challenges WHERE user_id=?",actor);
        audit.record(actor,"CUSTOMER_MFA_KEY_REVOKED","customer-mfa:"+event,"SUCCESS","ALL_SESSIONS_REVOKED");
        return null;
    }));}
    private <T>T attempt(long actor,java.util.function.Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_MFA_DEVICE","customer:"+actor,"POLICY_DENIED");throw e;}}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"다른 고객 패스키로 다시 확인하세요");}
}
