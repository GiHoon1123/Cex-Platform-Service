package dustin.cex.domains.edd;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 증빙은 신고·공급사 연결과 다른 키로 보호한다. 키를 바꾸고 옛 암호문을 추측해 읽는 경로는 없다. */
@Component
public class EvidenceCrypto {
    private final byte[] key;
    private final String keyId;
    public EvidenceCrypto(@Value("${edd.evidence-key:}") String encoded,
            @Value("${cdd.encryption-key:}") String declaration,@Value("${cdd.fingerprint-key:}") String fingerprint,
            @Value("${didit.encryption-key:}") String supplier) {
        if(encoded.isEmpty()) {key=null;keyId="UNCONFIGURED";return;}
        try {
            key=Base64.getDecoder().decode(encoded);
            if(key.length!=32) throw new IllegalArgumentException();
            for(String other:List.of(declaration,fingerprint,supplier))
                if(!other.isEmpty() && MessageDigest.isEqual(key,Base64.getDecoder().decode(other))) throw new IllegalArgumentException();
            keyId=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key));
        } catch(IllegalArgumentException|GeneralSecurityException e) {throw new IllegalArgumentException("증빙에는 별도의 256비트 암호화 키가 필요합니다");}
    }
    public String keyId() {return keyId;}
    public void requireConfigured() {if(key==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"증빙 보관 설정이 필요합니다");}
    public byte[] encrypt(String context,byte[] plain) {
        requireConfigured();byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,context,nonce,plain);
        return ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array();
    }
    public byte[] decrypt(String expectedKey,String context,byte[] encrypted) {
        requireConfigured();
        if(!keyId.equals(expectedKey) || encrypted.length<28) throw new IllegalStateException("증빙 키 또는 암호문 검증 실패");
        return crypt(Cipher.DECRYPT_MODE,context,Arrays.copyOf(encrypted,12),Arrays.copyOfRange(encrypted,12,encrypted.length));
    }
    private byte[] crypt(int mode,String context,byte[] nonce,byte[] bytes) {
        try {
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            // 행·고객·사건·요청·자료 종류·원본/열람본 구분을 인증한다. 다른 문맥으로 복사하면 실패한다.
            cipher.updateAAD(("EDD_EVIDENCE_V1:"+context).getBytes(StandardCharsets.UTF_8));return cipher.doFinal(bytes);
        } catch(GeneralSecurityException e) {throw new IllegalStateException("증빙 암호화 검증 실패");}
    }
    @Override public String toString() {return "EvidenceCrypto[비밀 생략]";}
}
