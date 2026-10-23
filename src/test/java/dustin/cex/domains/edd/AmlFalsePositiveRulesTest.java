package dustin.cex.domains.edd;

import static org.junit.jupiter.api.Assertions.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;

/** 공급사 오탐 표시와 실제 자료 형식·대상 일치·완전한 생일 차이를 분리해 검증한다. */
class AmlFalsePositiveRulesTest {
    final ObjectMapper json=JsonMapper.builder().build();
    ObjectNode report() {return (ObjectNode)json.readTree("{\"identity\":[{\"status\":\"Approved\",\"full_name\":\"가상 고객\",\"date_of_birth\":\"1990-01-01\",\"nationality\":\"KOR\"}],\"screenings\":[{\"node_id\":\"aml-1\",\"status\":\"Approved\",\"total_hits\":1,\"entity_type\":\"person\",\"score\":0,\"warnings\":[],\"screened_data\":{\"full_name\":\"가상 고객\",\"date_of_birth\":\"1990-01-01\",\"nationality\":\"KR\"},\"hits\":[{\"id\":\"hit-1\",\"caption\":\"가상 후보\",\"review_status\":\"False Positive\",\"properties\":{\"birthDate\":[\"1974-01-01\"]}}]}]}");}
    JsonNode node(ObjectNode r) {return r.path("screenings").get(0);}
    JsonNode hit(ObjectNode r) {return node(r).path("hits").get(0);}
    @Test void completeDistinctBirthAndProviderFalsePositiveAreEligibleData() {var c=AmlFalsePositiveRules.inspect(report(),true);assertTrue(c.valid());assertEquals("aml-1",c.candidates().getFirst().key().node());assertEquals(0,c.candidates().getFirst().screening());assertFalse(c.toString().contains("1990"));}
    @Test void historyMayRetainUnreviewedButCurrentCannot() {var r=report();((ObjectNode)node(r)).put("status","In Review").put("score",85);((ObjectNode)hit(r)).put("review_status","Unreviewed");assertTrue(AmlFalsePositiveRules.inspect(r,false).valid());assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @ParameterizedTest @ValueSource(strings={"Unreviewed","Confirmed Match","Inconclusive","False positive",""})
    void unqualifiedCandidateStatusNeverClears(String value) {var r=report();((ObjectNode)hit(r)).put("review_status",value);assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @ParameterizedTest @ValueSource(strings={"1990-01-01","1974","1974-01","1974-02-30","1974-1-1","9999-01-01",""})
    void equalPartialOrInvalidBirthCannotBeDistinctEvidence(String value) {var r=report();((ObjectNode)hit(r).path("properties")).set("birthDate",json.createArrayNode().add(value));assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @Test void oneEqualBirthAmongSeveralCannotBeIgnored() {var r=report();((ArrayNode)hit(r).path("properties").path("birthDate")).add("1990-01-01");assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @ParameterizedTest @ValueSource(strings={"full_name","date_of_birth","nationality"})
    void searchedPersonMustEqualVerifiedPerson(String field) {var r=report();((ObjectNode)node(r).path("screened_data")).put(field,field.equals("date_of_birth")?"1980-01-01":field.equals("nationality")?"US":"다른 사람");assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @ParameterizedTest @ValueSource(strings={"identity","screenings"})
    void missingOrEmptyRootArraysNeverClear(String field) {var r=report();r.remove(field);assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());r.set(field,json.createArrayNode());assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @ParameterizedTest @ValueSource(strings={"node_id","status","total_hits","entity_type","score","warnings","hits","screened_data"})
    void eachRequiredScreeningFieldMatters(String field) {var r=report();((ObjectNode)node(r)).remove(field);assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @Test void countsCannotHideAnExtraCandidate() {var r=report();((ObjectNode)node(r)).put("total_hits",0);assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @Test void noRemainingCandidateDoesNotProveHistoryResolved() {var r=report();((ObjectNode)node(r)).put("total_hits",0).set("hits",json.createArrayNode());assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @Test void warningNeverDisappearsBehindZeroScore() {var r=report();((ArrayNode)node(r).path("warnings")).addObject().put("risk","REVIEW_REQUIRED");assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @Test void positiveOrInvalidScoreCannotClear() {for(int value:new int[]{1,-1,101}){var r=report();((ObjectNode)node(r)).put("score",value);assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}}
    @Test void duplicateNodeIdIsAmbiguous() {var r=report();((ArrayNode)r.path("screenings")).add(node(r).deepCopy());assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @Test void duplicateHitInOneNodeIsAmbiguous() {var r=report();((ArrayNode)node(r).path("hits")).add(hit(r).deepCopy());((ObjectNode)node(r)).put("total_hits",2);assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @Test void sameHitInDifferentNodesHasDifferentKeys() {var r=report();var n=node(r).deepCopy();((ObjectNode)n).put("node_id","aml-2");((ArrayNode)r.path("screenings")).add(n);var c=AmlFalsePositiveRules.inspect(r,true);assertTrue(c.valid());assertNotEquals(c.candidates().get(0).key(),c.candidates().get(1).key());}
    @Test void allSignedIdentityEntriesMustAgree() {var r=report();var identity=r.path("identity").get(0).deepCopy();((ObjectNode)identity).put("full_name","다른 사람");((ArrayNode)r.path("identity")).add(identity);assertFalse(AmlFalsePositiveRules.inspect(r,true).valid());}
    @Test void candidateListCannotBeMutatedByCaller() {var c=AmlFalsePositiveRules.inspect(report(),true);assertThrows(UnsupportedOperationException.class,()->c.candidates().clear());}
    @Test void oldConfirmedMatchRequiresEscalationEvenWithLaterDateEvidence() {var r=report();((ObjectNode)hit(r)).put("review_status","Confirmed Match");assertEquals("CONFIRMED_MATCH_REQUIRES_ESCALATION",AmlFalsePositiveRules.inspect(r,false).failure());}
}
