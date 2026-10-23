package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.edd.*;
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
import tools.jackson.databind.node.*;

/** 실제 공급사 서명·후보 조사·독립 보안키와 금융 정책으로 제한적 종결 및 주문 재개를 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class AmlResolutionIntegrationTest extends EddTestSupport {
    @Autowired AmlInvestigationService investigations;@Autowired EvidenceService evidence;@Autowired AmlResolutionPolicy resolutionPolicy;
    UUID attempt;Map<String,Object> event;ObjectNode decision;ArrayNode nodes;long time;
    void startRisk(int hits) throws Exception {
        attempt=kyc.start(customer.getId(),sid,UUID.randomUUID()).status().attemptId();time=Instant.now().getEpochSecond()-20;
        decision=json.createObjectNode();decision.put("session_id",created.get(attempt).toString()).put("status","In Review");
        decision.set("id_verifications",json.valueToTree(List.of(Map.of("status","Approved","verification_method","document","assurance","documentary","full_name",declaration(CustomerDeclaration.Pep.NO).fullName(),"date_of_birth","1990-01-01","nationality","KOR"))));
        decision.set("liveness_checks",json.valueToTree(List.of(Map.of("status","Approved"))));decision.set("face_matches",json.valueToTree(List.of(Map.of("status","Approved"))));
        nodes=decision.putArray("aml_screenings");var node=nodes.addObject();node.put("node_id","aml-1").put("status","In Review").put("entity_type","person").put("score",85).put("total_hits",hits);node.putArray("warnings");node.putObject("screened_data").put("full_name",declaration(CustomerDeclaration.Pep.NO).fullName()).put("date_of_birth","1990-01-01").put("nationality","KR");
        var candidates=node.putArray("hits");for(int i=0;i<hits;i++){var hit=candidates.addObject();hit.put("id","hit-"+i).put("caption","가상 후보 "+i).put("review_status","Unreviewed");hit.putArray("datasets").add("PEP");hit.putObject("properties").putArray("birthDate").add("1974-01-01");}
        event=new HashMap<>();event.put("vendor_data",attempt.toString());event.put("session_id",created.get(attempt));event.put("application_id",APP);event.put("workflow_id",WORKFLOW);event.put("workflow_version",4);event.put("environment","live");event.put("webhook_type","status.updated");event.put("decision",decision);send("In Review");blocked();
    }
    void send(String status) throws Exception {
        decision.put("status",status);event.put("status",status);event.put("event_id",UUID.randomUUID());event.put("created_at",time++);event.put("timestamp",Instant.now().getEpochSecond());
        byte[] raw=json.writeValueAsBytes(event);var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));String signature=HexFormat.of().formatHex(mac.doFinal(verifier.canonical(verifier.json.readTree(raw)).getBytes(StandardCharsets.UTF_8)));kyc.webhook(raw,signature,event.get("timestamp").toString());
    }
    void providerResolves() throws Exception {for(var n:nodes){((ObjectNode)n).put("status","Approved").put("score",0);for(var hit:n.path("hits"))((ObjectNode)hit).put("review_status","False Positive");}send("Approved");blocked();}
    AmlSource.Metadata source() {return investigations.listSources(maker.user().getId(),maker.sid(),customer.getId(),0).getFirst();}
    void investigateAll() {
        var source=source();var view=investigations.inspect(maker.user().getId(),maker.sid(),source.id());int i=0;
        for(var n:nodes){for(var hit:n.path("hits"))investigations.record(maker.user().getId(),maker.sid(),source.id(),view.viewId(),source.assessmentId(),view.caseRevision(),UUID.randomUUID(),i,hit.path("id").asText(),AmlInvestigationService.Finding.IDENTIFIERS_DIFFER,"공급사 오탐 상태와 완전한 생일 차이를 확인함");i++;}
    }
    AmlResolutionPolicy.Eligibility eligibility() {return service.amlResolution(maker.user().getId(),maker.sid(),customer.getId());}
    EddService.Report resolution(Instant until) {return service.propose(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),UUID.randomUUID(),EddService.Finding.RESOLVED_AML_FALSE_POSITIVES,until);}
    EddService.Report resolution() {return resolution(Instant.now().plusSeconds(3600));}
    void ready(int hits) throws Exception {startRisk(hits);providerResolves();investigateAll();assertTrue(eligibility().eligible());}
    void closeCase() throws Exception {var r=resolution();decide(r,true,proof(checker,r,true));}
    @Test void signedResolutionAndIndependentDecisionReopenNewOrdersWithoutRewritingRiskHistory() throws Exception {
        ready(2);blocked();assertEquals("REVIEW_REQUIRED",cdd.status(customer.getId()).outcome());var r=resolution();assertEquals(2,r.payload().amlResolution().candidates());assertEquals(1,r.payload().amlResolution().historical().size());assertEquals(2,r.payload().amlResolution().investigations().size());decide(r,true,proof(checker,r,true));assertEquals("CLOSED",service.status(customer.getId()).state());allowed();assertEquals("REVIEW_REQUIRED",cdd.status(customer.getId()).outcome());
    }
    @Test void opinionWithoutNewProviderResolutionCannotClose() throws Exception {startRisk(1);investigateAll();assertFalse(eligibility().eligible());assertThrows(ResponseStatusException.class,this::resolution);blocked();}
    @Test void providerResolutionWithoutCurrentInvestigationCannotClose() throws Exception {startRisk(1);providerResolves();assertEquals("INVESTIGATION_UNRESOLVED",eligibility().unmetCondition());assertThrows(ResponseStatusException.class,this::resolution);}
    @Test void oldSourceInvestigationDoesNotReplaceCurrentSourceInvestigation() throws Exception {startRisk(1);investigateAll();providerResolves();assertFalse(eligibility().eligible());assertThrows(ResponseStatusException.class,this::resolution);}
    @Test void everyCurrentCandidateNeedsInvestigation() throws Exception {startRisk(2);providerResolves();var m=source();var v=investigations.inspect(maker.user().getId(),maker.sid(),m.id());investigations.record(maker.user().getId(),maker.sid(),m.id(),v.viewId(),m.assessmentId(),v.caseRevision(),UUID.randomUUID(),0,"hit-0",AmlInvestigationService.Finding.IDENTIFIERS_DIFFER,"첫 후보만 검토함");assertFalse(eligibility().eligible());investigateAll();assertTrue(eligibility().eligible());}
    @Test void disappearingHistoricalCandidateCannotBeTreatedAsFalsePositive() throws Exception {startRisk(2);((ArrayNode)nodes.get(0).path("hits")).remove(1);((ObjectNode)nodes.get(0)).put("total_hits",1);providerResolves();investigateAll();assertEquals("CANDIDATE_NOT_PRESERVED",eligibility().unmetCondition());assertThrows(ResponseStatusException.class,this::resolution);}
    @ParameterizedTest @ValueSource(strings={"1990-01-01","1974","1974-01"})
    void equalOrPartialCandidateBirthCannotAuthorizeClosure(String date) throws Exception {startRisk(1);((ObjectNode)nodes.get(0).path("hits").get(0).path("properties")).set("birthDate",json.createArrayNode().add(date));providerResolves();investigateAll();assertEquals("BIRTH_DATE_NOT_DISTINCT",eligibility().unmetCondition());assertThrows(ResponseStatusException.class,this::resolution);}
    @Test void pepDeclarationCannotBeClearedThroughAmlFalsePositive() throws Exception {cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.YES));startRisk(1);providerResolves();investigateAll();assertEquals("OTHER_RISK_REQUIRES_REVIEW",eligibility().unmetCondition());assertThrows(ResponseStatusException.class,this::resolution);}
    @Test void historicalIdentityMismatchCannotBeClearedThroughAmlFalsePositive() throws Exception {reviewed();cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));startRisk(1);providerResolves();investigateAll();assertEquals("OTHER_RISK_REQUIRES_REVIEW",eligibility().unmetCondition());assertThrows(ResponseStatusException.class,this::resolution);}
    @Test void requestedAddressOrFundsEvidencePreventsNarrowClosure() throws Exception {ready(1);evidence.request(maker.user().getId(),maker.sid(),customer.getId(),cdd.status(customer.getId()).assessmentId(),EvidenceService.Purpose.SOURCE_OF_FUNDS,UUID.randomUUID());assertEquals("ADDITIONAL_EVIDENCE_UNRESOLVED",eligibility().unmetCondition());assertThrows(ResponseStatusException.class,this::resolution);}
    @Test void pastConfirmedMatchCannotBeClearedWithThisLimitedPolicy() throws Exception {startRisk(1);((ObjectNode)nodes.get(0).path("hits").get(0)).put("review_status","Confirmed Match");send("In Review");providerResolves();investigateAll();assertEquals("CONFIRMED_MATCH_REQUIRES_ESCALATION",eligibility().unmetCondition());assertThrows(ResponseStatusException.class,this::resolution);}
    @Test void historicalMissingSourceNeverGetsInventedToEnableClosure() throws Exception {
        ready(1);
        // 별도 과거 서명을 실제 수신한 뒤 자료 미보관 시대의 평가 표본만 새로 추가한다. 기존 이력은 바꾸지 않는다.
        time=Instant.now().getEpochSecond()-100;send("In Review");
        jdbc.update("INSERT INTO cdd_assessments(id,user_id,declaration_id,attempt_id,event_id,status_digest,key_id,policy_version,outcome,reasons,identity_mac) SELECT ?,user_id,declaration_id,attempt_id,?,status_digest,key_id,policy_version,outcome,reasons,identity_mac FROM cdd_assessments WHERE id=?",UUID.randomUUID(),event.get("event_id"),cdd.status(customer.getId()).assessmentId());
        assertEquals("HISTORICAL_SOURCE_MISSING",eligibility().unmetCondition());assertThrows(ResponseStatusException.class,this::resolution);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void newInvestigationAfterSignatureInvalidatesDecisionWithoutConsumingProof(boolean unresolved) throws Exception {
        ready(1);var r=resolution();var p=proof(checker,r,true);long revision=service.status(customer.getId()).revision();var m=source();var v=investigations.inspect(maker.user().getId(),maker.sid(),m.id());investigations.record(maker.user().getId(),maker.sid(),m.id(),v.viewId(),m.assessmentId(),v.caseRevision(),UUID.randomUUID(),0,"hit-0",unresolved?AmlInvestigationService.Finding.POSSIBLE_MATCH:AmlInvestigationService.Finding.IDENTIFIERS_DIFFER,"새 조사 의견");assertEquals(revision,service.status(customer.getId()).revision());assertThrows(ResponseStatusException.class,()->decide(r,true,p));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));blocked();
    }
    @Test void newDeclarationAfterOptionsPreventsClosure() throws Exception {ready(1);var r=resolution();var p=proof(checker,r,true);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));assertThrows(ResponseStatusException.class,()->decide(r,true,p));blocked();}
    @Test void providerFreshnessLossAfterOptionsPreventsClosure() throws Exception {ready(1);var r=resolution();var p=proof(checker,r,true);jdbc.update("UPDATE kyc_reconciliations SET fresh_at=NULL WHERE attempt_id=?",attempt);assertThrows(ResponseStatusException.class,()->decide(r,true,p));blocked();}
    @Test void validUntilCannotExceedCurrentIdentityExpiry() throws Exception {startRisk(1);decision.put("expires_at",Instant.now().plusSeconds(600).toString());providerResolves();investigateAll();assertTrue(eligibility().maximumValidUntil().isBefore(Instant.now().plusSeconds(601)));assertThrows(ResponseStatusException.class,this::resolution);var r=resolution(Instant.now().plusSeconds(300));decide(r,true,proof(checker,r,true));allowed();}
    @Test void nullPastAndMoreThanOneDayValidityAreRejected() throws Exception {ready(1);for(Instant until:Arrays.asList(null,Instant.now().minusSeconds(1),Instant.now().plusSeconds(86401)))assertThrows(ResponseStatusException.class,()->resolution(until));}
    @Test void decisionAuditFailureRollsBackCaseProofAndGrantThenSameProofCanRetry() throws Exception {
        ready(1);var r=resolution();var p=proof(checker,r,true);doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_DECIDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->decide(r,true,p));assertEquals("OPEN",service.status(customer.getId()).state());assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,p.challengeId()));blocked();reset(audit);decide(r,true,p);allowed();
    }
    @Test void rejectionDoesNotGrantButAllowsFreshRequest() throws Exception {ready(1);var r=resolution();decide(r,false,proof(checker,r,false));blocked();var fresh=resolution();decide(fresh,true,proof(checker,fresh,true));allowed();}
    @Test void contributorWhoMovesToApproverCannotApproveOwnInvestigation() throws Exception {
        ready(1);var contributor=maker;maker=account(UserRole.OPERATOR);var r=resolution();var u=users.findById(contributor.user().getId()).orElseThrow();u.setRole(UserRole.APPROVER);users.saveAndFlush(u);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.inspect(contributor.user().getId(),contributor.sid(),r.payload().id(),0)).getStatusCode().value());blocked();
    }
    @Test void newProviderEventReopensApprovedCaseAndStopsNewOrders() throws Exception {ready(1);closeCase();allowed();send("Approved");assertEquals("OPEN",service.status(customer.getId()).state());blocked();}
    @Test void operationalTradingRestrictionStillOverridesResolvedCase() throws Exception {ready(1);closeCase();allowed();var u=users.findById(customer.getId()).orElseThrow();u.setTradingRestricted(true);users.saveAndFlush(u);blocked();}
    @Test void concurrentApproveAndRejectLeaveOneDecisionAndOnlyMatchingFinancialPermission() throws Exception {
        ready(1);var r=resolution();var yes=proof(checker,r,true);var no=proof(checker,r,false);try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();for(boolean approve:new boolean[]{true,false})results.add(pool.submit(()->{gate.await();try{decide(r,approve,approve?yes:no);return true;}catch(ResponseStatusException e){return false;}}));gate.countDown();int successes=0;for(var future:results)if(future.get(12,TimeUnit.SECONDS))successes++;assertEquals(1,successes);}
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM edd_decisions WHERE report_id=?",Long.class,r.payload().id()));boolean approved=jdbc.queryForObject("SELECT approved FROM edd_decisions WHERE report_id=?",Boolean.class,r.payload().id());if(approved)allowed();else blocked();
    }
    @Test void oldProtocolRequestIsHistoricalOnlyAndItsDossierStillReadable() throws Exception {
        ready(1);var r=resolution();var raw=(ObjectNode)json.valueToTree(r.payload());UUID id=UUID.randomUUID();raw.put("id",id.toString()).put("reviewPolicy","EDD_CASE_DOSSIER_REVIEW_V1");raw.remove("amlResolution");String hash=verifier.digest(raw);jdbc.update("INSERT INTO edd_reports(id,case_id,user_id,requester_id,request_key,payload,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?)",id,r.payload().caseId(),customer.getId(),maker.user().getId(),UUID.randomUUID(),json.writeValueAsString(raw),hash,java.sql.Timestamp.from(r.payload().expiresAt()));assertEquals(id,service.dossier(checker.user().getId(),checker.sid(),id,0).report().payload().id());assertThrows(ResponseStatusException.class,()->service.inspect(checker.user().getId(),checker.sid(),id,0));blocked();
    }
    @Test void eligibilityHttpIsMetadataOnlyAndNotCustomerOrAuditorPermission() throws Exception {
        ready(1);String path="/api/admin/edd/customers/"+customer.getId()+"/aml-resolution";var a=account(UserRole.AUDITOR);mvc.perform(get(path).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());mvc.perform(get(path).header("Authorization","Bearer "+a.access())).andExpect(status().isForbidden());String response=mvc.perform(get(path).header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();assertFalse(response.contains("1990-01-01"));assertFalse(response.contains("가상 후보"));
    }
    @Test void eligibilityAuditFailureDoesNotReturnBasis() throws Exception {ready(1);doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EDD_AML_RESOLUTION_CHECKED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::eligibility);}
    @Test void helperCannotResolveOutsideBusinessTransaction() {assertThrows(IllegalStateException.class,()->resolutionPolicy.assess(customer.getId()));}
    @Test void closedFlagWithoutApprovedResolutionDoesNotAuthorizeTrading() throws Exception {
        ready(1);jdbc.update("UPDATE edd_cases SET state='CLOSED',valid_until=clock_timestamp()+interval '1 hour' WHERE user_id=?",customer.getId());blocked();
    }
    @Test void derivedCaseCannotExtendAnImmutableApprovedValidity() throws Exception {
        ready(1);closeCase();allowed();jdbc.update("UPDATE edd_cases SET valid_until=valid_until+interval '1 hour' WHERE user_id=?",customer.getId());blocked();
    }
    @Test void approvedResolutionExpiresWithoutBackgroundStateMutation() throws Exception {
        ready(1);var r=resolution(Instant.now().plusSeconds(5));decide(r,true,proof(checker,r,true));allowed();
        // 실제 시계를 넘겨 SQL의 기한 조건을 검증한다. 금융 결정이나 사건 기한을 시험용 값으로 덮어쓰지 않는다.
        Thread.sleep(5500);assertEquals("CLOSED",service.status(customer.getId()).state());blocked();
        // 기존 자료 대기 결론으로 재심사를 열고 현재 조사·독립 결정을 새로 확정한다. 과거 기한은 수정하지 않는다.
        var reopen=propose(EddService.Finding.AWAITING_EVIDENCE);decide(reopen,true,proof(checker,reopen,true));
        investigateAll();assertTrue(eligibility().eligible());closeCase();allowed();
    }
    @Test void sameProviderTimeWithNewEventIdCannotReplaceNewResolutionEvidence() throws Exception {
        ready(1);time--;send("Approved");investigateAll();assertEquals("NEW_SIGNED_RESOLUTION_REQUIRED",eligibility().unmetCondition());assertThrows(ResponseStatusException.class,this::resolution);
    }

}
