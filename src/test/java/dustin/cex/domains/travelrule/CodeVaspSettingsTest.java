package dustin.cex.domains.travelrule;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 실제 공급사 접속 없이 고정 주소/등록 공개키와 비밀 없는 설정 오류를 검증한다. */
class CodeVaspSettingsTest {
    final String key=Base64.getEncoder().encodeToString(new byte[32]);
    @Test void absentConfigurationIsDisabledAndHasNoDefaultLiveAddress(){var settings=new CodeVaspSettings("","","","");assertFalse(settings.configured());assertNull(settings.api);assertNull(settings.cipher);}
    @Test void explicitHttpsAndPrivateComposeCipherAreAccepted(){var settings=new CodeVaspSettings("https://trapi-dev.codevasp.com","http://code-cipher:8787","cex-study",key);assertTrue(settings.configured());assertFalse(settings.toString().contains("trapi-dev"));}
    @Test void partialUnsafeOrUnboundedConfigurationIsRejectedWithoutEcho(){
        for(String[] values:List.of(new String[]{"","","entity",key},new String[]{"http://outside.example","http://127.0.0.1:1","entity",key},new String[]{"https://user:secret@example.test","http://127.0.0.1:1","entity",key},new String[]{"https://example.test:65536","http://127.0.0.1:1","entity",key},new String[]{"https://example.test/path","http://127.0.0.1:1","entity",key},new String[]{"https://example.test?secret=x","http://127.0.0.1:1","entity",key},new String[]{"https://example.test","http://outside.example","entity",key},new String[]{"https://example.test","http://127.0.0.1:1","entity\r\nInjected: x",key},new String[]{"https://example.test","http://127.0.0.1:1","entity","private-secret"})){
            var error=assertThrows(IllegalArgumentException.class,()->new CodeVaspSettings(values[0],values[1],values[2],values[3]));assertFalse(error.getMessage().contains("secret"));assertNull(error.getCause());
        }
    }
    @Test void publicKeyUsesCanonicalThirtyTwoByteBase64(){for(String invalid:List.of(Base64.getEncoder().encodeToString(new byte[31]),Base64.getEncoder().encodeToString(new byte[33]),key.substring(0,43),"not-a-key"))assertThrows(IllegalArgumentException.class,()->new CodeVaspSettings("https://example.test","http://127.0.0.1:1","entity",invalid));}
}
