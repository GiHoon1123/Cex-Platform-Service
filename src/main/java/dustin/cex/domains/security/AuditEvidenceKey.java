package dustin.cex.domains.security;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 감사 제출물 서명 키를 업무 암호화 키와 분리하고 설정되지 않은 내보내기를 닫힌 상태로 실패시킨다. */
@Component
public class AuditEvidenceKey {
    private final byte[] key;private final String keyId;
    public AuditEvidenceKey(@Value("${audit.evidence-key:}") String encoded){
        byte[] decoded;try{decoded=encoded.isBlank()?new byte[0]:Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){decoded=new byte[0];}
        key=decoded;if(key.length>=32)keyId=sha256(key);else keyId="UNCONFIGURED";
    }
    public void requireConfigured(){if(key.length<32)throw new IllegalStateException("감사 증적 서명 키가 설정되지 않았습니다");}
    public String keyId(){requireConfigured();return keyId;}
    public String sign(String text){requireConfigured();try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));}catch(GeneralSecurityException impossible){throw new IllegalStateException(impossible);}}
    public boolean verifies(String text,String signature){return MessageDigest.isEqual(HexFormat.of().parseHex(sign(text)),HexFormat.of().parseHex(signature));}
    private static String sha256(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
