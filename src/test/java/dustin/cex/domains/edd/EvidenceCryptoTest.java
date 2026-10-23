package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

/** 공개된 테스트 키만 사용해 문맥 결합·독립 키·손상 거부를 검증한다. */
class EvidenceCryptoTest {
    String key(int n) {byte[] key=new byte[32];key[0]=(byte)n;return Base64.getEncoder().encodeToString(key);}
    EvidenceCrypto crypto() {return new EvidenceCrypto(key(3),key(1),key(2),key(0));}
    @Test void randomizedNonceRoundTripAndRedactedDescription() {
        var c=crypto();byte[] raw={1,2,3},first=c.encrypt("1:자료:ORIGINAL",raw);assertArrayEquals(raw,c.decrypt(c.keyId(),"1:자료:ORIGINAL",first));assertFalse(Arrays.equals(first,c.encrypt("1:자료:ORIGINAL",raw)));assertFalse(c.toString().contains(key(3)));
    }
    @ParameterizedTest @ValueSource(strings={"다른고객","다른사건","다른요청","다른목적","VIEW"})
    void ciphertextCannotMoveAcrossContexts(String context) {var c=crypto();byte[] encrypted=c.encrypt("ORIGINAL",new byte[]{1});assertThrows(IllegalStateException.class,()->c.decrypt(c.keyId(),context,encrypted));}
    @Test void corruptionTruncationAndWrongKeyIdFailClosed() {
        var c=crypto();byte[] encrypted=c.encrypt("자료",new byte[]{1});encrypted[28]^=1;assertThrows(IllegalStateException.class,()->c.decrypt(c.keyId(),"자료",encrypted));assertThrows(IllegalStateException.class,()->c.decrypt("wrong","자료",encrypted));assertThrows(IllegalStateException.class,()->c.decrypt(c.keyId(),"자료",new byte[27]));
    }
    @Test void independentEncryptionKeyIsRequired() {
        for(String other:List.of(key(0),key(1),key(2)))assertThrows(IllegalArgumentException.class,()->new EvidenceCrypto(other,key(1),key(2),key(0)));
        assertThrows(IllegalArgumentException.class,()->new EvidenceCrypto("잘못된키","","",""));assertThrows(IllegalArgumentException.class,()->new EvidenceCrypto(Base64.getEncoder().encodeToString(new byte[16]),"","",""));
    }
    @Test void missingKeyDoesNotOfferFallbackOrPlaintextStorage() {var c=new EvidenceCrypto("","","","");assertEquals(503,assertThrows(ResponseStatusException.class,()->c.encrypt("자료",new byte[1])).getStatusCode().value());}
}
