package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.edd.*;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/** 실제 보고 승인·현재 인계·PG 저장·감사와 HTTP 인가로 전사 감독의 최소 정보 경계를 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
class ReportingSupervisionIntegrationTest extends EddTestSupport {
    @Autowired ReportingSupervisionService supervision;
    @Autowired ReportingReviewService reporting;
    @Autowired ReportingFollowupService followups;
    @Autowired ReportingCustodyService assignments;
    ReportingSupervisionService.Cursor beginning;
    Account supervisor;
    boolean risk;
    static final String REASON="외부에 공개하지 않을 가상 보고 판단의 근거";

    @BeforeEach void supervisionSetup() throws Exception {
        supervisor=account(UserRole.APPROVER);risk=false;
        // 불변 이력을 지우지 않는다. 앞선 테스트의 확정 위치 뒤에서 이번 표본만 검사한다.
        var rows=jdbc.queryForList("SELECT review_id,decided_at FROM compliance_review_decisions WHERE approved ORDER BY decided_at DESC,review_id DESC LIMIT 1");
        beginning=rows.isEmpty()?null:new ReportingSupervisionService.Cursor(((Timestamp)rows.getFirst().get("decided_at")).toInstant(),(UUID)rows.getFirst().get("review_id"));
    }
    ReportingReviewService.Review proposed(ReportingReviewService.Conclusion conclusion) throws Exception {
        if(!risk){reviewed();risk=true;}
        var ref=propose(EddService.Finding.COMPLIANCE_ESCALATION);decide(ref,true,proof(checker,ref,true));
        return reporting.propose(maker.user().getId(),maker.sid(),ref.payload().id(),checker.user().getId(),UUID.randomUUID(),new ReportingReviewService.Facts(conclusion,REASON));
    }
    ReportingReviewService.Review approved(ReportingReviewService.Conclusion conclusion) throws Exception {
        return complete(proposed(conclusion),true);
    }
    ReportingReviewService.Review complete(ReportingReviewService.Review r,boolean approve) throws Exception {
        for(int page=0;page<r.payload().dossier().pageCount();page++)reporting.inspect(checker.user().getId(),checker.sid(),r.payload().id(),page);
        var options=reporting.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return reporting.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve,new OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null)));
    }
    ReportingReviewService.Review required() throws Exception {return approved(ReportingReviewService.Conclusion.REPORT_REQUIRED);}
    ReportingSupervisionService.Page queue() {return supervision.queue(supervisor.user().getId(),supervisor.sid(),beginning);}
    ReportingSupervisionService.Item item(UUID id) {return queue().items().stream().filter(i->i.reviewId().equals(id)).findFirst().orElseThrow();}
    void unavailable(Account a,String how) {
        var u=users.findById(a.user().getId()).orElseThrow();
        switch(how) {
            case "disabled"->u.setEnabled(false);
            case "expired"->u.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));
            case "recovery"->u.setOperatorRecoveryRequestId(UUID.randomUUID());
            case "role"->u.setRole(UserRole.AUDITOR);
            case "identity"->u.setOperatorPersonId(UUID.randomUUID());
            default->throw new AssertionError();
        }
        users.saveAndFlush(u);
    }
    void nextCustomer() {
        customer=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());
        var token=tokens.saveAndFlush(RefreshToken.builder().userId(customer.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        sid=token.getSessionId();customerAccess=jwt.generateAccessToken(customer.getId(),sid);
        cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));risk=false;
    }
    @Test void unrelatedApproverSeesHealthyAssignedDutyButGetsNoOriginalContent() throws Exception {
        var r=required();var page=queue();var i=item(r.payload().id());
        assertEquals(1,page.scanned());assertEquals(customer.getId(),i.userId());
        assertEquals(i.internalDecisionAt().plusSeconds(86400),i.internalCheckTargetAt());assertFalse(i.internalCheckOverdue());
        assertFalse(i.orphaned());assertEquals(0,i.custodyGeneration());assertTrue(i.members().stream().allMatch(ReportingSupervisionService.Member::available));
        assertEquals("FOLLOW_UP_REQUIRED",i.taskStatus());assertEquals(ReportingFollowupService.DEADLINE,i.legalDeadlineStatus());
        assertNull(i.latestObservationRecordedAt());assertEquals(ReportingSupervisionService.SEMANTICS,page.semantics());
        assertThrows(ResponseStatusException.class,()->reporting.inspect(supervisor.user().getId(),supervisor.sid(),r.payload().id(),0));
        assertThrows(ResponseStatusException.class,()->assignments.origin(supervisor.user().getId(),supervisor.sid(),r.payload().id(),0));
        blocked();
    }
    @Test void metadataCannotSeizeHealthyWorkOrWriteFollowup() throws Exception {
        var r=required();item(r.payload().id());
        assertThrows(ResponseStatusException.class,()->assignments.context(supervisor.user().getId(),supervisor.sid(),r.payload().id()));
        assertThrows(ResponseStatusException.class,()->followups.record(supervisor.user().getId(),supervisor.sid(),r.payload().id(),UUID.randomUUID(),new ReportingFollowupService.Facts(ReportingFollowupService.Outcome.OUTCOME_UNKNOWN,Instant.now(),"","업무 배정 없는 기록")));
    }
    @Test void pendingRejectedAndNoReportDoNotBecomeReportingDuty() throws Exception {
        var pending=proposed(ReportingReviewService.Conclusion.REPORT_REQUIRED);assertTrue(queue().items().isEmpty());
        complete(pending,false);assertTrue(queue().items().isEmpty());
        approved(ReportingReviewService.Conclusion.NO_REPORT_ON_CURRENT_FACTS);assertTrue(queue().items().isEmpty());
    }
    @ParameterizedTest @EnumSource(ReportingFollowupService.Outcome.class)
    void everyStaffObservationKeepsDutyAndNeverLeaksOutcomeOrReference(ReportingFollowupService.Outcome outcome) throws Exception {
        var r=required();String reference=outcome==ReportingFollowupService.Outcome.RECEIPT_REFERENCE_OBSERVED?"가상 민감 접수번호":"";
        var facts=new ReportingFollowupService.Facts(outcome,Instant.now(),reference,"비공개 외부 결과 메모");
        var observation=followups.record(maker.user().getId(),maker.sid(),r.payload().id(),UUID.randomUUID(),facts);
        var page=queue();assertEquals(observation.recordedAt(),item(r.payload().id()).latestObservationRecordedAt());assertEquals("FOLLOW_UP_REQUIRED",item(r.payload().id()).taskStatus());
        String text=json.writeValueAsString(page);for(String forbidden:List.of(REASON,"비공개 외부 결과 메모",outcome.name(),"factsMac","actorPerson","operatorPersonId","payloadHash"))assertFalse(text.contains(forbidden));
        if(!reference.isEmpty())assertFalse(text.contains(reference));
        assertThrows(ResponseStatusException.class,()->followups.inspect(supervisor.user().getId(),supervisor.sid(),observation.payload().id()));blocked();
    }
    @ParameterizedTest @ValueSource(strings={"disabled","expired","recovery","role","identity"})
    void bothUnavailablePeopleAreVisibleWithoutDroppingDuty(String how) throws Exception {
        var r=required();unavailable(maker,how);unavailable(checker,how);
        var i=item(r.payload().id());assertTrue(i.orphaned());assertTrue(i.members().stream().noneMatch(ReportingSupervisionService.Member::available));
    }
    @Test void oneUnavailablePersonIsVisibleButNotOrphaned() throws Exception {
        var r=required();unavailable(maker,"disabled");var i=item(r.payload().id());
        assertFalse(i.orphaned());assertEquals(1,i.members().stream().filter(ReportingSupervisionService.Member::available).count());
    }
    @Test void staleCustodianMfaDoesNotPretendAuthorityIsLost() throws Exception {
        var r=required();var token=tokens.findBySessionId(maker.sid()).orElseThrow();token.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(token);
        assertTrue(item(r.payload().id()).members().stream().allMatch(ReportingSupervisionService.Member::available));assertFalse(item(r.payload().id()).orphaned());
    }
    @Test void handoffUpdatesCurrentPeopleButPreservesOriginalTargetAndFinancialHold() throws Exception {
        var r=required();var old=item(r.payload().id());var primary=account(UserRole.OPERATOR);var next=account(UserRole.APPROVER);
        var state=assignments.context(maker.user().getId(),maker.sid(),r.payload().id());
        var handoff=assignments.propose(maker.user().getId(),maker.sid(),r.payload().id(),state.stamp(),primary.user().getId(),next.user().getId(),UUID.randomUUID(),"별도 인물로 교대");
        assignments.inspect(supervisor.user().getId(),supervisor.sid(),handoff.payload().id());
        var options=assignments.options(supervisor.user().getId(),supervisor.sid(),handoff.payload().id(),handoff.payloadHash(),true).authentication();
        assignments.decide(supervisor.user().getId(),supervisor.sid(),handoff.payload().id(),handoff.payloadHash(),true,new OperatorMfaService.AuthorizationProof(options.challengeId(),supervisor.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null)));
        var current=item(r.payload().id());assertEquals(1,current.custodyGeneration());assertEquals(List.of(primary.user().getId(),next.user().getId()),current.members().stream().map(ReportingSupervisionService.Member::id).toList());
        assertEquals(old.internalDecisionAt(),current.internalDecisionAt());assertEquals(old.internalCheckTargetAt(),current.internalCheckTargetAt());blocked();
    }
    @Test void newDeclarationAndDisabledCustomerDoNotHidePastReportingDuty() throws Exception {
        var r=required();cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));
        var u=users.findById(customer.getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);assertEquals(r.payload().id(),item(r.payload().id()).reviewId());
    }
    @Test void emptyFilteredPageStillAdvancesThenFreshScanFindsLaterApproval() throws Exception {
        // 실제 독립 승인한 서로 다른 고객 26건이다. 일일 제한·불변 이력을 우회하지 않는다.
        for(int n=0;n<25;n++){if(n>0)nextCustomer();approved(ReportingReviewService.Conclusion.NO_REPORT_ON_CURRENT_FACTS);}
        var first=queue();assertTrue(first.items().isEmpty());assertEquals(25,first.scanned());assertNotNull(first.next());
        nextCustomer();var r=required();var second=supervision.queue(supervisor.user().getId(),supervisor.sid(),first.next());
        assertEquals(List.of(r.payload().id()),second.items().stream().map(ReportingSupervisionService.Item::reviewId).toList());assertNull(second.next());
        // 첫 페이지를 처음부터 갱신하면 뒤에 추가된 확정 업무도 다시 탐색할 수 있다.
        assertNotNull(queue().next());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","expired","recovery","role"})
    void serviceRechecksSupervisorAuthorityAfterAuthentication(String how) throws Exception {
        required();unavailable(supervisor,how);assertThrows(ResponseStatusException.class,this::queue);
    }
    @Test void staleRevokedAndForeignSessionCannotReadSupervision() throws Exception {
        required();assertThrows(ResponseStatusException.class,()->supervision.queue(supervisor.user().getId(),checker.sid(),beginning));
        var token=tokens.findBySessionId(supervisor.sid()).orElseThrow();token.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(token);assertThrows(ResponseStatusException.class,this::queue);
        token.setMfaVerifiedAt(LocalDateTime.now());token.setRevoked(true);tokens.saveAndFlush(token);assertThrows(ResponseStatusException.class,this::queue);
    }
    @Test void failedAuditReturnsNoListAndSuccessAuditContainsNoReportText() throws Exception {
        required();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("REPORTING_SUPERVISION_READ"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,this::queue);reset(audit);assertFalse(queue().items().isEmpty());
        String details=jdbc.queryForList("SELECT detail FROM security_audit WHERE action='REPORTING_SUPERVISION_READ' AND actor_id=?",supervisor.user().getId()).toString();assertFalse(details.contains(REASON));
    }
    @Test void malformedAndFutureCursorAreRejected() throws Exception {
        required();for(var cursor:List.of(new ReportingSupervisionService.Cursor(null,UUID.randomUUID()),new ReportingSupervisionService.Cursor(Instant.now(),null),new ReportingSupervisionService.Cursor(Instant.EPOCH.minusSeconds(1),UUID.randomUUID()),new ReportingSupervisionService.Cursor(Instant.now().plusSeconds(60),UUID.randomUUID())))assertThrows(ResponseStatusException.class,()->supervision.queue(supervisor.user().getId(),supervisor.sid(),cursor));
    }
    @Test void httpApproverGetsNoStoreMetadataWhileOtherRolesAndAnonymousAreDenied() throws Exception {
        var r=required();String url="/api/admin/compliance/reporting-supervision";
        var request=get(url).header("Authorization","Bearer "+supervisor.access());
        if(beginning!=null)request.param("afterDecision",beginning.decidedAt().toString()).param("afterReview",beginning.reviewId().toString());
        String body=mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        assertTrue(body.contains(r.payload().id().toString()));assertFalse(body.contains(REASON));
        var auditor=account(UserRole.AUDITOR);for(String access:List.of(maker.access(),auditor.access(),customerAccess))mvc.perform(get(url).header("Authorization","Bearer "+access)).andExpect(status().isForbidden());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("Authorization","Bearer "+supervisor.access()).param("afterReview",r.payload().id().toString())).andExpect(status().isBadRequest());
    }
}
