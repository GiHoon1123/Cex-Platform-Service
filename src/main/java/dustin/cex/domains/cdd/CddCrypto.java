package dustin.cex.domains.cdd;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 고객 신고 원문과 대조용 지문은 서로 다른 키로 보호한다. 키가 없으면 거래 허용을 생략하지 않는다. */
@Component
public class CddCrypto {
    private final byte[] encryption, fingerprint;
    private final String keyId;
    public CddCrypto(@Value("${cdd.encryption-key:}") String encryption,
            @Value("${cdd.fingerprint-key:}") String fingerprint,
            @Value("${didit.encryption-key:}") String link) {
        if(encryption.isEmpty() && fingerprint.isEmpty()) {this.encryption=null;this.fingerprint=null;keyId="UNCONFIGURED";return;}
        try {
            this.encryption=Base64.getDecoder().decode(encryption);this.fingerprint=Base64.getDecoder().decode(fingerprint);
            if(this.encryption.length!=32 || this.fingerprint.length!=32 || Arrays.equals(this.encryption,this.fingerprint)
                    || (!link.isEmpty() && (Arrays.equals(this.encryption,Base64.getDecoder().decode(link))
                    || Arrays.equals(this.fingerprint,Base64.getDecoder().decode(link))))) throw new IllegalArgumentException();
            var digest=MessageDigest.getInstance("SHA-256");digest.update(this.encryption);digest.update(this.fingerprint);
            keyId=HexFormat.of().formatHex(digest.digest());
        } catch(IllegalArgumentException | GeneralSecurityException e) {throw new IllegalArgumentException("고객 확인에는 서로 다른 독립적인 256비트 암호화 키와 지문 키가 필요합니다");}
    }
    public String keyId() {return keyId;}
    public void requireConfigured() {if(encryption==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"고객 확인 설정이 필요합니다");}
    /** 길이 구분자를 포함해 필드 경계를 고정한다. 이름·생일은 사전 공격이 쉬우므로 공개 해시만 저장하지 않는다. */
    public String fingerprint(String domain,String value) {
        requireConfigured();
        try {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(fingerprint,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((domain.length()+":"+domain+value).getBytes(StandardCharsets.UTF_8)));
        } catch(GeneralSecurityException e) {throw new IllegalStateException("고객 확인 지문 생성 실패");}
    }
    public byte[] encrypt(long user,UUID id,byte[] plain) {
        requireConfigured();byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        byte[] cipher=crypt(Cipher.ENCRYPT_MODE,user,id,nonce,plain);
        return ByteBuffer.allocate(12+cipher.length).put(nonce).put(cipher).array();
    }
    public byte[] decrypt(long user,UUID id,byte[] cipher) {
        requireConfigured();if(cipher.length<28) throw new IllegalStateException("고객 정보 암호문 손상");
        return crypt(Cipher.DECRYPT_MODE,user,id,Arrays.copyOf(cipher,12),Arrays.copyOfRange(cipher,12,cipher.length));
    }
    private byte[] crypt(int mode,long user,UUID id,byte[] nonce,byte[] bytes) {
        try {var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,new SecretKeySpec(encryption,"AES"),new GCMParameterSpec(128,nonce));
            // 다른 사용자나 다른 신고 행에 암호문을 옮기면 인증 태그 검증부터 실패한다.
            cipher.updateAAD((user+":"+id).getBytes(StandardCharsets.UTF_8));return cipher.doFinal(bytes);
        } catch(GeneralSecurityException e) {throw new IllegalStateException("고객 정보 암호화 검증 실패");}
    }
    @Override public String toString() {return "CddCrypto[비밀 생략]";}
}
