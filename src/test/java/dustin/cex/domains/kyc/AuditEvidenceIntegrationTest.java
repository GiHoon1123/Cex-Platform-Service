package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dustin.cex.domains.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;

/** 실제 PostgreSQL에서 감사 사건의 제한 내보내기·서명 검증·역할·불변성을 확인한다. */
class AuditEvidenceIntegrationTest extends EddTestSupport {
    @Autowired AuditEvidenceService evidence;

    @Test void boundedExportIsSignedImmutableAndVerifiable(){
        Instant start=Instant.now().minusSeconds(60);audit.record(maker.user().getId(),"EVIDENCE_SAMPLE","sample:1","SUCCESS","NO_SECRET");Instant end=Instant.now().plusSeconds(1);
        var exported=evidence.export(checker.user().getId(),checker.sid(),start,end,"EVIDENCE_SAMPLE");
        assertEquals(64,exported.manifestHash().length());assertEquals(64,exported.signature().length());assertFalse(exported.manifest().contains("security-test-password"));
        var verified=evidence.verify(checker.user().getId(),checker.sid(),exported.id());assertTrue(verified.valid());assertEquals(1,verified.eventCount());
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE audit_evidence_exports SET manifest='{}' WHERE id=?",exported.id()));
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM audit_evidence_exports WHERE id=?",exported.id()));
    }

    @Test void rangeAndVolumeBoundariesFailClosed(){
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->evidence.export(checker.user().getId(),checker.sid(),Instant.now(),Instant.now().minusSeconds(1),null));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->evidence.export(checker.user().getId(),checker.sid(),Instant.now().minus(Duration.ofDays(32)),Instant.now(),null));
        assertThrows(IllegalStateException.class,()->new AuditEvidenceKey("").sign("payload"));
    }

    @Test void httpBoundaryRejectsCustomerAndOrdinaryOperator()throws Exception{
        String body=json.writeValueAsString(Map.of("rangeStart",Instant.now().minusSeconds(60),"rangeEnd",Instant.now().plusSeconds(1),"actionFilter","HTTP_AUTHORIZATION"));
        mvc.perform(post("/api/admin/audit-evidence").header("Authorization","Bearer "+customerAccess).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/audit-evidence").header("Authorization","Bearer "+maker.access()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/audit-evidence").header("Authorization","Bearer "+checker.access()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.signature").isString());
    }
}
