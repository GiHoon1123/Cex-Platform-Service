package dustin.cex.domains.security;

import dustin.cex.domains.cdd.CddCrypto;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 비밀번호 변경 한 업무에만 일회 서명을 결합한다. 로그인 인증으로 대체하거나 인증 시각을 연장하지 않는다. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class CustomerPasswordAuthorization {
    public record Options(UUID challengeId,String purpose,JsonNode publicKey){}
    public record Proof(UUID challengeId,JsonNode publicKey){@Override public String toString(){return "Proof[인증 자료 생략]";}}
    private final JdbcTemplate jdbc;private final CustomerSessionPolicy sessions;private final CustomerMfaPolicy mfa;
    private final CddCrypto crypto;private final AuditService audit;private final WebAuthnVerifier verifier;
    private final JsonMapper json=JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    public CustomerPasswordAuthorization(JdbcTemplate jdbc,CustomerSessionPolicy sessions,CustomerMfaPolicy mfa,CddCrypto crypto,AuditService audit,
            @Value("${customer.webauthn.rp-id:localhost}")String rp,@Value("${customer.webauthn.origin:http://localhost:3003}")String origin){
        this.jdbc=jdbc;this.sessions=sessions;this.mfa=mfa;this.crypto=crypto;this.audit=audit;verifier=new WebAuthnVerifier(jdbc,rp,origin,"CEX 고객 인증");
    }
    public Options prepare(long actor,UUID sid,String next){return operation(actor,()->{
        require(actor,sid);UUID id=UUID.randomUUID();String encoded=json.writeValueAsString(verifier.request(actor));
        jdbc.update("DELETE FROM customer_password_challenges WHERE session_id=? OR expires_at<clock_timestamp()",sid);
        jdbc.update("INSERT INTO customer_password_challenges(id,user_id,session_id,password_mac,key_id,options,expires_at) VALUES(?,?,?,?,?,?,clock_timestamp()+interval '2 minutes')",id,actor,sid,mac(actor,sid,next),crypto.keyId(),encoded);
        audit.record(actor,"CUSTOMER_PASSWORD_OPTIONS","customer:"+actor,"SUCCESS","PASSWORD_CHANGE_ONLY");
        return new Options(id,"PASSWORD_CHANGE",json.readTree(encoded));
    });}
    public void authorize(long actor,UUID sid,String next,Proof proof,UUID change){operation(actor,()->{
        require(actor,sid);if(proof==null||proof.challengeId()==null||proof.publicKey()==null)throw denied();
        var rows=jdbc.queryForList("SELECT options FROM customer_password_challenges WHERE id=? AND user_id=? AND session_id=? AND password_mac=? AND key_id=? AND NOT consumed AND expires_at>clock_timestamp() FOR UPDATE",proof.challengeId(),actor,sid,mac(actor,sid,next),crypto.keyId());
        if(rows.size()!=1)throw denied();
        var key=verifier.verifyAssertion(actor,(String)rows.getFirst().get("options"),proof.publicKey());
        jdbc.update("UPDATE customer_password_challenges SET consumed=true WHERE id=?",proof.challengeId());
        // 변경 원본은 같은 상위 트랜잭션이 뒤에서 만든다. 지연 외래키는 커밋 때 원본 존재를 강제한다.
        String fingerprint=OperatorMfaService.sha256Hex(key.getRawId().toBase64UrlString());
        jdbc.update("INSERT INTO customer_password_authorizations(change_id,user_id,challenge_id,credential_fingerprint) VALUES(?,?,?,?)",change,actor,proof.challengeId(),fingerprint);
        audit.record(actor,"CUSTOMER_PASSWORD_AUTHORIZED","password-change:"+change,"SUCCESS","KEY:"+fingerprint);
        return null;
    });}
    private void require(long actor,UUID sid){
        sessions.require(actor,sid);if(!mfa.required(actor))throw denied();
        if(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='CUSTOMER_PASSWORD_AUTHORIZATION' AND outcome='DENIED' AND detail='VERIFICATION_DENIED' AND occurred_at>clock_timestamp()-interval '15 minutes'",Long.class,actor)>=5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"변경 서명 시도 제한");
    }
    private String mac(long actor,UUID sid,String next){
        // 공개 해시를 남기면 DB 유출 시 비밀번호를 사전 대입할 수 있다. 기존 독립 HMAC 키와 목적 구분을 쓴다.
        return crypto.fingerprint("customer-password-change-v1",actor+":"+sid+":"+next);
    }
    // 서버의 5xx 장애는 고객의 잘못된 서명으로 집계하지 않는다. 복구 뒤에도 한도를 소진시키지 않는다.
    private <T>T operation(long actor,java.util.function.Supplier<T> work){try{return work.get();}
        catch(ResponseStatusException e){if(e.getStatusCode().is4xxClientError())audit.denied(actor,"CUSTOMER_PASSWORD_AUTHORIZATION","customer:"+actor,e.getStatusCode().value()==429?"RATE_LIMITED":"VERIFICATION_DENIED");throw e;}
        catch(IllegalArgumentException|tools.jackson.core.JacksonException|com.webauthn4j.verifier.exception.VerificationException|com.webauthn4j.converter.exception.DataConversionException e){audit.denied(actor,"CUSTOMER_PASSWORD_AUTHORIZATION","customer:"+actor,"VERIFICATION_DENIED");throw denied();}}
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"이 비밀번호 변경의 패스키 서명을 확인하세요");}
}
