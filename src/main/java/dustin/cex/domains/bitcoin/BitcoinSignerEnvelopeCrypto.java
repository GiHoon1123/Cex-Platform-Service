package dustin.cex.domains.bitcoin;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 서명 요청 봉투만 보호하는 독립 키다. 고객확인·Didit·비트코인 개인키와 공유하지 않는다. */
@Component
public final class BitcoinSignerEnvelopeCrypto {
    private final byte[] encryption,fingerprint;private final String keyId;
    public BitcoinSignerEnvelopeCrypto(@Value("${bitcoin.signer.envelope-encryption-key:}") String encryption,@Value("${bitcoin.signer.envelope-fingerprint-key:}") String fingerprint){
        if(encryption.isEmpty()&&fingerprint.isEmpty()){this.encryption=null;this.fingerprint=null;keyId="UNCONFIGURED";return;}
        try{this.encryption=Base64.getDecoder().decode(encryption);this.fingerprint=Base64.getDecoder().decode(fingerprint);
            if(this.encryption.length!=32||this.fingerprint.length!=32||Arrays.equals(this.encryption,this.fingerprint))throw new IllegalArgumentException();
            var digest=MessageDigest.getInstance("SHA-256");digest.update(this.encryption);digest.update(this.fingerprint);keyId=HexFormat.of().formatHex(digest.digest());
        }catch(IllegalArgumentException|GeneralSecurityException invalid){throw new IllegalArgumentException("서명 요청에는 서로 다른 독립적인 256비트 암호화 키와 지문 키가 필요합니다");}
    }
    public String keyId(){return keyId;}
    public String fingerprint(String value){require();try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(fingerprint,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(("btc-signer-envelope-v1"+value).getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException impossible){throw new IllegalStateException("서명 요청 지문 생성 실패");}}
    public byte[] encrypt(long owner,UUID request,byte[] plain){require();byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);byte[] cipher=crypt(Cipher.ENCRYPT_MODE,owner,request,nonce,plain);return ByteBuffer.allocate(12+cipher.length).put(nonce).put(cipher).array();}
    public byte[] decrypt(long owner,UUID request,byte[] cipher){require();if(cipher.length<28)throw new IllegalStateException("서명 요청 암호문 손상");return crypt(Cipher.DECRYPT_MODE,owner,request,Arrays.copyOf(cipher,12),Arrays.copyOfRange(cipher,12,cipher.length));}
    private byte[] crypt(int mode,long owner,UUID request,byte[] nonce,byte[] value){try{var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(encryption,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD((owner+":"+request).getBytes(StandardCharsets.UTF_8));return cipher.doFinal(value);}catch(GeneralSecurityException invalid){throw new IllegalStateException("서명 요청 암호화 검증 실패");}}
    private void require(){if(encryption==null)throw new IllegalStateException("서명 요청 봉투 키가 필요합니다");}
    @Override public String toString(){return "BitcoinSignerEnvelopeCrypto[비밀 생략]";}
}
