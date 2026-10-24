package dustin.cex.domains.security;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.PasswordService;
import dustin.cex.domains.mail.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 미리 보관한 일회 복구 코드와 현재 비밀번호로 분실한 패스키를 교체한다.
 * 복구 전용 세션을 거래 권한으로 올리지 않는다. 새 키의 검증·기존 키 폐기·세션 회수·통지를 함께 확정한다.
 */
@Service
@Transactional(propagation=Propagation.NEVER)
public class CustomerRecoveryService {
    public record Options(UUID challengeId,String purpose,JsonNode publicKey) {}
    public record Proof(UUID challengeId,JsonNode publicKey) {
        @Override public String toString(){return "Proof[인증 자료 생략]";}
    }
    public record Code(String recoveryCode,boolean requiresLogin) {
        @Override public String toString(){return "Code[복구 코드 생략]";}
    }
    public record Status(boolean configured) {}
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordService passwords;
    private final CustomerSessionPolicy sessions;
    private final CustomerMfaPolicy mfa;
    private final CustomerMfaKeyChanges changes;
    private final CustomerMailRecipientPolicy recipients;
    private final CustomerMailOutbox outbox;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WebAuthnVerifier verifier;
    private final WebAuthnCredentialRepository credentials;
    private final SecureRandom random=new SecureRandom();
    private final JsonMapper json=JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    public CustomerRecoveryService(UserRepository users,RefreshTokenRepository tokens,PasswordService passwords,
            CustomerSessionPolicy sessions,CustomerMfaPolicy mfa,CustomerMfaKeyChanges changes,
            CustomerMailRecipientPolicy recipients,CustomerMailOutbox outbox,AuditService audit,JdbcTemplate jdbc,
            PlatformTransactionManager manager,@Value("${customer.webauthn.rp-id:localhost}")String rp,
            @Value("${customer.webauthn.origin:http://localhost:3003}")String origin){
        this.users=users;this.tokens=tokens;this.passwords=passwords;this.sessions=sessions;this.mfa=mfa;
        this.changes=changes;this.recipients=recipients;this.outbox=outbox;this.audit=audit;this.jdbc=jdbc;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        verifier=new WebAuthnVerifier(jdbc,rp,origin,"CEX 고객 인증");credentials=new WebAuthnCredentialRepository(jdbc);
    }
    /** 등록 여부만 제공한다. 저장한 해시나 이전에 반환한 코드를 다시 읽어 주는 API는 없다. */
    public Status status(long actor,UUID sid){return operation(actor,()->{
        sessions.require(actor,sid);audit.record(actor,"CUSTOMER_RECOVERY_READ","customer:"+actor,"SUCCESS","CONFIGURATION_ONLY");
        return new Status(current(actor)!=null);
    });}
    /** 현재 키의 새 서명을 받아 코드 발급/교체라는 한 목적에만 결합한다. 로그인 서명은 재사용할 수 없다. */
    public Options codeOptions(long actor,UUID sid,String currentPassword){return operation(actor,()->{
        var user=strong(actor,sid);if(!passwords.verify(currentPassword,user.getPasswordHash()))throw denied();
        return challenge(actor,sid,"CODE_REPLACE",null,verifier.request(actor));
    });}
    public Code replaceCode(long actor,UUID sid,Proof proof){return operation(actor,()->{
        var user=strong(actor,sid);var stored=consume(actor,sid,proof,"CODE_REPLACE");
        verifier.verifyAssertion(actor,stored.options(),proof.publicKey());
        return issue(user,sid,"CODE_REPLACED",null,false,UUID.randomUUID());
    });}
    /** 비밀번호 단계의 유효 세션만 받으며 복구 코드 단독으로 계정이나 로그인 세션을 발급하지 않는다. */
    public Options recoveryOptions(long actor,UUID sid,String currentPassword,String recoveryCode){return operation(actor,()->{
        var user=pending(actor,sid);
        if(!passwords.verify(currentPassword,user.getPasswordHash())||recoveryCode==null
                ||!recoveryCode.matches("[A-Za-z0-9_-]{43}"))throw denied();
        UUID code=current(actor);if(code==null)throw denied();
        String hash=jdbc.queryForObject("SELECT code_hash FROM customer_recovery_codes WHERE id=? AND user_id=?",String.class,code,actor);
        if(!passwords.verify(recoveryCode,hash))throw denied();
        // 이 단계에서는 코드를 소비하거나 기존 키를 지우지 않는다. 실제 새 키 검증까지 성공해야 교체한다.
        return challenge(actor,sid,"MFA_RECOVER",code,verifier.creation(actor));
    });}
    public Code recover(long actor,UUID sid,Proof proof){return operation(actor,()->{
        var user=pending(actor,sid);var stored=consume(actor,sid,proof,"MFA_RECOVER");
        if(stored.code()==null||!stored.code().equals(current(actor)))throw denied();
        var old=verifier.keys(actor);var added=verifier.register(stored.options(),proof.publicKey());
        // 이미 등록된 자격을 이름만 바꿔 재등록하는 요청도 복구로 인정하지 않는다.
        if(old.stream().anyMatch(k->k.getCredentialId().equals(added.getCredentialId())))throw denied();
        UUID event=UUID.randomUUID();
        for(var key:old){
            credentials.delete(key.getCredentialId());
            changes.appendForRecovery(actor,sid,CustomerMfaKeyChanges.Kind.REVOKED,fingerprint(key.getCredentialId().toBase64UrlString()),event);
        }
        changes.appendForRecovery(actor,sid,CustomerMfaKeyChanges.Kind.ADDED,fingerprint(added.getCredentialId().toBase64UrlString()),event);
        tokens.revokeAllByUserId(actor,LocalDateTime.now());
        jdbc.update("DELETE FROM customer_mfa_challenges WHERE user_id=?",actor);
        jdbc.update("DELETE FROM customer_password_challenges WHERE user_id=?",actor);
        // 불변 최초 등록 사실은 삭제하지 않는다. 복구 직후에도 새 키 서명 없이 거래할 수 없다.
        return issue(user,sid,"MFA_RECOVERED",stored.code(),true,event);
    });}
    private User strong(long actor,UUID sid){
        var user=sessions.require(actor,sid);limit(actor);
        if(!mfa.required(actor)||!mfa.fresh(actor,tokens.findBySessionId(sid).orElseThrow())||!recipients.verified(user))throw denied();
        return user;
    }
    private User pending(long actor,UUID sid){
        var user=users.lockById(actor).orElseThrow(CustomerRecoveryService::denied);
        var session=sid==null?null:tokens.findBySessionId(sid).orElse(null);
        if(user.getRole()!=UserRole.CUSTOMER||!user.isAuthenticationAllowed()||session==null||session.getUserId()!=actor
                ||session.getRevoked()||!session.getExpiresAt().isAfter(LocalDateTime.now())||session.hasVerifiedMfa()
                ||!mfa.required(actor)||!recipients.verified(user))throw denied();
        limit(actor);return user;
    }
    private void limit(long actor){
        if(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_RECOVERY' AND outcome='DENIED' AND detail='VERIFICATION_DENIED' AND occurred_at>clock_timestamp()-interval '15 minutes'",Long.class,actor)>=5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"계정 복구 시도 제한");
    }
    private UUID current(long actor){
        var ids=jdbc.queryForList("SELECT code_id FROM customer_recovery_events WHERE user_id=? ORDER BY sequence DESC LIMIT 1",UUID.class,actor);
        return ids.isEmpty()?null:ids.getFirst();
    }
    private Options challenge(long actor,UUID sid,String purpose,UUID code,Object options){
        jdbc.update("DELETE FROM customer_recovery_challenges WHERE session_id=? OR expires_at<clock_timestamp()",sid);
        UUID id=UUID.randomUUID();String encoded=json.writeValueAsString(options);
        jdbc.update("INSERT INTO customer_recovery_challenges(id,user_id,session_id,purpose,code_id,options,expires_at) VALUES(?,?,?,?,?,?,clock_timestamp()+interval '2 minutes')",id,actor,sid,purpose,code,encoded);
        audit.record(actor,"CUSTOMER_RECOVERY_OPTIONS","customer:"+actor,"SUCCESS",purpose);
        return new Options(id,purpose,json.readTree(encoded));
    }
    private record Stored(String options,UUID code) {}
    private Stored consume(long actor,UUID sid,Proof proof,String purpose){
        if(proof==null||proof.challengeId()==null||proof.publicKey()==null)throw denied();
        var rows=jdbc.queryForList("SELECT options,code_id FROM customer_recovery_challenges WHERE id=? AND user_id=? AND session_id=? AND purpose=? AND expires_at>clock_timestamp() FOR UPDATE",proof.challengeId(),actor,sid,purpose);
        if(rows.size()!=1)throw denied();var row=rows.getFirst();
        jdbc.update("DELETE FROM customer_recovery_challenges WHERE id=?",proof.challengeId());
        return new Stored((String)row.get("options"),(UUID)row.get("code_id"));
    }
    private Code issue(User user,UUID sid,String kind,UUID consumed,boolean requiresLogin,UUID event){
        byte[] raw=new byte[32];random.nextBytes(raw);String code=Base64.getUrlEncoder().withoutPadding().encodeToString(raw);Arrays.fill(raw,(byte)0);
        UUID id=UUID.randomUUID();
        // 원문은 이 응답에서 한 번만 제공한다. 이미 설치한 소금 포함 Argon2로 저장한다.
        jdbc.update("INSERT INTO customer_recovery_codes(id,user_id,code_hash) VALUES(?,?,?)",id,user.getId(),passwords.hash(code));
        jdbc.update("INSERT INTO customer_recovery_events(id,user_id,session_id,kind,code_id,consumed_code_id) VALUES(?,?,?,?,?,?)",event,user.getId(),sid,kind,id,consumed);
        jdbc.update("DELETE FROM customer_recovery_challenges WHERE user_id=?",user.getId());
        Instant occurred=jdbc.queryForObject("SELECT created_at FROM customer_recovery_events WHERE id=?",java.sql.Timestamp.class,event).toInstant();
        outbox.enqueue(user.getId(),CustomerMailOutbox.Kind.ACCOUNT_RECOVERY,event,user.getEmail(),null,occurred.plusSeconds(86400));
        audit.record(user.getId(),"CUSTOMER_RECOVERY_COMPLETED","recovery:"+event,"SUCCESS",kind);
        return new Code(code,requiresLogin);
    }
    private static String fingerprint(String id){return OperatorMfaService.sha256Hex(id);}
    private <T>T operation(long actor,java.util.function.Supplier<T> work){return tx.execute(t->{
        try{return work.get();}
        catch(ResponseStatusException e){if(e.getStatusCode().is4xxClientError())audit.denied(actor,"CUSTOMER_RECOVERY","customer:"+actor,e.getStatusCode().value()==429?"RATE_LIMITED":"VERIFICATION_DENIED");throw e;}
        catch(IllegalArgumentException|tools.jackson.core.JacksonException|com.webauthn4j.verifier.exception.VerificationException|com.webauthn4j.converter.exception.DataConversionException e){audit.denied(actor,"CUSTOMER_RECOVERY","customer:"+actor,"VERIFICATION_DENIED");throw denied();}
    });}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"계정 복구 자격을 확인하세요");}
}
