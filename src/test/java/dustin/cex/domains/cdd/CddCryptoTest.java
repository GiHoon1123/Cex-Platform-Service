package dustin.cex.domains.cdd;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 표준 암호 라이브러리를 사용하되 키 분리·인증 문맥·실패 시 거부 계약은 직접 검증한다. */
class CddCryptoTest {
    String key(int marker) {byte[] bytes=new byte[32];bytes[0]=(byte)marker;return Base64.getEncoder().encodeToString(bytes);}
    @Test void emptyConfigurationCannotEncryptOrFingerprint() {
        var crypto=new CddCrypto("","","");assertEquals("UNCONFIGURED",crypto.keyId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->crypto.fingerprint("이름","가상 이름"));
    }
    @Test void partialInvalidOrReusedKeysFailStartup() {
        for(String[] pair:List.of(new String[]{key(1),""},new String[]{"",key(2)},new String[]{"손상",key(2)},new String[]{key(1),key(1)}))
            assertThrows(IllegalArgumentException.class,()->new CddCrypto(pair[0],pair[1],""));
        assertThrows(IllegalArgumentException.class,()->new CddCrypto(key(1),key(2),key(1)));
        assertThrows(IllegalArgumentException.class,()->new CddCrypto(key(1),key(2),key(2)));
    }
    @Test void authenticatedCiphertextCannotMoveBetweenUsersOrDeclarations() {
        var crypto=new CddCrypto(key(1),key(2),"");UUID id=UUID.randomUUID();byte[] plain="가상 고객 신고".getBytes(StandardCharsets.UTF_8);
        byte[] cipher=crypto.encrypt(1,id,plain);assertArrayEquals(plain,crypto.decrypt(1,id,cipher));
        assertFalse(Arrays.equals(cipher,crypto.encrypt(1,id,plain)));
        assertThrows(IllegalStateException.class,()->crypto.decrypt(2,id,cipher));
        assertThrows(IllegalStateException.class,()->crypto.decrypt(1,UUID.randomUUID(),cipher));
        cipher[cipher.length-1]^=1;assertThrows(IllegalStateException.class,()->crypto.decrypt(1,id,cipher));
    }
    @Test void fingerprintIsStableDomainSeparatedAndChangesWithKey() {
        var one=new CddCrypto(key(1),key(2),"");var two=new CddCrypto(key(1),key(3),"");
        assertEquals(one.fingerprint("신원","가상 이름"),one.fingerprint("신원","가상 이름"));
        assertNotEquals(one.fingerprint("신원","가상 이름"),one.fingerprint("신고","가상 이름"));
        assertNotEquals(one.fingerprint("신원","가상 이름"),two.fingerprint("신원","가상 이름"));
        assertNotEquals(one.keyId(),two.keyId());assertFalse(one.toString().contains(key(1)));
    }
}
