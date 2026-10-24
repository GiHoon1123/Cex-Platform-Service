package dustin.cex.domains.security;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.webauthn.api.*;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.security.web.webauthn.management.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * 설치된 표준 검증기의 등록·서명 검증만 담당한다. 개인키와 서명 알고리즘을 직접 구현하지 않는다.
 * 역할·등록 권한·챌린지의 소유/목적/만료·세션 교체·감사는 호출 업무의 책임이다.
 * 사용자 잠금과 트랜잭션 안에서만 호출해 공개키 카운터와 업무 성공을 함께 확정한다.
 * 고객/운영자별 정확한 출처로 인스턴스를 만들며 출처를 합쳐 허용하지 않는다.
 */
final class WebAuthnVerifier {
    private final JdbcPublicKeyCredentialUserEntityRepository identities;
    private final WebAuthnCredentialRepository credentials;
    private final Webauthn4JRelyingPartyOperations relyingParty;
    // 전역 JSON 매퍼나 자동 로그인 화면을 바꾸지 않는다. 표준 타입에만 필요한 모듈을 쓴다.
    private final JsonMapper json=JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();
    WebAuthnVerifier(JdbcTemplate jdbc,String rpId,String origin,String displayName) {
        URI uri=URI.create(origin);
        // 운영에서는 HTTPS의 정확한 출처 하나를 사용한다. HTTP는 로컬 루프백 개발만 허용한다.
        if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || (uri.getPath()!=null && !uri.getPath().isEmpty())
                || !(uri.getHost().equals(rpId) || uri.getHost().endsWith("."+rpId))
                || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && "localhost".equals(rpId) && "localhost".equals(uri.getHost()))))
            throw new IllegalArgumentException("보안키 출처와 RP 설정을 확인하세요");
        identities=new JdbcPublicKeyCredentialUserEntityRepository(jdbc);credentials=new WebAuthnCredentialRepository(jdbc);
        relyingParty=new Webauthn4JRelyingPartyOperations(identities,credentials,
            PublicKeyCredentialRpEntity.builder().id(rpId).name(displayName).build(),Set.of(origin));
        // 기본값 preferred를 그대로 두면 PIN·생체 확인이 생략될 수 있으므로 등록·인증 모두 필수다.
        relyingParty.setCustomizeCreationOptions(b->b.authenticatorSelection(AuthenticatorSelectionCriteria.builder()
            .residentKey(ResidentKeyRequirement.REQUIRED).userVerification(UserVerificationRequirement.REQUIRED).build()));
        relyingParty.setCustomizeRequestOptions(b->b.userVerification(UserVerificationRequirement.REQUIRED));
    }
    List<CredentialRecord> keys(long actor) {
        var identity=identities.findByUsername(Long.toString(actor));
        return identity==null?List.of():credentials.findByUserId(identity.getId());
    }
    PublicKeyCredentialCreationOptions creation(long actor) {
        requireTransaction();
        var auth=UsernamePasswordAuthenticationToken.authenticated(Long.toString(actor),null,List.of());
        return relyingParty.createPublicKeyCredentialCreationOptions(new ImmutablePublicKeyCredentialCreationOptionsRequest(auth));
    }
    PublicKeyCredentialRequestOptions request(long actor) {
        requireTransaction();
        var auth=UsernamePasswordAuthenticationToken.authenticated(Long.toString(actor),null,List.of());
        return relyingParty.createCredentialRequestOptions(new ImmutablePublicKeyCredentialRequestOptionsRequest(auth));
    }
    CredentialRecord register(String stored,JsonNode publicKey) {
        requireTransaction();validateProof(publicKey,true);
        var options=restoreCreation(stored);
        var key=json.treeToValue(publicKey,RelyingPartyPublicKey.class);
        if(key.getLabel()==null || key.getLabel().isBlank() || key.getLabel().length()>100) throw denied();
        var record=relyingParty.registerCredential(new ImmutableRelyingPartyRegistrationRequest(options,key));
        // 외부 rawId와 검증된 인증 데이터의 자격 ID가 같아야 다른 키 ID로 바꿔 저장하지 못한다.
        var converter=new com.webauthn4j.converter.util.ObjectConverter();
        var attestation=converter.getCborConverter().readValue(record.getAttestationObject().getBytes(),com.webauthn4j.data.attestation.AttestationObject.class);
        if(!Arrays.equals(record.getCredentialId().getBytes(),attestation.getAuthenticatorData().getAttestedCredentialData().getCredentialId())) throw denied();
        return record;
    }
    private static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("보안키 검증에는 업무 트랜잭션이 필요합니다");
    }
    private static org.springframework.web.server.ResponseStatusException denied() {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"보안키 검증 실패");
    }
    /** 서명 검증을 로그인과 업무 승인에서 공유한다. 호출자는 사용자 잠금과 트랜잭션을 유지한다. */
    PublicKeyCredential<AuthenticatorAssertionResponse> verifyAssertion(long actor,String stored,JsonNode credential) {
        requireTransaction();
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
        // 고객과 운영자 인증 모두 다른 사이트의 iframe에서 허용하지 않는다.
        if((client.has("crossOrigin") && (!client.path("crossOrigin").isBoolean() || client.path("crossOrigin").asBoolean())) || client.has("topOrigin")) throw denied();
    }
}
