package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Python 공식 정규화 방식으로 독립 생성한 fixture와 Java 결과를 비교한다. */
class DiditWebhookVerifierTest {
    static final String SECRET="isolated-didit-webhook-secret-at-least-32-characters";
    static DiditSettings settings(String environment) {return new DiditSettings("isolated-api-key-at-least-16",SECRET,
        "11111111-2222-3333-4444-555555555555","66666666-7777-8888-9999-000000000000",4,environment,
        "https://exchange.example.test/kyc/return",Base64.getEncoder().encodeToString(new byte[32]));}
    @Test void pythonCanonicalFixturesMatchIncludingUnicodeEmptyObjectsAndFloatingPoint() throws Exception {
        var v=new DiditWebhookVerifier(settings("live"));
        try(var stream=getClass().getResourceAsStream("/didit-v2-canonical-fixtures.json")) {
            var cases=v.json.readTree(stream);assertEquals(300,cases.size());
            for(var fixture:cases) {
                String canonical=v.canonical(v.json.readTree(fixture.path("raw").asText()));assertEquals(fixture.path("canonical").asText(),canonical,fixture.path("raw").asText());
                var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
                assertEquals(fixture.path("signature").asText(),HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
            }
        }
    }
    @Test void duplicateKeysInvalidSurrogatesAndOversizedBodiesAreRejected() {
        var v=new DiditWebhookVerifier(settings("live"));String now=Long.toString(Instant.now().getEpochSecond());
        for(byte[] body:List.of(("{\"timestamp\":"+now+",\"timestamp\":"+now+"}").getBytes(StandardCharsets.UTF_8),new byte[1048577],"{bad}".getBytes(StandardCharsets.UTF_8)))
            assertThrows(ResponseStatusException.class,()->v.verify(body,"0".repeat(64),now));
        assertThrows(ResponseStatusException.class,()->v.canonical(v.json.readTree("\"\\ud800\"")));
    }
    @Test void signatureAndSignedTimestampBothMustMatchFreshHeader() throws Exception {
        var v=new DiditWebhookVerifier(settings("live"));long now=Instant.now().getEpochSecond();
        byte[] body=("{\"timestamp\":"+now+",\"decision\":{}}").getBytes(StandardCharsets.UTF_8);
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String sig=HexFormat.of().formatHex(mac.doFinal(v.canonical(v.json.readTree(body)).getBytes(StandardCharsets.UTF_8)));
        assertNotNull(v.verify(body,sig,Long.toString(now)));
        assertThrows(ResponseStatusException.class,()->v.verify(body,"0".repeat(64),Long.toString(now)));
        assertThrows(ResponseStatusException.class,()->v.verify(body,sig,Long.toString(now+1)));
        assertThrows(ResponseStatusException.class,()->v.verify(body,sig,Long.toString(now-301)));
        assertThrows(ResponseStatusException.class,()->v.verify(body,sig,Long.toString(now+301)));
        assertThrows(ResponseStatusException.class,()->v.verify(body,null,Long.toString(now)));
    }
    @Test void missingOrPartialConfigurationNeverCreatesAnAuthenticationBypass() {
        var empty=new DiditSettings("","","","",0,"","","");assertFalse(empty.configured());
        assertThrows(ResponseStatusException.class,()->new DiditWebhookVerifier(empty).verify(new byte[0],null,null));
        assertThrows(IllegalArgumentException.class,()->new DiditSettings("some-key","","","",0,"","",""));
        assertThrows(IllegalArgumentException.class,()->new DiditSettings("isolated-api-key-at-least-16",SECRET,"11111111-2222-3333-4444-555555555555",
            "66666666-7777-8888-9999-000000000000",4,"live","http://evil.example",Base64.getEncoder().encodeToString(new byte[32])));
        assertEquals("DiditSettings[비밀 생략]",settings("sandbox").toString());
    }
}
