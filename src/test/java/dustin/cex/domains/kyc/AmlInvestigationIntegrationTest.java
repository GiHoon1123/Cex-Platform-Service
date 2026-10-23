package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.edd.*;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.auth.model.entity.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/** 실제 HMAC·격리 DB·운영 MFA와 EDD 서명으로 후보 조사부터 회부까지 검증한다. 외부 공급사 사실 확인은 아니다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class AmlInvestigationIntegrationTest extends EddTestSupport {
    @Autowired AmlInvestigationService investigations;@Autowired AmlSource sources;@Autowired KycReconciliationService reconciliation;
    UUID attempt;Map<String,Object> event;long time;
    JsonNode candidates() {
        return json.readTree("[{\"node_id\":\"aml-1\",\"status\":\"In Review\",\"entity_type\":\"person\",\"score\":85,\"total_hits\":1,\"warnings\":[],\"screened_data\":{\"full_name\":\"가상 검색명\",\"date_of_birth\":\"1990-01-01\",\"nationality\":\"KR\",\"document_number\":\"PRIVATE-NUMBER\"},\"hits\":[{\"id\":\"hit-1\",\"caption\":\"가상 후보\",\"match_score\":96,\"risk_score\":85,\"review_status\":\"Unreviewed\",\"datasets\":[\"PEP\"],\"properties\":{\"name\":[\"가상 후보\"],\"birthDate\":[\"1974\"],\"citizenship\":[\"kr\"],\"passportNumber\":[\"PRIVATE-NUMBER\"]},\"url\":\"http://127.0.0.1:1/PRIVATE-URL\"}]}]");
    }
    void prepare(String status,JsonNode aml) {
        attempt=kyc.start(customer.getId(),sid,UUID.randomUUID()).status().attemptId();time=Instant.now().getEpochSecond()-10;
        var decision=json.createObjectNode();decision.put("session_id",created.get(attempt).toString()).put("status",status);
        decision.set("id_verifications",json.valueToTree(List.of(Map.of("status","Approved","verification_method","document","assurance","documentary","full_name",declaration(CustomerDeclaration.Pep.NO).fullName(),"date_of_birth","1990-01-01","nationality","KOR","document_number","PRIVATE-NUMBER"))));
        decision.set("liveness_checks",json.valueToTree(List.of(Map.of("status","Approved"))));decision.set("face_matches",json.valueToTree(List.of(Map.of("status","Approved"))));if(aml!=null)decision.set("aml_screenings",aml);
        event=new HashMap<>();event.put("event_id",UUID.randomUUID());event.put("vendor_data",attempt.toString());event.put("session_id",created.get(attempt));event.put("application_id",APP);event.put("workflow_id",WORKFLOW);event.put("workflow_version",4);event.put("environment","live");event.put("webhook_type","status.updated");event.put("status",status);event.put("decision",decision);event.put("timestamp",Instant.now().getEpochSecond());event.put("created_at",time);
    }
    String signature(byte[] raw) throws Exception {var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(verifier.canonical(verifier.json.readTree(raw)).getBytes(StandardCharsets.UTF_8)));}
    void send() throws Exception {byte[] raw=json.writeValueAsBytes(event);kyc.webhook(raw,signature(raw),event.get("timestamp").toString());}
    AmlSource.Metadata flagged() throws Exception {prepare("In Review",candidates());send();blocked();return metadata();}
    AmlSource.Metadata metadata() {return investigations.listSources(maker.user().getId(),maker.sid(),customer.getId(),0).getFirst();}
    AmlInvestigationService.Viewed view(AmlSource.Metadata m) {return investigations.inspect(maker.user().getId(),maker.sid(),m.id());}
    AmlInvestigationService.Investigation record(AmlSource.Metadata m,AmlInvestigationService.Viewed v,UUID key,int node,String hit,AmlInvestigationService.Finding finding,String note) {
        return investigations.record(maker.user().getId(),maker.sid(),m.id(),v.viewId(),m.assessmentId(),v.caseRevision(),key,node,hit,finding,note);
    }
    AmlInvestigationService.Investigation memo(AmlSource.Metadata m,AmlInvestigationService.Viewed v) {return record(m,v,UUID.randomUUID(),0,"hit-1",AmlInvestigationService.Finding.IDENTIFIERS_DIFFER,"출생 연도가 달라 추가 확인 필요");}
    long count(String table) {return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Long.class,customer.getId());}

    @ParameterizedTest @ValueSource(strings={"Approved","In Review","Declined"})
    void signedRiskSourceSurvivesEachAggregateStatusWithoutOpeningTrading(String status) throws Exception {
        prepare(status,candidates());send();var m=metadata();var v=view(m);assertEquals(event.get("event_id"),m.eventId());assertEquals("가상 후보",v.source().report().path("screenings").get(0).path("hits").get(0).path("caption").asText());
        assertEquals(AmlSource.ASSURANCE,m.assurance());memo(m,v);blocked();assertNotEquals("CLOSED",service.status(customer.getId()).state());
    }
    @Test void originalNumbersUrlsAndMediaNeverEnterStoredProjectionOrLogs() throws Exception {
        var m=flagged();var v=view(m);assertFalse(v.toString().contains("가상 후보"));assertFalse(v.source().report().toString().contains("PRIVATE"));
        var row=jdbc.queryForMap("SELECT * FROM edd_aml_sources WHERE id=?",m.id());byte[] cipher=(byte[])row.remove("report_cipher");assertFalse(new String(cipher,StandardCharsets.UTF_8).contains("가상 후보"));assertFalse(row.toString().contains("가상 후보"));assertFalse(jdbc.queryForObject("SELECT string_agg(detail,',') FROM security_audit WHERE actor_id=?",String.class,customer.getId()).contains("PRIVATE"));
    }
    @Test void unverifiedSignatureCannotCreateSourceAssessmentOrCase() {
        prepare("In Review",candidates());byte[] raw=json.writeValueAsBytes(event);assertThrows(ResponseStatusException.class,()->kyc.webhook(raw,"0".repeat(64),event.get("timestamp").toString()));assertEquals(0,count("edd_aml_sources"));assertNull(cdd.status(customer.getId()).assessmentId());
    }
    @Test void duplicateWebhookDoesNotCreateDuplicateSources() throws Exception {flagged();send();assertEquals(1,count("edd_aml_sources"));}
    @Test void sourceCaptureAuditFailureRollsBackEventAssessmentAndCase() throws Exception {
        prepare("In Review",candidates());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_AML_SOURCE_CAPTURED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::send);
        assertEquals(0,count("edd_aml_sources"));assertNull(cdd.status(customer.getId()).assessmentId());assertNull(service.status(customer.getId()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM kyc_events WHERE attempt_id=?",Long.class,attempt));reset(audit);send();assertEquals(1,count("edd_aml_sources"));
    }
    @Test void sourceInspectionAuditFailureRollsBackReceiptAndReturnsNoContent() throws Exception {
        var m=flagged();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_AML_SOURCE_VIEWED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->view(m));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM edd_aml_source_views WHERE source_id=?",Long.class,m.id()));
    }
    @Test void investigationAuditFailureRollsBackNotesAndMetadata() throws Exception {
        var m=flagged();var v=view(m);doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_AML_INVESTIGATION_RECORDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->memo(m,v));assertEquals(0,count("edd_aml_investigations"));
    }
    @Test void repeatedKeyReusesExactNotesAndTargetButChangedNotesConflict() throws Exception {
        var m=flagged();var v=view(m);UUID key=UUID.randomUUID();var one=record(m,v,key,0,"hit-1",AmlInvestigationService.Finding.POSSIBLE_MATCH,"추가 확인 필요");assertEquals(one,record(m,v,key,0,"hit-1",AmlInvestigationService.Finding.POSSIBLE_MATCH,"추가 확인 필요"));assertThrows(ResponseStatusException.class,()->record(m,v,key,0,"hit-1",AmlInvestigationService.Finding.POSSIBLE_MATCH,"변경된 의견"));assertEquals(1,count("edd_aml_investigations"));
    }
    @Test void simultaneousSameKeyNeverAddsTwoInvestigations() throws Exception {
        var m=flagged();var v=view(m);UUID key=UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var results=new ArrayList<Future<UUID>>();for(int i=0;i<2;i++)results.add(pool.submit(()->{gate.await();return record(m,v,key,0,"hit-1",AmlInvestigationService.Finding.POSSIBLE_MATCH,"추가 확인 필요").payload().id();}));gate.countDown();assertEquals(results.get(0).get(12,TimeUnit.SECONDS),results.get(1).get(12,TimeUnit.SECONDS));}assertEquals(1,count("edd_aml_investigations"));
    }
    @Test void nonexistentHitCannotBeInventedAndNoPartialRecordRemains() throws Exception {var m=flagged();var v=view(m);assertThrows(ResponseStatusException.class,()->record(m,v,UUID.randomUUID(),0,"invented",AmlInvestigationService.Finding.POSSIBLE_MATCH,"의견"));assertEquals(0,count("edd_aml_investigations"));}
    @Test void duplicateHitWithinSameNodeIsAmbiguousAndRejected() throws Exception {
        var nodes=candidates();var hits=(tools.jackson.databind.node.ArrayNode)nodes.get(0).path("hits");hits.add(hits.get(0).deepCopy());prepare("In Review",nodes);send();var m=metadata();var v=view(m);assertThrows(ResponseStatusException.class,()->memo(m,v));
    }
    @Test void sameHitInDifferentNodesIsBoundToSpecificNode() throws Exception {
        var nodes=(tools.jackson.databind.node.ArrayNode)candidates();var second=nodes.get(0).deepCopy();((ObjectNode)second).put("node_id","aml-2");nodes.add(second);prepare("In Review",nodes);send();var m=metadata();var v=view(m);
        var a=record(m,v,UUID.randomUUID(),0,"hit-1",AmlInvestigationService.Finding.POSSIBLE_MATCH,"첫 검색");var b=record(m,v,UUID.randomUUID(),1,"hit-1",AmlInvestigationService.Finding.POSSIBLE_MATCH,"둘째 검색");assertNotEquals(a.payload().screeningIndex(),b.payload().screeningIndex());
    }
    @Test void missingAmlCanOnlyBeRecordedAsInsufficientInformation() throws Exception {
        prepare("Approved",null);send();var m=metadata();var v=view(m);assertTrue(v.source().report().path("screenings").isNull());assertThrows(ResponseStatusException.class,()->record(m,v,UUID.randomUUID(),-1,null,AmlInvestigationService.Finding.IDENTIFIERS_DIFFER,"근거 없음"));record(m,v,UUID.randomUUID(),-1,null,AmlInvestigationService.Finding.INSUFFICIENT_INFORMATION,"공급사 검색 근거 없음");blocked();
    }
    @Test void missingHitCanOnlyHaveInsufficientInformationFinding() throws Exception {var m=flagged();var v=view(m);assertThrows(ResponseStatusException.class,()->record(m,v,UUID.randomUUID(),0,null,AmlInvestigationService.Finding.POSSIBLE_MATCH,"근거 없음"));record(m,v,UUID.randomUUID(),0,null,AmlInvestigationService.Finding.INSUFFICIENT_INFORMATION,"추가 후보 자료 필요");}
    @Test void anotherOperatorsReceiptCannotAuthorizeThisWriter() throws Exception {
        var m=flagged();var other=account(UserRole.OPERATOR);var v=investigations.inspect(other.user().getId(),other.sid(),m.id());assertThrows(ResponseStatusException.class,()->memo(m,v));
    }
    @Test void sourceFromAnotherCustomerCannotUseCurrentCustomersReceipt() throws Exception {var m=flagged();var v=view(m);var other=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());var wrong=new AmlSource.Metadata(m.id(),m.caseId(),other.getId(),m.assessmentId(),m.eventId(),m.statusDigest(),m.providerTime(),m.contentMac(),m.keyId(),m.policy(),m.assurance());assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->sources.content(wrong)));assertEquals(0,count("edd_aml_investigations"));}
    @Test void changedMetadataFailsSourceIntegrityWithSanitizedError() throws Exception {
        var m=flagged();var wrong=new AmlSource.Metadata(m.id(),m.caseId(),m.userId(),UUID.randomUUID(),m.eventId(),m.statusDigest(),m.providerTime(),m.contentMac(),m.keyId(),m.policy(),m.assurance());var error=assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->sources.content(wrong)));assertNull(error.getCause());assertFalse(error.getMessage().contains("가상"));
    }
    @Test void staleMfaCannotReadOrWriteSource() throws Exception {var m=flagged();var v=view(m);jdbc.update("UPDATE refresh_tokens SET mfa_verified_at=localtimestamp-interval '6 minutes' WHERE session_id=?",maker.sid());assertThrows(ResponseStatusException.class,()->view(m));assertThrows(ResponseStatusException.class,()->memo(m,v));}
    @Test void changingWriterVersionInvalidatesOldReceipt() throws Exception {var m=flagged();var v=view(m);var u=users.findById(maker.user().getId()).orElseThrow();u.setEmail(UUID.randomUUID()+"@test.invalid");users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->memo(m,v));}
    @Test void newDeclarationPreventsInvestigationAgainstOldAssessment() throws Exception {var m=flagged();var v=view(m);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));assertThrows(ResponseStatusException.class,()->memo(m,v));assertEquals(m.id(),view(m).source().metadata().id());}
    @Test void newProviderDataPreservesOldSourceButRejectsItsNewInvestigation() throws Exception {
        var m=flagged();var v=view(m);event.put("event_id",UUID.randomUUID());event.put("created_at",time+1);((ObjectNode)((JsonNode)event.get("decision")).path("aml_screenings").get(0).path("hits").get(0)).put("caption","새 후보 이름");send();assertEquals(2,count("edd_aml_sources"));assertThrows(ResponseStatusException.class,()->memo(m,v));assertEquals("가상 후보",view(m).source().report().path("screenings").get(0).path("hits").get(0).path("caption").asText());
    }
    @Test void staleEventCannotCreateNewSource() throws Exception {flagged();event.put("event_id",UUID.randomUUID());event.put("created_at",time-1);send();assertEquals(1,count("edd_aml_sources"));}
    @Test void changedDuplicateEventBlocksNewInvestigationWithoutOverwritingSource() throws Exception {var m=flagged();var v=view(m);((ObjectNode)((JsonNode)event.get("decision")).path("aml_screenings").get(0)).put("score",99);send();assertEquals(1,count("edd_aml_sources"));assertThrows(ResponseStatusException.class,()->memo(m,v));blocked();}
    @Test void mismatchedDecisionSessionCannotBecomeInvestigationEvidence() throws Exception {prepare("In Review",candidates());((ObjectNode)event.get("decision")).put("session_id",UUID.randomUUID().toString());send();assertEquals(0,count("edd_aml_sources"));assertNull(cdd.status(customer.getId()).assessmentId());blocked();}
    @Test void auditAndMetadataExcludePlaintextNotesAndApprovedReferralRetainsHold() throws Exception {
        var m=flagged();var v=view(m);var a=memo(m,v);assertEquals("출생 연도가 달라 추가 확인 필요",investigations.inspectInvestigation(checker.user().getId(),checker.sid(),a.payload().id()).notes());var report=propose(EddService.Finding.COMPLIANCE_ESCALATION);
        var items=service.inspect(checker.user().getId(),checker.sid(),report.payload().id(),0).dossier().items();assertTrue(items.stream().anyMatch(x->x.path("kind").asText().equals("AML_SOURCE")));assertTrue(items.stream().anyMatch(x->x.path("kind").asText().equals("AML_INVESTIGATION")));String metadata=json.writeValueAsString(items);assertFalse(metadata.contains("출생"));assertFalse(metadata.contains("가상 후보"));
        decide(report,true,proof(checker,report,true));assertEquals("REFERRED",service.status(customer.getId()).state());blocked();
    }
    @Test void newInvestigationInvalidatesOldReferralSignatureWithoutChangingCaseRevision() throws Exception {
        var m=flagged();var v=view(m);var old=propose(EddService.Finding.COMPLIANCE_ESCALATION);var signed=proof(checker,old,true);long revision=service.status(customer.getId()).revision();memo(m,v);assertEquals(revision,service.status(customer.getId()).revision());assertThrows(ResponseStatusException.class,()->decide(old,true,signed));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,signed.challengeId()));var fresh=propose(EddService.Finding.COMPLIANCE_ESCALATION);assertNotEquals(old.payload().dossier().digest(),fresh.payload().dossier().digest());decide(fresh,true,proof(checker,fresh,true));blocked();
    }
    @ParameterizedTest @NullSource @ValueSource(strings={""," ","가\u202E","a\t"})
    void invalidNotesNeverCreateInvestigation(String notes) throws Exception {var m=flagged();var v=view(m);assertThrows(ResponseStatusException.class,()->record(m,v,UUID.randomUUID(),0,"hit-1",AmlInvestigationService.Finding.POSSIBLE_MATCH,notes));assertEquals(0,count("edd_aml_investigations"));}
    @Test void oversizedNotesAndInvalidNodeAreRejected() throws Exception {var m=flagged();var v=view(m);assertThrows(ResponseStatusException.class,()->record(m,v,UUID.randomUUID(),0,"hit-1",AmlInvestigationService.Finding.POSSIBLE_MATCH,"가".repeat(2001)));assertThrows(ResponseStatusException.class,()->record(m,v,UUID.randomUUID(),10,"hit-1",AmlInvestigationService.Finding.POSSIBLE_MATCH,"의견"));}
    @Test void investigationReadAuditFailureDoesNotReturnNotes() throws Exception {var m=flagged();var a=memo(m,view(m));doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_AML_INVESTIGATION_VIEWED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->investigations.inspectInvestigation(checker.user().getId(),checker.sid(),a.payload().id()));}
    @Test void allNewRecordsRejectUpdateDeleteAndTruncate() throws Exception {var m=flagged();memo(m,view(m));for(String table:List.of("edd_aml_sources","edd_aml_source_views","edd_aml_investigations"))for(String sql:List.of("UPDATE "+table+" SET id=id","DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));}
    @Test void customerAndAuditorCannotReadOriginalsWhileAuditorCanReadMetadata() throws Exception {
        var m=flagged();var a=account(UserRole.AUDITOR);String path="/api/admin/edd/aml-sources/"+m.id()+"/inspect";
        mvc.perform(post(path).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());mvc.perform(post(path).header("Authorization","Bearer "+a.access())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/edd/customers/"+customer.getId()+"/aml-sources").header("Authorization","Bearer "+a.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void actualHttpWriterAndApproverUseSameStoredInvestigation() throws Exception {
        var m=flagged();var v=view(m);var p=new AmlInvestigationController.Proposal(v.viewId(),m.assessmentId(),v.caseRevision(),0,"hit-1",AmlInvestigationService.Finding.POSSIBLE_MATCH,"추가 확인 필요");
        String raw=mvc.perform(post("/api/admin/edd/aml-sources/"+m.id()+"/investigations").header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(p))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertFalse(raw.contains("추가 확인 필요"));String id=json.readTree(raw).path("payload").path("id").asText();
        mvc.perform(post("/api/admin/edd/aml-investigations/"+id+"/inspect").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/admin/edd/aml-sources/"+m.id()+"/investigations").header("Authorization","Bearer "+checker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(p))).andExpect(status().isForbidden());
    }
    JsonNode delivery(Map<String,Object> body) throws Exception {
        byte[] raw=json.writeValueAsBytes(body);return json.valueToTree(Map.of("session_id",created.get(attempt).toString(),"webhook_type",body.get("webhook_type"),
            "request_body",new String(raw,StandardCharsets.UTF_8),"request_header",json.writeValueAsString(Map.of("X-Signature-V2",signature(raw),"X-Timestamp",body.get("timestamp").toString()))));
    }
    void currentDecision(JsonNode nodes) {
        var d=((JsonNode)event.get("decision")).deepCopy();var o=(ObjectNode)d;o.put("session_kind","user").put("vendor_data",attempt.toString()).put("workflow_id",WORKFLOW.toString()).put("environment","live");if(nodes!=null)o.set("aml_screenings",nodes);
        when(client.decision(created.get(attempt))).thenReturn(d);
    }
    void reconcile() {jdbc.update("UPDATE kyc_reconciliations SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE attempt_id=?",attempt);reconciliation.reconcileDue();}
    @Test void missedInReviewWebhookIsRecoveredFromActualArchivedSignature() throws Exception {
        prepare("In Review",candidates());var original=delivery(event);currentDecision(null);when(client.deliveries(created.get(attempt))).thenReturn(json.createArrayNode().add(original));reconcile();
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT outcome FROM kyc_reconciliations WHERE attempt_id=?",String.class,attempt));assertEquals(1,count("edd_aml_sources"));memo(metadata(),view(metadata()));blocked();
    }
    @Test void nonemptyCandidateChangeWithoutMatchingSignedEventFailsReconciliation() throws Exception {
        flagged();var original=delivery(event);var changed=candidates();((ObjectNode)changed.get(0).path("hits").get(0)).put("caption","변경된 후보");currentDecision(changed);when(client.deliveries(created.get(attempt))).thenReturn(json.createArrayNode().add(original));reconcile();
        assertEquals("EVIDENCE_MISMATCH",jdbc.queryForObject("SELECT outcome FROM kyc_reconciliations WHERE attempt_id=?",String.class,attempt));assertEquals(1,count("edd_aml_sources"));blocked();
    }
    @Test void nonexistentAndExpiredReceiptCannotCreateMemo() throws Exception {
        var m=flagged();var u=users.findById(maker.user().getId()).orElseThrow();long revision=service.status(customer.getId()).revision();
        var missing=new AmlInvestigationService.Viewed(UUID.randomUUID(),revision,null);assertThrows(ResponseStatusException.class,()->memo(m,missing));UUID id=UUID.randomUUID();
        // 과거 제공의 시간 경계만 시험하며 실제 불변 제공 이력을 변경하지 않는다.
        jdbc.update("INSERT INTO edd_aml_source_views(id,source_id,actor_id,actor_person,actor_version,session_id,case_revision,content_mac,viewed_at) VALUES(?,?,?,?,?,?,?,?,clock_timestamp()-interval '6 minutes')",id,m.id(),u.getId(),u.getOperatorPersonId(),u.getVersion(),maker.sid(),revision,m.contentMac());
        var expired=new AmlInvestigationService.Viewed(id,revision,null);assertThrows(ResponseStatusException.class,()->memo(m,expired));assertEquals(0,count("edd_aml_investigations"));
    }
    @Test void normalCustomerHasNoAdditionalAmlInvestigationOrEddCase() throws Exception {verify(false);allowed();assertTrue(investigations.listSources(maker.user().getId(),maker.sid(),customer.getId(),0).isEmpty());assertNull(service.status(customer.getId()));}

}
