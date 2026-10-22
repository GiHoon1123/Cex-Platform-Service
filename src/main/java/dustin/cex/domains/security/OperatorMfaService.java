package dustin.cex.domains.security;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.webauthn.api.*;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.security.web.webauthn.management.*;
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
    private final JdbcPublicKeyCredentialUserEntityRepository identities;
    private final OperatorCredentialRepository credentials;
    private final Webauthn4JRelyingPartyOperations relyingParty;
    // 애플리케이션 전체의 금융 JSON 설정은 바꾸지 않는다. 표준 WebAuthn 타입만 이 매퍼에서 처리한다.
    private final JsonMapper json=JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    public OperatorMfaService(JdbcTemplate jdbc,UserRepository users,RefreshTokenRepository tokens,AuditService audit,
            PlatformTransactionManager manager,JwtService jwt,@Value("${operator.webauthn.rp-id:localhost}") String rpId,
            @Value("${operator.webauthn.origin:http://localhost:3003}") String origin) {
        URI uri=URI.create(origin);
        // 운영에서는 HTTPS의 정확한 출처 하나를 사용한다. HTTP는 로컬 루프백 개발만 허용한다.
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || (uri.getPath()!=null && !uri.getPath().isEmpty())
                || !(uri.getHost().equals(rpId) || uri.getHost().endsWith("."+rpId))
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && "localhost".equals(rpId) && "localhost".equals(uri.getHost()))))
            throw new IllegalArgumentException("운영 보안키 출처와 RP 설정을 확인하세요");
        this.jdbc=jdbc;this.users=users;this.tokens=tokens;this.audit=audit;this.tx=new TransactionTemplate(manager);this.jwt=jwt;
        identities=new JdbcPublicKeyCredentialUserEntityRepository(jdbc);credentials=new OperatorCredentialRepository(jdbc);
        relyingParty=new Webauthn4JRelyingPartyOperations(identities,credentials,
            PublicKeyCredentialRpEntity.builder().id(rpId).name("CEX 운영 인증").build(),Set.of(origin));
        // 기본값 preferred를 그대로 두면 PIN·생체 확인이 생략될 수 있으므로 등록·인증 모두 필수다.
        relyingParty.setCustomizeCreationOptions(b->b.authenticatorSelection(AuthenticatorSelectionCriteria.builder()
            .residentKey(ResidentKeyRequirement.REQUIRED).userVerification(UserVerificationRequirement.REQUIRED).build()));
        relyingParty.setCustomizeRequestOptions(b->b.userVerification(UserVerificationRequirement.REQUIRED));
    }
    public record Options(UUID challengeId,JsonNode publicKey) {}

    public Options registrationOptions(long actor,UUID sid,String enrollmentToken) {
        return operation(actor,()->{
            var session=lock(actor,sid);var identity=identities.findByUsername(Long.toString(actor));
            if(identity==null || credentials.findByUserId(identity.getId()).isEmpty()) {
                // 최초 등록 권한은 OS 관리자에게 따로 받은 256비트 토큰이다. 비밀번호만으로 키를 심지 못한다.
                if(enrollmentToken==null || enrollmentToken.length()<43 || enrollmentToken.length()>256) throw denied();
                String hash=sha256Hex(enrollmentToken);
                int used=jdbc.update("UPDATE operator_enrollment SET consumed=true WHERE user_id=? AND token_hash=? AND NOT consumed AND expires_at>clock_timestamp()",actor,hash);
                if(used!=1) throw denied();
            } else requireFresh(session);
            if(identity!=null && credentials.findByUserId(identity.getId()).size()>=5) throw denied();
            var auth=UsernamePasswordAuthenticationToken.authenticated(Long.toString(actor),null,List.of());
            var options=relyingParty.createPublicKeyCredentialCreationOptions(new ImmutablePublicKeyCredentialCreationOptionsRequest(auth));
            return challenge(actor,sid,"REGISTER",options);
        });
    }
    public void register(long actor,UUID sid,UUID id,JsonNode publicKey) {
        operation(actor,()->{
            var session=lock(actor,sid);var stored=consume(actor,sid,id,"REGISTER");
            var identity=identities.findByUsername(Long.toString(actor));
            if(identity!=null) {
                var registered=credentials.findByUserId(identity.getId());
                if(registered.size()>=5) throw denied();
                if(!registered.isEmpty()) requireFresh(session);
            }
            validateProof(publicKey,true);
            var options=restoreCreation(stored);
            var key=json.treeToValue(publicKey,RelyingPartyPublicKey.class);
            if(key.getLabel()==null || key.getLabel().isBlank() || key.getLabel().length()>100) throw denied();
            var record=relyingParty.registerCredential(new ImmutableRelyingPartyRegistrationRequest(options,key));
            // 라이브러리가 검증한 attested credential ID와 외부 rawId도 동일해야 한다.
            var converter=new com.webauthn4j.converter.util.ObjectConverter();
            var attestation=converter.getCborConverter().readValue(record.getAttestationObject().getBytes(),com.webauthn4j.data.attestation.AttestationObject.class);
            if(!Arrays.equals(record.getCredentialId().getBytes(),attestation.getAuthenticatorData().getAttestedCredentialData().getCredentialId())) throw denied();
            // 등록 자체로 운영 권한은 열지 않는다. 별도 get 서명 검증까지 요구한다.
            audit.record(actor,"MFA_REGISTER","user:"+actor,"SUCCESS","CREDENTIAL_BOUND:sha256="+sha256Hex(record.getCredentialId().toBase64UrlString()));return null;
        });
    }
    public Options authenticationOptions(long actor,UUID sid) {
        return operation(actor,()->{
            lock(actor,sid);var identity=identities.findByUsername(Long.toString(actor));
            if(identity==null || credentials.findByUserId(identity.getId()).isEmpty()) throw denied();
            var auth=UsernamePasswordAuthenticationToken.authenticated(Long.toString(actor),null,List.of());
            var options=relyingParty.createCredentialRequestOptions(new ImmutablePublicKeyCredentialRequestOptionsRequest(auth));
            return challenge(actor,sid,"AUTHENTICATE",options);
        });
    }
    public record Verified(String accessToken,String refreshToken) {
        @Override public String toString() {return "Verified[토큰 생략]";}
    }
    public Verified authenticate(long actor,UUID sid,UUID id,JsonNode credential) {
        return operation(actor,()->{
            lock(actor,sid);var stored=consume(actor,sid,id,"AUTHENTICATE");
            var key=verifyAssertion(actor,stored,credential);
            // 인증 수준을 높일 때 세션을 교체한다. 이전 대기 토큰을 훔친 사람에게 운영 권한이 생기지 않는다.
            tokens.revokeAllByUserId(actor,LocalDateTime.now());
            String raw=jwt.generateRefreshToken();LocalDateTime verifiedAt=LocalDateTime.now();
            var verified=tokens.saveAndFlush(RefreshToken.builder().userId(actor).tokenHash(jwt.hashRefreshToken(raw))
                .mfaVerifiedAt(verifiedAt).mfaCredentialFingerprint(sha256Hex(key.getRawId().toBase64UrlString())).expiresAt(verifiedAt.plusHours(12)).build());
            audit.record(actor,"MFA_AUTHENTICATE","user:"+actor,"SUCCESS","USER_VERIFIED:sha256="+sha256Hex(key.getRawId().toBase64UrlString()));
            return new Verified(jwt.generateAccessToken(actor,verified.getSessionId()),raw);
        });
    }
    /** 서명 검증을 로그인과 업무 승인에서 공유한다. 호출자는 사용자 잠금과 트랜잭션을 유지한다. */
    private PublicKeyCredential<AuthenticatorAssertionResponse> verifyAssertion(long actor,String stored,JsonNode credential) {
        var options=restoreAuthentication(stored);
        validateProof(credential,false);
        var type=json.getTypeFactory().constructParametricType(PublicKeyCredential.class,AuthenticatorAssertionResponse.class);
        PublicKeyCredential<AuthenticatorAssertionResponse> key=json.convertValue(credential,type);
        var existing=credentials.findByCredentialId(key.getRawId());
        var identity=identities.findByUsername(Long.toString(actor));
        if(existing==null || identity==null || !identity.getId().equals(existing.getUserEntityUserId())) throw denied();
        var handle=key.getResponse().getUserHandle();
        if(handle!=null && !handle.equals(identity.getId())) throw denied();
        var authenticated=relyingParty.authenticate(new RelyingPartyAuthenticationRequest(options,key));
        if(!authenticated.getName().equals(Long.toString(actor))) throw denied();
        // 일부 동기화 패스키는 카운터 0을 쓴다. 카운터를 쓰는 기기는 마지막 성공 값보다 증가해야 한다.
        // 인증 데이터의 앞 32바이트는 RP 해시, 다음 1바이트는 플래그, 이어지는 4바이트는 부호 없는 카운터다.
        long current=Integer.toUnsignedLong(ByteBuffer.wrap(key.getResponse().getAuthenticatorData().getBytes(),33,4).getInt());
        if((existing.getSignatureCount()!=0 || current!=0) && current<=existing.getSignatureCount()) throw denied();
        return key;
    }
    public record AuthorizationProof(UUID challengeId,JsonNode credential) {
        // 디버깅이나 DTO 로그에도 서명 원문을 남기지 않는다.
        @Override public String toString() {return "AuthorizationProof[서명 생략]";}
    }
    /**
     * 역할 승인 서비스만 호출하는 내부 경로다. 요청 잠금 → 사용자 잠금 → 챌린지 잠금 순서를 지킨다.
     * 인증 옵션만 발급하며 세션의 MFA 시각이나 수명은 늘리지 않는다.
     */
    Options roleAuthorizationOptions(long actor,UUID sid,UUID requestId,String payloadHash,boolean approve) {
        requireBusinessTransaction();
        return operation(actor,()->{
            requireFresh(lock(actor,sid));
            var identity=identities.findByUsername(Long.toString(actor));
            if(identity==null || credentials.findByUserId(identity.getId()).isEmpty()) throw denied();
            var auth=UsernamePasswordAuthenticationToken.authenticated(Long.toString(actor),null,List.of());
            var options=relyingParty.createCredentialRequestOptions(new ImmutablePublicKeyCredentialRequestOptionsRequest(auth));
            UUID id=UUID.randomUUID();String encoded=json.writeValueAsString(options);
            // 한 세션에서 새 업무 확인을 시작하면 이전 업무 옵션은 폐기한다. 오래된 옵션도 함께 정리한다.
            jdbc.update("DELETE FROM operator_role_challenges WHERE session_id=? OR expires_at<clock_timestamp()",sid);
            jdbc.update("""
                INSERT INTO operator_role_challenges(id,user_id,session_id,request_id,payload_hash,approve,options,expires_at)
                VALUES(?,?,?,?,?,?,?,clock_timestamp()+interval '2 minutes')
                """,id,actor,sid,requestId,payloadHash,approve,encoded);
            audit.record(actor,"ROLE_AUTHORIZATION_OPTIONS","request:"+requestId,"SUCCESS",(approve?"APPROVE:":"REJECT:")+payloadHash);
            return new Options(id,json.readTree(encoded));
        });
    }
    void authorizeRole(long actor,UUID sid,UUID requestId,String payloadHash,boolean approve,AuthorizationProof proof) {
        requireBusinessTransaction();
        operation(actor,()->{
            requireFresh(lock(actor,sid));
            if(proof==null || proof.challengeId()==null || proof.credential()==null) throw denied();
            var rows=jdbc.queryForList("""
                SELECT options FROM operator_role_challenges WHERE id=? AND user_id=? AND session_id=?
                 AND request_id=? AND payload_hash=? AND approve=? AND NOT consumed AND expires_at>clock_timestamp() FOR UPDATE
                """,proof.challengeId(),actor,sid,requestId,payloadHash,approve);
            if(rows.isEmpty()) throw denied();
            var key=verifyAssertion(actor,(String)rows.getFirst().get("options"),proof.credential());
            jdbc.update("UPDATE operator_role_challenges SET consumed=true WHERE id=?",proof.challengeId());
            // 서명 소비·공개키 카운터·역할 변경·감사는 같은 업무 트랜잭션에서 확정된다.
            audit.record(actor,"ROLE_AUTHORIZATION","request:"+requestId,"SUCCESS",
                (approve?"APPROVE:":"REJECT:")+payloadHash+":key="+sha256Hex(key.getRawId().toBase64UrlString()));
            return null;
        });
    }
    private void requireBusinessTransaction() {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("업무 트랜잭션 안에서만 추가 인증을 처리할 수 있습니다");
    }
    // 공식 모듈은 옵션의 응답 직렬화를 제공하지만 DB JSON에서 옵션 생성자를 복원하지 않는다.
    // 임의 타입 역직렬화나 Java 객체 스트림 대신 서버가 저장한 필드만 표준 빌더로 재구성한다.
    // 사용자 확인 필수 정책은 저장 문자열의 객체 동일성에 의존하지 않고 매번 명시한다.
    private PublicKeyCredentialCreationOptions restoreCreation(String encoded) {
        var stored=json.readTree(encoded);var user=stored.path("user");var rp=stored.path("rp");
        return PublicKeyCredentialCreationOptions.builder()
            .rp(PublicKeyCredentialRpEntity.builder().id(rp.path("id").asText()).name(rp.path("name").asText()).build())
            .user(ImmutablePublicKeyCredentialUserEntity.builder().id(Bytes.fromBase64(user.path("id").asText()))
                .name(user.path("name").asText()).displayName(user.path("displayName").asText()).build())
            .challenge(Bytes.fromBase64(stored.path("challenge").asText()))
            .pubKeyCredParams(PublicKeyCredentialParameters.EdDSA,PublicKeyCredentialParameters.ES256,PublicKeyCredentialParameters.RS256)
            .authenticatorSelection(AuthenticatorSelectionCriteria.builder().residentKey(ResidentKeyRequirement.REQUIRED)
                .userVerification(UserVerificationRequirement.REQUIRED).build()).build();
    }
    private PublicKeyCredentialRequestOptions restoreAuthentication(String encoded) {
        var stored=json.readTree(encoded);List<PublicKeyCredentialDescriptor> allowed=new ArrayList<>();
        for(var item:stored.path("allowCredentials"))
            allowed.add(PublicKeyCredentialDescriptor.builder().id(Bytes.fromBase64(item.path("id").asText())).build());
        return PublicKeyCredentialRequestOptions.builder().challenge(Bytes.fromBase64(stored.path("challenge").asText()))
            .rpId(stored.path("rpId").asText()).allowCredentials(allowed).userVerification(UserVerificationRequirement.REQUIRED).build();
    }
    private void validateProof(JsonNode body,boolean registration) {
        if(body==null || !body.isObject() || json.writeValueAsString(body).length()>32768) throw denied();
        JsonNode credential=registration?body.path("credential"):body;
        if(!credential.path("type").asText().equals("public-key") || !credential.path("id").isString()
                || credential.path("id").asText().isEmpty() || credential.path("id").asText().length()>1400
                || !credential.path("id").asText().equals(credential.path("rawId").asText())) throw denied();
        var response=credential.path("response");
        for(String field:registration?List.of("clientDataJSON","attestationObject"):List.of("clientDataJSON","authenticatorData","signature"))
            if(!response.path(field).isString() || response.path(field).asText().isEmpty()) throw denied();
        var client=json.readTree(Base64.getUrlDecoder().decode(response.path("clientDataJSON").asText()));
        // 운영자 인증은 다른 사이트의 iframe에서 허용하지 않는다.
        if((client.has("crossOrigin") && (!client.path("crossOrigin").isBoolean() || client.path("crossOrigin").asBoolean())) || client.has("topOrigin")) throw denied();
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
