package dustin.cex.domains.security;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.PasswordService;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.mail.*;
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

/** 현재 비밀번호와 정확한 새 주소에 대한 패스키 서명 후 새 주소 소유를 확인해 변경한다. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class CustomerEmailChangeService {
    public record Options(UUID challengeId,String purpose,JsonNode publicKey) {}
    public record Proof(UUID challengeId,JsonNode publicKey){@Override public String toString(){return "Proof[인증 자료 생략]";}}
    public record Changed(UUID id,boolean requiresLogin) {}
    private final JdbcTemplate jdbc;private final UserRepository users;private final RefreshTokenRepository tokens;
    private final CustomerSessionPolicy sessions;private final CustomerMfaPolicy mfa;private final PasswordService passwords;
    private final CddCrypto crypto;private final CustomerMailRecipientPolicy recipients;private final CustomerMailOutbox outbox;
    private final CustomerEmailChallenges challenges;private final AuditService audit;private final TransactionTemplate tx;
    private final WebAuthnVerifier verifier;
    private final JsonMapper json=JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    public CustomerEmailChangeService(JdbcTemplate jdbc,UserRepository users,RefreshTokenRepository tokens,CustomerSessionPolicy sessions,
            CustomerMfaPolicy mfa,PasswordService passwords,CddCrypto crypto,CustomerMailRecipientPolicy recipients,
            CustomerMailOutbox outbox,CustomerEmailChallenges challenges,AuditService audit,PlatformTransactionManager manager,
            @Value("${customer.webauthn.rp-id:localhost}")String rp,@Value("${customer.webauthn.origin:http://localhost:3003}")String origin){
        this.jdbc=jdbc;this.users=users;this.tokens=tokens;this.sessions=sessions;this.mfa=mfa;this.passwords=passwords;
        this.crypto=crypto;this.recipients=recipients;this.outbox=outbox;this.challenges=challenges;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        verifier=new WebAuthnVerifier(jdbc,rp,origin,"CEX 고객 인증");
    }
    public Options options(long actor,UUID sid,String currentPassword,String destination){return operation(actor,()->{
        var user=strong(actor,sid);validateDestination(user,destination);
        if(!passwords.verify(currentPassword,user.getPasswordHash()))throw denied(HttpStatus.FORBIDDEN);
        UUID id=UUID.randomUUID();String encoded=json.writeValueAsString(verifier.request(actor));
        jdbc.update("DELETE FROM customer_email_change_challenges WHERE session_id=? OR expires_at<clock_timestamp()",sid);
        jdbc.update("INSERT INTO customer_email_change_challenges(id,user_id,session_id,user_version,old_email_mac,new_email_mac,key_id,options,expires_at) VALUES(?,?,?,?,?,?,?,?,clock_timestamp()+interval '2 minutes')",id,actor,sid,user.getVersion(),recipients.addressMac(user.getEmail()),recipients.addressMac(destination),crypto.keyId(),encoded);
        audit.record(actor,"CUSTOMER_EMAIL_CHANGE_OPTIONS","customer:"+actor,"SUCCESS","EMAIL_CHANGE_ONLY");
        return new Options(id,"EMAIL_CHANGE",json.readTree(encoded));
    });}
    public CustomerEmailChallenges.Receipt request(long actor,UUID sid,String destination,Proof proof){return operation(actor,()->{
        var user=strong(actor,sid);validateDestination(user,destination);
        if(proof==null||proof.challengeId()==null||proof.publicKey()==null)throw denied(HttpStatus.FORBIDDEN);
        var previous=jdbc.queryForList("SELECT r.id,c.expires_at,r.old_email_mac,r.new_email_mac,r.key_id,r.user_version FROM customer_email_change_requests r JOIN customer_email_challenges c ON c.id=r.id WHERE r.authorization_id=? AND r.user_id=?",proof.challengeId(),actor);
        // 응답 유실 재요청은 같은 실제 변경 의도에 대한 영수증만 반환한다. 토큰/메일/만료를 새로 만들지 않는다.
        if(!previous.isEmpty()){
            var old=previous.getFirst();checkContext(user,destination,old);
            return new CustomerEmailChallenges.Receipt((UUID)old.get("id"),((java.sql.Timestamp)old.get("expires_at")).toInstant());
        }
        var rows=jdbc.queryForList("SELECT * FROM customer_email_change_challenges WHERE id=? AND user_id=? AND session_id=? AND expires_at>clock_timestamp() FOR UPDATE",proof.challengeId(),actor,sid);
        if(rows.size()!=1)throw denied(HttpStatus.FORBIDDEN);var stored=rows.getFirst();checkContext(user,destination,stored);
        var signed=verifier.verifyAssertion(actor,(String)stored.get("options"),proof.publicKey());
        // 워커는 커밋 뒤에만 토큰과 요청 원본을 함께 본다. 새 주소와 이전 주소의 메일을 따로 확정하지 않는다.
        var receipt=challenges.issue(user,proof.challengeId(),destination,CustomerMailOutbox.Kind.EMAIL_CHANGE_VERIFICATION);
        String fingerprint=OperatorMfaService.sha256Hex(signed.getRawId().toBase64UrlString());
        jdbc.update("INSERT INTO customer_email_change_requests(id,user_id,session_id,authorization_id,user_version,credential_fingerprint,old_email_mac,new_email_mac,key_id) VALUES(?,?,?,?,?,?,?,?,?)",receipt.challengeId(),actor,sid,proof.challengeId(),user.getVersion(),fingerprint,stored.get("old_email_mac"),stored.get("new_email_mac"),crypto.keyId());
        jdbc.update("DELETE FROM customer_email_change_challenges WHERE id=?",proof.challengeId());
        Instant occurred=jdbc.queryForObject("SELECT created_at FROM customer_email_change_requests WHERE id=?",java.sql.Timestamp.class,receipt.challengeId()).toInstant();
        outbox.enqueue(actor,CustomerMailOutbox.Kind.EMAIL_CHANGE_REQUESTED,receipt.challengeId(),user.getEmail(),null,occurred.plusSeconds(86400));
        audit.record(actor,"CUSTOMER_EMAIL_CHANGE_REQUESTED","email-change-request:"+receipt.challengeId(),"SUCCESS","PENDING_NEW_ADDRESS");
        return receipt;
    });}
    public Changed confirm(long actor,UUID sid,UUID id,String token){return operation(actor,()->{
        var user=sessions.require(actor,sid);limit(actor);
        var rows=jdbc.queryForList("SELECT * FROM customer_email_change_requests WHERE id=? AND user_id=?",id,actor);
        if(rows.size()!=1)throw denied(HttpStatus.NOT_FOUND);var request=rows.getFirst();
        // 새 주소의 실제 토큰을 먼저 확인한다. 주소 중복 여부를 토큰 없이 알아내는 조회 경로를 만들지 않는다.
        challenges.verifyToken(actor,id,token);
        var done=jdbc.queryForList("SELECT id FROM customer_email_changes WHERE request_id=? AND user_id=?",UUID.class,id,actor);
        if(!done.isEmpty())return new Changed(done.getFirst(),true);
        if(!crypto.keyId().equals(request.get("key_id"))||user.getVersion()!=((Number)request.get("user_version")).longValue()
                ||!recipients.addressMac(user.getEmail()).equals(request.get("old_email_mac"))
                ||!mfa.credentialActive(actor,(String)request.get("credential_fingerprint")))throw denied(HttpStatus.CONFLICT);
        UUID mail=jdbc.queryForObject("SELECT id FROM customer_mail_jobs WHERE reference_id=? AND user_id=? AND kind='EMAIL_CHANGE_VERIFICATION'",UUID.class,id,actor);
        var payload=outbox.open(mail);String destination=payload.email();
        if(!recipients.addressMac(destination).equals(request.get("new_email_mac")))throw new IllegalStateException("확인 주소의 원본이 일치하지 않습니다");
        // 신규 가입과 같은 주소 할당 잠금을 사용한다. 네트워크/SMTP 대기는 이 트랜잭션 안에 없다.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","customer-email-owner-v1:"+destination);
        if(users.existsByEmail(destination))throw denied(HttpStatus.CONFLICT);
        String old=user.getEmail();user.setEmail(destination);users.saveAndFlush(user);
        challenges.confirmCurrent(user,sid,id,token);
        UUID change=UUID.randomUUID();jdbc.update("INSERT INTO customer_email_changes(id,user_id,request_id,session_id) VALUES(?,?,?,?)",change,actor,id,sid);
        tokens.revokeAllByUserId(actor,LocalDateTime.now());
        Instant changed=jdbc.queryForObject("SELECT changed_at FROM customer_email_changes WHERE id=?",java.sql.Timestamp.class,change).toInstant();
        outbox.enqueue(actor,CustomerMailOutbox.Kind.EMAIL_CHANGED_PREVIOUS,change,old,null,changed.plusSeconds(86400));
        outbox.enqueue(actor,CustomerMailOutbox.Kind.EMAIL_CHANGED_CURRENT,change,destination,null,changed.plusSeconds(86400));
        jdbc.update("DELETE FROM customer_email_change_challenges WHERE user_id=?",actor);
        audit.record(actor,"CUSTOMER_EMAIL_CHANGED","email-change:"+change,"SUCCESS","ALL_SESSIONS_REVOKED");
        return new Changed(change,true);
    });}
    private User strong(long actor,UUID sid){
        var user=sessions.require(actor,sid);limit(actor);
        if(!mfa.required(actor)||!mfa.fresh(actor,tokens.findBySessionId(sid).orElseThrow())||!recipients.verified(user))throw denied(HttpStatus.FORBIDDEN);
        return user;
    }
    private void validateDestination(User user,String destination){CustomerEmailChallenges.validateAddress(destination);if(user.getEmail().equals(destination))throw denied(HttpStatus.BAD_REQUEST);}
    private void checkContext(User user,String destination,Map<String,Object> stored){
        if(user.getVersion()!=((Number)stored.get("user_version")).longValue()||!crypto.keyId().equals(stored.get("key_id"))
                ||!recipients.addressMac(user.getEmail()).equals(stored.get("old_email_mac"))||!recipients.addressMac(destination).equals(stored.get("new_email_mac")))throw denied(HttpStatus.CONFLICT);
    }
    private void limit(long actor){if(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_EMAIL_CHANGE' AND outcome='DENIED' AND detail='VERIFICATION_DENIED' AND occurred_at>clock_timestamp()-interval '15 minutes'",Long.class,actor)>=5)throw denied(HttpStatus.TOO_MANY_REQUESTS);}
    private <T>T operation(long actor,java.util.function.Supplier<T> work){return tx.execute(t->{
        try{return work.get();}catch(ResponseStatusException e){if(e.getStatusCode().is4xxClientError())audit.denied(actor,"CUSTOMER_EMAIL_CHANGE","customer:"+actor,e.getStatusCode().value()==429?"RATE_LIMITED":"VERIFICATION_DENIED");throw e;}
        catch(IllegalArgumentException|tools.jackson.core.JacksonException|com.webauthn4j.verifier.exception.VerificationException|com.webauthn4j.converter.exception.DataConversionException e){audit.denied(actor,"CUSTOMER_EMAIL_CHANGE","customer:"+actor,"VERIFICATION_DENIED");throw denied(HttpStatus.FORBIDDEN);}
    });}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"이메일 변경 자격·서명·확인 기한을 확인하세요");}
}
