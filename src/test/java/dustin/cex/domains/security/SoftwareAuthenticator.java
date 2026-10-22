package dustin.cex.domains.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.*;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.*;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트마다 새 P-256 개인키를 만든다. 검증기의 성공 반환을 모킹하지 않고 실제 ECDSA 서명을 보낸다.
 * CBOR·COSE 인코딩은 검증 라이브러리에 맡긴다. 테스트의 소프트웨어 키는 실물 보안키 증적이 아니다.
 */
final class SoftwareAuthenticator {
    final KeyPair pair;
    final byte[] credentialId=new byte[32];
    final JsonMapper json=JsonMapper.builder().build();
    SoftwareAuthenticator() throws Exception {
        var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));
        pair=generator.generateKeyPair();new SecureRandom().nextBytes(credentialId);
    }
    String id() {return encoded(credentialId);}
    JsonNode registration(OperatorMfaService.Options options,String origin,boolean verified) throws Exception {
        byte[] client=client("webauthn.create",options.publicKey().path("challenge").asText(),origin);
        var data=new AttestedCredentialData(AAGUID.ZERO,credentialId,EC2COSEKey.create((ECPublicKey)pair.getPublic(),com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier.ES256));
        var auth=new AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(sha(options.publicKey().path("rp").path("id").asText().getBytes(StandardCharsets.UTF_8)),
            (byte)(0x41|(verified?4:0)),0,data);
        byte[] attestation=new ObjectConverter().getCborConverter().writeValueAsBytes(new AttestationObject(auth,new NoneAttestationStatement()));
        return json.valueToTree(Map.of("label","테스트 보안키","credential",Map.of("id",id(),"rawId",id(),"type","public-key",
            "response",Map.of("clientDataJSON",encoded(client),"attestationObject",encoded(attestation),"transports",List.of()),
            "clientExtensionResults",Map.of())));
    }
    JsonNode assertion(OperatorMfaService.Options options,String origin,boolean verified,long count,String userHandle) throws Exception {
        byte[] client=client("webauthn.get",options.publicKey().path("challenge").asText(),origin);
        byte[] auth=ByteBuffer.allocate(37).put(sha(options.publicKey().path("rpId").asText().getBytes(StandardCharsets.UTF_8)))
            .put((byte)(verified?5:1)).putInt((int)count).array();
        var signer=Signature.getInstance("SHA256withECDSA");signer.initSign(pair.getPrivate());signer.update(auth);signer.update(sha(client));
        Map<String,Object> response=new HashMap<>(Map.of("clientDataJSON",encoded(client),"authenticatorData",encoded(auth),"signature",encoded(signer.sign())));
        if(userHandle!=null) response.put("userHandle",userHandle);
        return json.valueToTree(Map.of("id",id(),"rawId",id(),"type","public-key","response",response,"clientExtensionResults",Map.of()));
    }
    private byte[] client(String type,String challenge,String origin) throws Exception {
        return json.writeValueAsBytes(Map.of("type",type,"challenge",challenge,"origin",origin,"crossOrigin",false));
    }
    static String encoded(byte[] bytes) {return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static byte[] sha(byte[] bytes) throws Exception {return MessageDigest.getInstance("SHA-256").digest(bytes);}
}
