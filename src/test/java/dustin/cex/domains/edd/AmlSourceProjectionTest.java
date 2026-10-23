package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** 허용 목록 투영이 원문 번호·미디어·임의 속성을 복사하지 않고 자료 부족을 드러내는지 확인한다. */
class AmlSourceProjectionTest {
    final ObjectMapper json=JsonMapper.builder().build();
    final AmlSource source=new AmlSource(null,null,json,null);
    @Test void missingAmlRemainsMissingInsteadOfInventingClearResult() {var p=source.project(json.createObjectNode());assertTrue(p.path("screenings").isNull());assertTrue(p.path("identity").isEmpty());}
    @Test void emptyAmlArrayIsPreserved() {assertTrue(source.project(json.readTree("{\"aml_screenings\":[]}")).path("screenings").isEmpty());}
    @Test void identityAndScreenedDataNeverCopyDocumentNumbersOrImages() {
        var input=json.readTree("{\"id_verifications\":[{\"full_name\":\"가상 인물\",\"document_number\":\"SECRET-NUMBER\",\"front_image\":\"SECRET-IMAGE\"}],\"aml_screenings\":[{\"screened_data\":{\"full_name\":\"가상 인물\",\"document_number\":\"SECRET-NUMBER\"}}]}");
        String output=json.writeValueAsString(source.project(input));assertTrue(output.contains("가상 인물"));assertFalse(output.contains("SECRET"));assertFalse(output.contains("document_number"));
    }
    @Test void propertiesUseExplicitNameAliasBirthDateCitizenshipAllowlist() {
        var input=json.readTree("{\"aml_screenings\":[{\"hits\":[{\"id\":\"hit-1\",\"caption\":\"가상 후보\",\"properties\":{\"name\":[\"가상 후보\"],\"birthDate\":[\"1974\"],\"citizenship\":[\"kr\"],\"passportNumber\":[\"SECRET\"]},\"url\":\"https://private.invalid/SECRET\",\"additional_information\":{\"secret\":true}}]}]}");
        var p=source.project(input);var hit=p.path("screenings").get(0).path("hits").get(0);assertEquals("1974",hit.path("properties").path("birthDate").get(0).asText());assertFalse(json.writeValueAsString(p).contains("SECRET"));assertFalse(hit.has("url"));
    }
    @Test void ambiguousArrayTypesBecomeNullRatherThanCoercedNames() {var p=source.project(json.readTree("{\"aml_screenings\":[{\"hits\":[{\"datasets\":[1],\"properties\":{\"name\":\"not-an-array\"}}]}]}"));var h=p.path("screenings").get(0).path("hits").get(0);assertTrue(h.path("datasets").isNull());assertTrue(h.path("properties").path("name").isNull());}
    @Test void unknownObjectInScalarSlotIsNotCopied() {var p=source.project(json.readTree("{\"aml_screenings\":[{\"score\":{\"secret\":\"PRIVATE\"}}]}"));assertTrue(p.path("screenings").get(0).path("score").isNull());assertFalse(p.toString().contains("PRIVATE"));}
    @Test void noCandidateReviewStatusIsInvented() {var p=source.project(json.readTree("{\"aml_screenings\":[{\"hits\":[{\"id\":\"a\"}]}]}"));assertTrue(p.path("screenings").get(0).path("hits").get(0).path("review_status").isNull());}
    @Test void scoresArePreservedWithoutThresholdClassification() {var p=source.project(json.readTree("{\"aml_screenings\":[{\"score\":85,\"hits\":[{\"match_score\":96,\"risk_score\":85}]}]}"));assertEquals(85,p.path("screenings").get(0).path("score").intValue());assertEquals(96,p.path("screenings").get(0).path("hits").get(0).path("match_score").intValue());}
    @Test void emptyWarningsAreDifferentFromAbsentWarnings() {var p=source.project(json.readTree("{\"aml_screenings\":[{\"warnings\":[]},{}]}"));assertTrue(p.path("screenings").get(0).path("warnings").isArray());assertTrue(p.path("screenings").get(1).path("warnings").isNull());}
    @Test void projectionHasIndependentMutableContainers() {var input=json.readTree("{\"aml_screenings\":[{\"hits\":[{\"datasets\":[\"PEP\"]}]}]}");var p=source.project(input);((tools.jackson.databind.node.ArrayNode)input.path("aml_screenings").get(0).path("hits").get(0).path("datasets")).add("changed");assertEquals(1,p.path("screenings").get(0).path("hits").get(0).path("datasets").size());}
    @Test void captureRequiresBusinessTransaction() {assertThrows(IllegalStateException.class,()->source.capture(1,java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),"x",json.createObjectNode()));}
}
