package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.edd.*;
import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/** 확정된 실제 보고 판단의 역사 조회·페이지 경계·원문 보호·멱등/경합·실패 원자성을 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class ReportingFollowupIntegrationTest extends EddTestSupport {
    @Autowired ReportingReviewService reporting;@Autowired ReportingFollowupService followups;@Autowired EvidenceCrypto evidenceCrypto;
    boolean risk;
    @BeforeEach void resetRisk() {risk=false;}
    ReportingReviewService.Facts reportingFacts(ReportingReviewService.Conclusion c) {return new ReportingReviewService.Facts(c,"가상 사건의 합당한 근거에 대한 내부 보고 판단");}
    ReportingReviewService.Review proposed(ReportingReviewService.Conclusion conclusion) throws Exception {
        if(!risk){reviewed();risk=true;}var referral=propose(EddService.Finding.COMPLIANCE_ESCALATION);decide(referral,true,proof(checker,referral,true));
        return reporting.propose(maker.user().getId(),maker.sid(),referral.payload().id(),checker.user().getId(),UUID.randomUUID(),reportingFacts(conclusion));
    }
    ReportingReviewService.Review approved(ReportingReviewService.Conclusion conclusion) throws Exception {
        var r=proposed(conclusion);for(int page=0;page<r.payload().dossier().pageCount();page++)reporting.inspect(checker.user().getId(),checker.sid(),r.payload().id(),page);
        var o=reporting.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true).authentication();return reporting.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true,new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null)));
    }
    ReportingReviewService.Review required() throws Exception {return approved(ReportingReviewService.Conclusion.REPORT_REQUIRED);}
    ReportingFollowupService.Facts facts(ReportingFollowupService.Outcome o) {return new ReportingFollowupService.Facts(o,Instant.now().minusSeconds(30),o==ReportingFollowupService.Outcome.RECEIPT_REFERENCE_OBSERVED?"가상 외부 참조":"","가상 외부 처리 결과를 관찰했으나 접수 진위는 별도 확인 필요");}
    ReportingFollowupService.Observation record(ReportingReviewService.Review r,UUID key,ReportingFollowupService.Facts facts) {return followups.record(maker.user().getId(),maker.sid(),r.payload().id(),key,facts);}
    ReportingFollowupService.Page queue() {return followups.queue(maker.user().getId(),maker.sid(),null);}
    @Test void realApprovedReportNeededAppearsWithoutDeclaringLegalDeadlineOrSubmissionSuccess() throws Exception {
        var r=required();var page=queue();assertEquals(1,page.scanned());assertEquals(1,page.items().size());var i=page.items().getFirst();assertEquals(r.payload().id(),i.reviewId());assertEquals(i.internalDecisionAt().plusSeconds(86400),i.internalCheckTargetAt());assertFalse(i.internalCheckOverdue());assertEquals(ReportingFollowupService.DEADLINE,i.legalDeadlineStatus());assertEquals("FOLLOW_UP_REQUIRED",i.taskStatus());assertNull(i.latestObservation());blocked();
    }
    @Test void unapprovedAndApprovedNoReportNeverBecomePendingReport() throws Exception {
        var pending=proposed(ReportingReviewService.Conclusion.REPORT_REQUIRED);assertTrue(queue().items().isEmpty());assertThrows(ResponseStatusException.class,()->record(pending,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN)));
    }
    @Test void approvedNoReportIsActuallyVerifiedAndExcluded() throws Exception {var r=approved(ReportingReviewService.Conclusion.NO_REPORT_ON_CURRENT_FACTS);assertEquals(1,queue().scanned());assertTrue(queue().items().isEmpty());assertThrows(ResponseStatusException.class,()->record(r,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN)));}
    @Test void oldNeededDecisionSurvivesNewDeclarationAndAnotherApprovedConclusion() throws Exception {
        var first=required();approved(ReportingReviewService.Conclusion.NO_REPORT_ON_CURRENT_FACTS);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));var items=queue().items();assertEquals(1,items.size());assertEquals(first.payload().id(),items.getFirst().reviewId());assertNotNull(record(first,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN)));blocked();
    }
    @Test void disabledCustomerDoesNotEraseHistoricalReportingWork() throws Exception {var r=required();var u=users.findById(customer.getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);assertEquals(r.payload().id(),queue().items().getFirst().reviewId());assertNotNull(record(r,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN)));}
    @ParameterizedTest @EnumSource(ReportingFollowupService.Outcome.class)
    void everyObservationOutcomeStaysUnverifiedAndDoesNotCloseTask(ReportingFollowupService.Outcome outcome) throws Exception {
        var r=required();var f=facts(outcome);var o=record(r,UUID.randomUUID(),f);assertEquals(outcome,o.payload().outcome());var item=queue().items().getFirst();assertEquals("FOLLOW_UP_REQUIRED",item.taskStatus());assertEquals(ReportingFollowupService.ASSURANCE,item.latestObservation().payload().assurance());assertEquals(o.payload().id(),item.latestObservation().payload().id());assertEquals(f,followups.inspect(checker.user().getId(),checker.sid(),o.payload().id()).facts());blocked();
    }
    @Test void declaredEarlierExternalTimeDoesNotResetInternalAnchorOrCertifyLegalTime() throws Exception {var r=required();Instant anchor=queue().items().getFirst().internalCheckTargetAt();record(r,UUID.randomUUID(),new ReportingFollowupService.Facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,Instant.now().minusSeconds(86400),"","과거 외부 행동이라고 진술한 관찰"));assertEquals(anchor,queue().items().getFirst().internalCheckTargetAt());assertEquals(ReportingFollowupService.DEADLINE,queue().items().getFirst().legalDeadlineStatus());}
    @Test void bothAssignedPeopleCanReadButOthersHaveNoQueueOrObservationAccess() throws Exception {
        var r=required();var o=record(r,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN));assertEquals(1,followups.queue(checker.user().getId(),checker.sid(),null).items().size());
        for(var other:List.of(account(UserRole.OPERATOR),account(UserRole.APPROVER))){assertTrue(followups.queue(other.user().getId(),other.sid(),null).items().isEmpty());assertThrows(ResponseStatusException.class,()->followups.inspect(other.user().getId(),other.sid(),o.payload().id()));assertThrows(ResponseStatusException.class,()->followups.record(other.user().getId(),other.sid(),r.payload().id(),UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN)));}
    }
    @Test void changedOperatorIdentityCannotRecoverOldAssignmentUsingSameAccount() throws Exception {required();var u=users.findById(maker.user().getId()).orElseThrow();u.setOperatorPersonId(UUID.randomUUID());users.saveAndFlush(u);assertTrue(queue().items().isEmpty());}
    @Test void sameHumanOnAnotherAccountHasNoImplicitAssignment() throws Exception {var r=required();var other=account(UserRole.OPERATOR);var u=users.findById(other.user().getId()).orElseThrow();u.setOperatorPersonId(maker.user().getOperatorPersonId());users.saveAndFlush(u);assertTrue(followups.queue(u.getId(),other.sid(),null).items().isEmpty());assertThrows(ResponseStatusException.class,()->followups.record(u.getId(),other.sid(),r.payload().id(),UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN)));}
    @Test void staleMfaBlocksQueueAndRawObservation() throws Exception {var r=required();var o=record(r,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN));var token=tokens.findBySessionId(maker.sid()).orElseThrow();token.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(token);assertThrows(ResponseStatusException.class,this::queue);assertThrows(ResponseStatusException.class,()->followups.inspect(maker.user().getId(),maker.sid(),o.payload().id()));}
    @Test void observationMetadataAndAuditExcludePrivateReferenceAndNotes() throws Exception {var r=required();var value=facts(ReportingFollowupService.Outcome.RECEIPT_REFERENCE_OBSERVED);var o=record(r,UUID.randomUUID(),value);String metadata=json.writeValueAsString(queue())+json.writeValueAsString(followups.history(checker.user().getId(),checker.sid(),r.payload().id(),0));String stored=jdbc.queryForObject("SELECT payload FROM reporting_followups WHERE id=?",String.class,o.payload().id());String auditText=jdbc.queryForList("SELECT action,detail FROM security_audit WHERE target=?","reporting-followup:"+o.payload().id()).toString();for(String text:List.of(metadata,stored,auditText)){assertFalse(text.contains(value.externalReference()));assertFalse(text.contains(value.notes()));}assertFalse(new String(jdbc.queryForObject("SELECT facts_cipher FROM reporting_followups WHERE id=?",byte[].class,o.payload().id()),StandardCharsets.UTF_8).contains(value.notes()));}
    @Test void sameKeyAndSameFactsReplayButChangedOutcomeOrNotesConflict() throws Exception {var r=required();var value=facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN);UUID key=UUID.randomUUID();var o=record(r,key,value);assertEquals(o.payload().id(),record(r,key,value).payload().id());assertThrows(ResponseStatusException.class,()->record(r,key,new ReportingFollowupService.Facts(value.outcome(),value.occurredAt(),"","바뀐 메모")));assertThrows(ResponseStatusException.class,()->record(r,key,new ReportingFollowupService.Facts(ReportingFollowupService.Outcome.REJECTION_OBSERVED,value.occurredAt(),"",value.notes())));assertEquals(1,followups.history(maker.user().getId(),maker.sid(),r.payload().id(),0).size());}
    @Test void sameKeyCannotBeMovedToAnotherApprovedReview() throws Exception {var a=required();var b=required();UUID key=UUID.randomUUID();var value=facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN);record(a,key,value);assertThrows(ResponseStatusException.class,()->record(b,key,value));}
    @Test void concurrentSameKeyCommitsOneObservation() throws Exception {var r=required();UUID key=UUID.randomUUID();var value=facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN);try(var pool=Executors.newFixedThreadPool(2)){var gate=new CountDownLatch(1);var results=new ArrayList<Future<UUID>>();for(int i=0;i<2;i++)results.add(pool.submit(()->{gate.await();return record(r,key,value).payload().id();}));gate.countDown();assertEquals(results.get(0).get(12,TimeUnit.SECONDS),results.get(1).get(12,TimeUnit.SECONDS));}assertEquals(1,followups.history(maker.user().getId(),maker.sid(),r.payload().id(),0).size());}
    @Test void latestRecordedObservationCannotHideEarlierFailureHistory() throws Exception {var r=required();var fail=record(r,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.REJECTION_OBSERVED));var receipt=record(r,UUID.randomUUID(),new ReportingFollowupService.Facts(ReportingFollowupService.Outcome.RECEIPT_REFERENCE_OBSERVED,Instant.now().minusSeconds(60),"이후 적은 접수 참조","선행 시각이라고 주장한 나중 기록"));assertEquals(receipt.payload().id(),queue().items().getFirst().latestObservation().payload().id());var history=followups.history(maker.user().getId(),maker.sid(),r.payload().id(),0);assertEquals(List.of(receipt.payload().id(),fail.payload().id()),history.stream().map(x->x.payload().id()).toList());}
    @Test void recordAuditFailureRollsBackAndSameKeyRetries() throws Exception {var r=required();UUID key=UUID.randomUUID();var value=facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN);doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("REPORTING_FOLLOWUP_RECORDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->record(r,key,value));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM reporting_followups WHERE review_id=?",Long.class,r.payload().id()));reset(audit);assertNotNull(record(r,key,value));}
    @Test void queueAuditFailureDoesNotReturnPendingList() throws Exception {required();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("REPORTING_QUEUE_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::queue);}
    @Test void inspectionAuditFailureDoesNotReturnPrivateNotes() throws Exception {var r=required();var o=record(r,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN));doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("REPORTING_FOLLOWUP_VIEWED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->followups.inspect(checker.user().getId(),checker.sid(),o.payload().id()));}
    @Test void allObservationMutationsAreRejected() throws Exception {var r=required();record(r,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN));for(String sql:List.of("UPDATE reporting_followups SET payload=payload","DELETE FROM reporting_followups","TRUNCATE reporting_followups"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));assertEquals(1,followups.history(maker.user().getId(),maker.sid(),r.payload().id(),0).size());}
    @Test void emptyFilteredFirstPageStillAdvancesAndFindsOldHistoricalWork() throws Exception {
        // 페이지 표본은 실제 서로 다른 고객으로 만든다. 고객별 하루 10건 제한을 끄거나 이력을 바꾸지 않는다.
        for(int i=0;i<26;i++) {if(i>0)nextCustomer();approved(ReportingReviewService.Conclusion.NO_REPORT_ON_CURRENT_FACTS);}nextCustomer();var needed=required();var first=queue();assertTrue(first.items().isEmpty());assertEquals(25,first.scanned());assertNotNull(first.next());var second=followups.queue(maker.user().getId(),maker.sid(),first.next());assertEquals(2,second.scanned());assertEquals(List.of(needed.payload().id()),second.items().stream().map(ReportingFollowupService.Item::reviewId).toList());assertNull(second.next());
    }
    void nextCustomer() {
        customer=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());
        var token=tokens.saveAndFlush(RefreshToken.builder().userId(customer.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        sid=token.getSessionId();customerAccess=jwt.generateAccessToken(customer.getId(),sid);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));risk=false;
    }
    @Test void freshScanFindsApprovalCommittedAfterEarlierQueueRead() throws Exception {approved(ReportingReviewService.Conclusion.NO_REPORT_ON_CURRENT_FACTS);assertTrue(queue().items().isEmpty());var r=required();assertEquals(r.payload().id(),queue().items().getFirst().reviewId());assertEquals("REFRESH_FROM_START_NOT_A_DURABLE_CHECKPOINT",queue().semantics());}
    @Test void malformedAndFutureCursorsAndNegativeHistoryPageAreRejected() throws Exception {var r=required();for(var cursor:List.of(new ReportingFollowupService.Cursor(null,UUID.randomUUID()),new ReportingFollowupService.Cursor(Instant.now(),null),new ReportingFollowupService.Cursor(Instant.now().plusSeconds(86400),UUID.randomUUID())))assertThrows(ResponseStatusException.class,()->followups.queue(maker.user().getId(),maker.sid(),cursor));assertThrows(ResponseStatusException.class,()->followups.history(maker.user().getId(),maker.sid(),r.payload().id(),-1));}
    @Test void perReviewDailyLimitDoesNotTreatObservationAsCompletion() throws Exception {var r=required();for(int i=0;i<20;i++)record(r,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN));assertEquals(429,assertThrows(ResponseStatusException.class,()->record(r,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN))).getStatusCode().value());assertEquals("FOLLOW_UP_REQUIRED",queue().items().getFirst().taskStatus());}
    @Test void actualHttpQueueRecordHistoryAndPrivateInspectionUseAssignedFacts() throws Exception {
        var r=required();String path="/api/admin/compliance/reviews/"+r.payload().id()+"/followups";var value=facts(ReportingFollowupService.Outcome.RECEIPT_REFERENCE_OBSERVED);
        mvc.perform(get("/api/admin/compliance/reporting-queue").header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.items[0].taskStatus").value("FOLLOW_UP_REQUIRED"));
        String response=mvc.perform(post(path).header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new ReportingFollowupController.Observation(value)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();var observation=json.readValue(response,ReportingFollowupService.Observation.class);assertFalse(response.contains(value.externalReference()));
        mvc.perform(get(path).header("Authorization","Bearer "+checker.access())).andExpect(status().isOk()).andExpect(jsonPath("$[0].payload.id").value(observation.payload().id().toString()));
        mvc.perform(post("/api/admin/compliance/followups/"+observation.payload().id()+"/inspect").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk()).andExpect(jsonPath("$.facts.notes").value(value.notes()));blocked();
    }
    @Test void customerAuditorAndUnassignedOperatorCannotUseHttpRoutes() throws Exception {
        var r=required();var o=record(r,UUID.randomUUID(),facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN));var auditor=account(UserRole.AUDITOR);
        for(String token:List.of(customerAccess,auditor.access())) {mvc.perform(get("/api/admin/compliance/reporting-queue").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());mvc.perform(post("/api/admin/compliance/followups/"+o.payload().id()+"/inspect").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());}
        var other=account(UserRole.OPERATOR);mvc.perform(post("/api/admin/compliance/followups/"+o.payload().id()+"/inspect").header("Authorization","Bearer "+other.access())).andExpect(status().isNotFound());
    }
}
