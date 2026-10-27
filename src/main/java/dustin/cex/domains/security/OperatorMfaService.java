package dustin.cex.domains.security;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.auth.service.JwtService;

/**
 * 운영 인증의 업무 계약만 정의한다. 표준 검증기는 서명·RP·출처·챌린지·사용자 확인을 검증한다.
 * 쿠키·웹 로그인 화면 없이 기존 Bearer 세션과 PostgreSQL 트랜잭션으로 일회성을 보장한다.
 */
@Service
public class OperatorMfaService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final JwtService jwt;
    private final WebAuthnVerifier verifier;
    // 애플리케이션 전체의 금융 JSON 설정은 바꾸지 않는다. 표준 WebAuthn 타입만 이 매퍼에서 처리한다.
    private final JsonMapper json=JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    public OperatorMfaService(JdbcTemplate jdbc,UserRepository users,RefreshTokenRepository tokens,AuditService audit,
            PlatformTransactionManager manager,JwtService jwt,@Value("${operator.webauthn.rp-id:localhost}") String rpId,
            @Value("${operator.webauthn.origin:http://localhost:3003}") String origin) {
        this.jdbc=jdbc;this.users=users;this.tokens=tokens;this.audit=audit;this.tx=new TransactionTemplate(manager);this.jwt=jwt;
        verifier=new WebAuthnVerifier(jdbc,rpId,origin,"CEX 운영 인증");
    }
    public record Options(UUID challengeId,JsonNode publicKey) {}

    public Options registrationOptions(long actor,UUID sid,String enrollmentToken) {
        return operation(actor,()->{
            var session=lock(actor,sid);var registered=verifier.keys(actor);
            if(registered.isEmpty()) {
                // 최초 등록 권한은 OS 관리자에게 따로 받은 256비트 토큰이다. 비밀번호만으로 키를 심지 못한다.
                if(enrollmentToken==null || enrollmentToken.length()<43 || enrollmentToken.length()>256) throw denied();
                String hash=sha256Hex(enrollmentToken);
                int used=jdbc.update("UPDATE operator_enrollment SET consumed=true WHERE user_id=? AND token_hash=? AND NOT consumed AND expires_at>clock_timestamp()",actor,hash);
                if(used!=1) throw denied();
            } else requireFresh(session);
            if(registered.size()>=5) throw denied();
            var options=verifier.creation(actor);
            return challenge(actor,sid,"REGISTER",options);
        });
    }
    public void register(long actor,UUID sid,UUID id,JsonNode publicKey) {
        operation(actor,()->{
            var session=lock(actor,sid);var stored=consume(actor,sid,id,"REGISTER");
            var registered=verifier.keys(actor);
            if(registered.size()>=5) throw denied();
            if(!registered.isEmpty()) requireFresh(session);
            var record=verifier.register(stored,publicKey);
            // 등록 자체로 운영 권한은 열지 않는다. 별도 get 서명 검증까지 요구한다.
            audit.record(actor,"MFA_REGISTER","user:"+actor,"SUCCESS","CREDENTIAL_BOUND:sha256="+sha256Hex(record.getCredentialId().toBase64UrlString()));return null;
        });
    }
    public Options authenticationOptions(long actor,UUID sid) {
        return operation(actor,()->{
            lock(actor,sid);var registered=verifier.keys(actor);
            if(registered.isEmpty()) throw denied();
            var options=verifier.request(actor);
            return challenge(actor,sid,"AUTHENTICATE",options);
        });
    }
    public record Verified(String accessToken,String refreshToken) {
        @Override public String toString() {return "Verified[토큰 생략]";}
    }
    public Verified authenticate(long actor,UUID sid,UUID id,JsonNode credential) {
        return operation(actor,()->{
            lock(actor,sid);var stored=consume(actor,sid,id,"AUTHENTICATE");
            var key=verifier.verifyAssertion(actor,stored,credential);
            // 인증 수준을 높일 때 세션을 교체한다. 이전 대기 토큰을 훔친 사람에게 운영 권한이 생기지 않는다.
            tokens.revokeAllByUserId(actor,LocalDateTime.now());
            String raw=jwt.generateRefreshToken();LocalDateTime verifiedAt=LocalDateTime.now();
            var verified=tokens.saveAndFlush(RefreshToken.builder().userId(actor).tokenHash(jwt.hashRefreshToken(raw))
                .mfaVerifiedAt(verifiedAt).mfaCredentialFingerprint(sha256Hex(key.getRawId().toBase64UrlString())).expiresAt(verifiedAt.plusHours(12)).build());
            audit.record(actor,"MFA_AUTHENTICATE","user:"+actor,"SUCCESS","USER_VERIFIED:sha256="+sha256Hex(key.getRawId().toBase64UrlString()));
            return new Verified(jwt.generateAccessToken(actor,verified.getSessionId()),raw);
        });
    }
    public record AuthorizationProof(UUID challengeId,JsonNode credential) {
        // 디버깅이나 DTO 로그에도 서명 원문을 남기지 않는다.
        @Override public String toString() {return "AuthorizationProof[서명 생략]";}
    }
    /**
     * 각 승인 서비스에서만 호출하는 내부 경로다. 업무의 현재 상태·잠금을 확인한 뒤 사용자와 챌린지 순서를 지킨다.
     * 요청이 불변인 업무도 관련 사용자 잠금 뒤 결정 상태를 다시 확인한다. 이미 확보한 사용자 잠금을 다른 순서로 새로 잡지 않는다.
     * 인증 옵션만 발급하며 세션의 MFA 시각이나 수명은 늘리지 않는다.
     */
    public Options approvalOptions(long actor,UUID sid,ApprovalKind kind,UUID requestId,String payloadHash,boolean approve) {
        requireBusinessTransaction();
        return operation(actor,()->{
            requireFresh(lock(actor,sid));
            checkBinding(kind,requestId,payloadHash);
            var registered=verifier.keys(actor);
            if(registered.isEmpty()) throw denied();
            var options=verifier.request(actor);
            UUID id=UUID.randomUUID();String encoded=json.writeValueAsString(options);
            // 한 세션에서 새 업무 확인을 시작하면 이전 업무 옵션은 폐기한다. 오래된 옵션도 함께 정리한다.
            jdbc.update("DELETE FROM operator_authorization_challenges WHERE session_id=? OR expires_at<clock_timestamp()",sid);
            jdbc.update("""
                INSERT INTO operator_authorization_challenges(id,user_id,session_id,kind,request_id,payload_hash,approve,options,expires_at)
                VALUES(?,?,?,?,?,?,?,?,clock_timestamp()+interval '2 minutes')
                """,id,actor,sid,kind.name(),requestId,payloadHash,approve,encoded);
            audit.record(actor,"APPROVAL_OPTIONS","request:"+requestId,"SUCCESS",kind+":"+(approve?"APPROVE:":"REJECT:")+payloadHash);
            return new Options(id,json.readTree(encoded));
        });
    }
    public void authorizeApproval(long actor,UUID sid,ApprovalKind kind,UUID requestId,String payloadHash,boolean approve,AuthorizationProof proof) {
        requireBusinessTransaction();
        operation(actor,()->{
            requireFresh(lock(actor,sid));
            checkBinding(kind,requestId,payloadHash);
            if(proof==null || proof.challengeId()==null || proof.credential()==null) throw denied();
            var rows=jdbc.queryForList("""
                SELECT options FROM operator_authorization_challenges WHERE id=? AND user_id=? AND session_id=?
                 AND kind=? AND request_id=? AND payload_hash=? AND approve=? AND NOT consumed AND expires_at>clock_timestamp() FOR UPDATE
                """,proof.challengeId(),actor,sid,kind.name(),requestId,payloadHash,approve);
            if(rows.isEmpty()) throw denied();
            var key=verifier.verifyAssertion(actor,(String)rows.getFirst().get("options"),proof.credential());
            jdbc.update("UPDATE operator_authorization_challenges SET consumed=true WHERE id=?",proof.challengeId());
            // 서명 소비·공개키 카운터·업무 변경·감사는 같은 업무 트랜잭션에서 확정된다.
            audit.record(actor,"APPROVAL_AUTHORIZATION","request:"+requestId,"SUCCESS",
                kind+":"+(approve?"APPROVE:":"REJECT:")+payloadHash+":key="+sha256Hex(key.getRawId().toBase64UrlString()));
            return null;
        });
    }
    /** 현재 구현된 업무만 구분한다. 임의 명령 이름이나 동적 실행기를 받지 않는다. */
    public enum ApprovalKind { ROLE, MFA_RECOVERY, ACCESS_REVIEW, TRADING_RESTRICTION, EDD_REVIEW, EDD_ADDRESS_REVIEW, EDD_FUNDS_REVIEW, EDD_WEALTH_REVIEW, STR_REVIEW, STR_CUSTODY, RELATIONSHIP_EXIT, EXIT_RESCISSION, CUSTOMER_REVIEW_CUSTODY, CUSTOMER_REVIEW_FINAL, CUSTOMER_MAIL_RETRY, TM_CASE_REVIEW, TM_CASE_ACTION, BTC_CHAIN_RECOVERY, BTC_WITHDRAWAL_PAYOUT, BTC_CUSTODY_CORRECTION, MARKET_STATE }
    public void bindApproval(ApprovalKind kind,UUID id,long requester,long version,String hash,Instant expires) {
        requireBusinessTransaction();
        jdbc.update("INSERT INTO operator_approval_bindings(kind,request_id,requester_id,requester_version,payload_hash,expires_at) VALUES(?,?,?,?,?,?)",
            kind.name(),id,requester,version,hash,java.sql.Timestamp.from(expires));
    }
    private void checkBinding(ApprovalKind kind,UUID id,String hash) {
        // 각 호출 서비스가 연관 사용자 행을 이미 잠갔다. 여기서 다른 순서로 잠금을 다시 잡지 않는다.
        // 과거 요청에 결합 정보가 없으면 추측해서 채우지 않고 거부한다. 읽기용 업무 이력은 보존한다.
        var rows=jdbc.queryForList("""
            SELECT b.requester_version,u.version FROM operator_approval_bindings b JOIN users u ON u.id=b.requester_id
            WHERE b.kind=? AND b.request_id=? AND b.payload_hash=? AND b.expires_at>clock_timestamp()
            """,kind.name(),id,hash);
        if(rows.size()!=1 || !rows.getFirst().get("requester_version").equals(rows.getFirst().get("version"))) throw denied();
    }
    private void requireBusinessTransaction() {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("업무 트랜잭션 안에서만 추가 인증을 처리할 수 있습니다");
    }
    private Options challenge(long actor,UUID sid,String purpose,Object options) {
        // 세션마다 진행 중 챌린지는 하나만 남겨 경합·과거 옵션 재사용·무제한 발급을 제한한다.
        jdbc.update("DELETE FROM operator_mfa_challenges WHERE session_id=? OR expires_at<clock_timestamp()",sid);
        UUID id=UUID.randomUUID();String encoded=json.writeValueAsString(options);
        jdbc.update("INSERT INTO operator_mfa_challenges(id,user_id,session_id,purpose,options,expires_at) VALUES(?,?,?,?,?,clock_timestamp()+interval '5 minutes')",id,actor,sid,purpose,encoded);
        audit.record(actor,"MFA_CHALLENGE","user:"+actor,"SUCCESS",purpose);
        return new Options(id,json.readTree(encoded));
    }
    private String consume(long actor,UUID sid,UUID id,String purpose) {
        var rows=jdbc.queryForList("SELECT options FROM operator_mfa_challenges WHERE id=? AND user_id=? AND session_id=? AND purpose=? AND NOT consumed AND expires_at>clock_timestamp() FOR UPDATE",id,actor,sid,purpose);
        if(rows.isEmpty()) throw denied();
        jdbc.update("UPDATE operator_mfa_challenges SET consumed=true WHERE id=?",id);
        return (String)rows.getFirst().get("options");
    }
    private RefreshToken lock(long actor,UUID sid) {
        var user=users.lockById(actor).orElseThrow(OperatorMfaService::denied);
        var session=tokens.findBySessionId(sid).orElseThrow(OperatorMfaService::denied);
        if(!user.isAuthenticationAllowed() || user.getRole()==UserRole.CUSTOMER || user.getOperatorPersonId()==null
                || session.getUserId()!=actor || session.getRevoked() || !session.getExpiresAt().isAfter(LocalDateTime.now())) throw denied();
        // 사용자 잠금 안에서 실패를 다시 확인하므로 병렬 요청으로 한도를 넘길 수 없다.
        if(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='MFA' AND outcome='DENIED' AND detail='VERIFICATION_DENIED' AND occurred_at>clock_timestamp()-interval '15 minutes'",Long.class,actor)>=5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"운영 인증 시도 제한");
        return session;
    }
    private void requireFresh(RefreshToken session) {
        if(!session.hasVerifiedMfa() || !session.getMfaVerifiedAt().plusMinutes(5).isAfter(LocalDateTime.now())) throw denied();
    }
    private <T> T operation(long actor,java.util.function.Supplier<T> work) {
        return tx.execute(status->{
            try { return work.get(); }
            catch(ResponseStatusException e) {
                // 제한 뒤의 요청은 원래 실패 창을 계속 연장하지 않게 별도로 분류한다.
                audit.denied(actor,"MFA","user:"+actor,e.getStatusCode().value()==429?"RATE_LIMITED":"VERIFICATION_DENIED");throw e;
            }
            catch(IllegalArgumentException | tools.jackson.core.JacksonException
                    | com.webauthn4j.verifier.exception.VerificationException
                    | com.webauthn4j.converter.exception.DataConversionException e) {
                // 사용자 행을 잠근 상태에서 실패를 별도 커밋한다. 다음 병렬 시도는 이 실패를 본다.
                // DB 장애·감사 장애는 여기서 인증 실패로 변환하지 않고 호출자까지 전파한다.
                audit.denied(actor,"MFA","user:"+actor,"VERIFICATION_DENIED");
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"운영 보안키 검증 실패",e);
            }
        });
    }
    private static ResponseStatusException denied() {return new ResponseStatusException(HttpStatus.FORBIDDEN,"운영 보안키 검증 실패");}
    // 등록 비밀은 비교 가능한 해시만 보관한다. 공개키 ID의 해시는 기기를 식별하는 감사 지문으로 쓴다.
    // 등록 비밀의 해시와 원본 인증 자료는 감사에 남기지 않는다.
    static String sha256Hex(String raw) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
