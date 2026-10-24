package dustin.cex.domains.security;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.*;
import dustin.cex.domains.mail.CustomerMailRecipientPolicy;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 고객 최초 패스키 등록과 비밀번호 이후의 서명 인증을 처리한다. 운영자 등록 권한을 받지 않는다. */
@Service
@Transactional(propagation=Propagation.NEVER)
public class CustomerMfaService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordService passwords;
    private final JwtService jwt;
    private final CustomerMfaPolicy policy;
    private final CustomerMailRecipientPolicy recipients;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final WebAuthnVerifier verifier;
    private final JsonMapper json=JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    public CustomerMfaService(JdbcTemplate jdbc,UserRepository users,RefreshTokenRepository tokens,PasswordService passwords,
            JwtService jwt,CustomerMfaPolicy policy,CustomerMailRecipientPolicy recipients,AuditService audit,
            PlatformTransactionManager manager,@Value("${customer.webauthn.rp-id:localhost}")String rp,
            @Value("${customer.webauthn.origin:http://localhost:3003}")String origin){
        this.jdbc=jdbc;this.users=users;this.tokens=tokens;this.passwords=passwords;this.jwt=jwt;
        this.policy=policy;this.recipients=recipients;this.audit=audit;tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        verifier=new WebAuthnVerifier(jdbc,rp,origin,"CEX 고객 인증");
    }
    public record Options(UUID challengeId,JsonNode publicKey){}
    public record Enrolled(UUID id,boolean requiresLogin){}
    public record Verified(String accessToken,String refreshToken){@Override public String toString(){return "Verified[토큰 생략]";}}
    public Options registrationOptions(long actor,UUID sid,String currentPassword){
        return operation(actor,()->{
            var user=lock(actor,sid);requireFirstRegistration(user);
            if(!passwords.verify(currentPassword,user.getPasswordHash()))throw denied();
            // 확인한 정확한 주소도 챌린지에 묶는다. 등록 도중 주소가 바뀌면 새로 시작해야 한다.
            return challenge(actor,sid,"REGISTER",verifier.creation(actor),recipients.addressMac(user.getEmail()));
        });
    }
    public Enrolled register(long actor,UUID sid,UUID id,JsonNode publicKey){
        return operation(actor,()->{
            var user=lock(actor,sid);requireFirstRegistration(user);
            var stored=consume(actor,sid,id,"REGISTER");
            if(!recipients.addressMac(user.getEmail()).equals(stored.emailMac()))throw denied();
            var key=verifier.register(stored.options(),publicKey);UUID event=UUID.randomUUID();
            jdbc.update("INSERT INTO customer_mfa_enrollments(id,user_id,credential_fingerprint) VALUES(?,?,?)",event,actor,OperatorMfaService.sha256Hex(key.getCredentialId().toBase64UrlString()));
            // 등록 자체를 로그인 서명으로 간주하지 않는다. 모든 기존 세션을 회수하고 별도로 인증한다.
            tokens.revokeAllByUserId(actor,LocalDateTime.now());
            audit.record(actor,"CUSTOMER_MFA_REGISTER","customer-mfa:"+event,"SUCCESS","ALL_SESSIONS_REVOKED");
            return new Enrolled(event,true);
        });
    }
    public Options authenticationOptions(long actor,UUID sid){
        return operation(actor,()->{
            lock(actor,sid);if(!policy.required(actor)||verifier.keys(actor).isEmpty())throw denied();
            return challenge(actor,sid,"AUTHENTICATE",verifier.request(actor),null);
        });
    }
    public Verified authenticate(long actor,UUID sid,UUID id,JsonNode credential){
        return operation(actor,()->{
            lock(actor,sid);if(!policy.required(actor))throw denied();
            var stored=consume(actor,sid,id,"AUTHENTICATE");
            var key=verifier.verifyAssertion(actor,stored.options(),credential);
            // 인증 수준이 올라갈 때 기존 대기 세션을 교체한다. 탈취된 대기 JWT에는 권한이 생기지 않는다.
            tokens.revokeAllByUserId(actor,LocalDateTime.now());
            LocalDateTime now=LocalDateTime.now();String raw=jwt.generateRefreshToken();
            var session=tokens.saveAndFlush(RefreshToken.builder().userId(actor).tokenHash(jwt.hashRefreshToken(raw))
                .mfaVerifiedAt(now).mfaCredentialFingerprint(OperatorMfaService.sha256Hex(key.getRawId().toBase64UrlString())).expiresAt(now.plusHours(12)).build());
            audit.record(actor,"CUSTOMER_MFA_AUTHENTICATE","customer:"+actor,"SUCCESS","SESSION_REPLACED");
            return new Verified(jwt.generateAccessToken(actor,session.getSessionId()),raw);
        });
    }
    private void requireFirstRegistration(User user){
        if(policy.required(user.getId())||!verifier.keys(user.getId()).isEmpty()||!recipients.verified(user))throw denied();
    }
    private User lock(long actor,UUID sid){
        // 이 경로만 비밀번호 단계의 대기 세션을 받는다. 고객/운영 업무 서비스의 검증 수준 검사를 우회하지 않는다.
        var user=users.lockById(actor).orElseThrow(CustomerMfaService::denied);
        var session=sid==null?null:tokens.findBySessionId(sid).orElse(null);
        if(user.getRole()!=UserRole.CUSTOMER||!user.isAuthenticationAllowed()||session==null||session.getUserId()!=actor
                ||session.getRevoked()||!session.getExpiresAt().isAfter(LocalDateTime.now()))throw denied();
        if(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_MFA' AND outcome='DENIED' AND detail='VERIFICATION_DENIED' AND occurred_at>clock_timestamp()-interval '15 minutes'",Long.class,actor)>=5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"고객 인증 시도 제한");
        return user;
    }
    private Options challenge(long actor,UUID sid,String purpose,Object options,String emailMac){
        // 비밀이 아닌 일회 옵션만 저장하며 세션당 하나로 제한한다. 운영자 챌린지와 표도 구분한다.
        jdbc.update("DELETE FROM customer_mfa_challenges WHERE session_id=? OR expires_at<clock_timestamp()",sid);
        UUID id=UUID.randomUUID();String encoded=json.writeValueAsString(options);
        jdbc.update("INSERT INTO customer_mfa_challenges(id,user_id,session_id,purpose,options,email_mac,expires_at) VALUES(?,?,?,?,?,?,clock_timestamp()+interval '5 minutes')",id,actor,sid,purpose,encoded,emailMac);
        audit.record(actor,"CUSTOMER_MFA_CHALLENGE","customer:"+actor,"SUCCESS",purpose);
        return new Options(id,json.readTree(encoded));
    }
    private record Stored(String options,String emailMac){}
    private Stored consume(long actor,UUID sid,UUID id,String purpose){
        var rows=jdbc.queryForList("SELECT options,email_mac FROM customer_mfa_challenges WHERE id=? AND user_id=? AND session_id=? AND purpose=? AND NOT consumed AND expires_at>clock_timestamp() FOR UPDATE",id,actor,sid,purpose);
        if(rows.size()!=1)throw denied();jdbc.update("UPDATE customer_mfa_challenges SET consumed=true WHERE id=?",id);
        return new Stored((String)rows.getFirst().get("options"),(String)rows.getFirst().get("email_mac"));
    }
    private <T>T operation(long actor,java.util.function.Supplier<T> work){
        return tx.execute(status->{
            try{return work.get();}
            catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_MFA","customer:"+actor,e.getStatusCode().value()==429?"RATE_LIMITED":"VERIFICATION_DENIED");throw e;}
            catch(IllegalArgumentException|tools.jackson.core.JacksonException|com.webauthn4j.verifier.exception.VerificationException|com.webauthn4j.converter.exception.DataConversionException e){
                // 실패는 사용자 잠금 안에서 별도 확정한다. DB/감사 장애를 인증 오류로 감추지 않는다.
                audit.denied(actor,"CUSTOMER_MFA","customer:"+actor,"VERIFICATION_DENIED");throw denied();
            }
        });
    }
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"고객 패스키 검증 실패");}
}
