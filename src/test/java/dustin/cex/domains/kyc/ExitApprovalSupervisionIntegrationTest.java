package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.edd.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.node.ObjectNode;

/** 미결 상태는 실제 요청으로, 만료·동일 시각/식별자 경계는 추가 전용 메타데이터 표본으로 검증한다. */
class ExitApprovalSupervisionIntegrationTest extends ExitTestSupport {
    @Autowired ExitApprovalSupervisionService supervision;
    ExitApprovalSupervisionService.Cursor beginning;
    @BeforeEach void cursor() {
        var row=jdbc.queryForList("SELECT * FROM (SELECT created_at,'EXIT' AS kind,id FROM relationship_exit_requests UNION ALL SELECT created_at,'RESCISSION' AS kind,id FROM relationship_exit_rescissions) r ORDER BY created_at DESC,kind DESC,id DESC LIMIT 1");
        beginning=row.isEmpty()?null:new ExitApprovalSupervisionService.Cursor(((Timestamp)row.getFirst().get("created_at")).toInstant(),ExitApprovalSupervisionService.Kind.valueOf((String)row.getFirst().get("kind")),(UUID)row.getFirst().get("id"));
    }
    ExitApprovalSupervisionService.Page queue(){return supervision.queue(independent.user().getId(),independent.sid(),beginning);}
    @Test void originalPendingDecisionIsVisibleWithoutPrivateFactsOrDecisionAuthority() throws Exception {
        original=prepared();var page=queue();assertEquals(1,page.items().size());var i=page.items().getFirst();assertEquals(ExitApprovalSupervisionService.Kind.EXIT,i.kind());assertEquals(original.payload().id(),i.requestId());assertFalse(i.expired());assertTrue(i.requesterMatchesRequest());assertTrue(i.checkerMatchesRequest());assertEquals("RECHECK_REQUIRED",i.decisionEligibility());
        String text=json.writeValueAsString(page);for(String w:List.of(facts().rationale(),"factsMac","payloadHash","dossier","operatorPersonId"))assertFalse(text.contains(w));assertThrows(ResponseStatusException.class,()->reporting.inspect(independent.user().getId(),independent.sid(),i.requestId(),0));assertEquals(0,active());
    }
    @Test void finalizedExitAndRescissionRequestsLeavePendingQueue() throws Exception {
        initiated();assertTrue(queue().items().isEmpty());var r=withdraw(UUID.randomUUID());var i=queue().items().getFirst();assertEquals(ExitApprovalSupervisionService.Kind.RESCISSION,i.kind());assertEquals(r.payload().id(),i.requestId());approveWithdrawal(r);assertTrue(queue().items().isEmpty());assertEquals(0,active());
    }
    @Test void rejectedDecisionAlsoLeavesQueueWithoutCreatingEffect() throws Exception {original=prepared();reportDecide(original,false,reportProof(checker,original,false));assertTrue(queue().items().isEmpty());assertEquals(0,active());}
    @Test void versionChangedRequesterAndCheckerAreNotReportedAsMatching() throws Exception {
        original=prepared();var u=users.findById(maker.user().getId()).orElseThrow();u.setUsername("버전 변경");users.saveAndFlush(u);var v=users.findById(checker.user().getId()).orElseThrow();v.setUsername("버전 변경");users.saveAndFlush(v);var i=queue().items().getFirst();assertFalse(i.requesterMatchesRequest());assertFalse(i.checkerMatchesRequest());assertFalse(i.expired());
    }
    @Test void staleStaffMfaDoesNotInvalidateBoundIdentityVersion() throws Exception {original=prepared();var t=tokens.findBySessionId(maker.sid()).orElseThrow();t.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(t);assertTrue(queue().items().getFirst().requesterMatchesRequest());}
    // 시간/정렬 표본만 추가한다. 업무 서명 결합이 없으므로 정상 승인이나 실제 금융 사실의 표본으로 쓰지 않는다.
    UUID fixture(UUID id,Instant created,Instant expires) {
        expires=expires.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        ObjectNode payload=(ObjectNode)json.valueToTree(original.payload());payload.put("id",id.toString());payload.put("expiresAt",expires.toString());String raw=json.writeValueAsString(payload);
        jdbc.update("INSERT INTO relationship_exit_requests(id,case_id,user_id,referral_id,requester_id,checker_id,request_key,payload,payload_hash,facts_cipher,expires_at,created_at) SELECT ?,case_id,user_id,referral_id,requester_id,checker_id,?,?,?,facts_cipher,?,? FROM relationship_exit_requests WHERE id=?",id,UUID.randomUUID(),raw,verifier.digest(payload),Timestamp.from(expires),Timestamp.from(created),original.payload().id());return id;
    }
    @Test void expiredUndecidedRequestRemainsVisibleAndNeverBecomesAnApproval() throws Exception {
        original=prepared();UUID id=fixture(UUID.randomUUID(),Instant.now(),Instant.now().minusSeconds(1));var i=queue().items().stream().filter(x->x.requestId().equals(id)).findFirst().orElseThrow();assertTrue(i.expired());assertEquals("RECHECK_REQUIRED",i.decisionEligibility());assertEquals(0,active());
    }
    @Test void sameTimestampPaginationScansEveryRequestExactlyOnce() throws Exception {
        original=prepared();reportDecide(original,false,reportProof(checker,original,false));Instant at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);Set<UUID> expected=new HashSet<>();for(int n=0;n<26;n++)expected.add(fixture(UUID.randomUUID(),at,Instant.now().minusSeconds(1)));
        var first=queue();assertEquals(25,first.items().size());assertNotNull(first.next());var second=supervision.queue(independent.user().getId(),independent.sid(),first.next());Set<UUID> got=new HashSet<>();first.items().forEach(i->assertTrue(got.add(i.requestId())));second.items().forEach(i->assertTrue(got.add(i.requestId())));assertEquals(expected,got);assertNull(second.next());assertEquals(0,active());
    }
    @Test void sameUuidInBothBusinessKindsIsNotCollapsed() throws Exception {
        initiated();var r=withdraw(UUID.randomUUID());Instant at=jdbc.queryForObject("SELECT created_at FROM relationship_exit_rescissions WHERE id=?",Timestamp.class,r.payload().id()).toInstant();
        fixture(r.payload().id(),at,Instant.now().minusSeconds(1));var matches=queue().items().stream().filter(i->i.requestId().equals(r.payload().id())).toList();assertEquals(2,matches.size());assertEquals(List.of(ExitApprovalSupervisionService.Kind.EXIT,ExitApprovalSupervisionService.Kind.RESCISSION),matches.stream().map(ExitApprovalSupervisionService.Item::kind).toList());assertEquals(1,active());
    }
    @Test void missingCursorPartsFutureAndNegativeTimesAreRejected() {
        for(var c:List.of(new ExitApprovalSupervisionService.Cursor(null,ExitApprovalSupervisionService.Kind.EXIT,UUID.randomUUID()),new ExitApprovalSupervisionService.Cursor(Instant.now(),null,UUID.randomUUID()),new ExitApprovalSupervisionService.Cursor(Instant.now(),ExitApprovalSupervisionService.Kind.EXIT,null),new ExitApprovalSupervisionService.Cursor(Instant.now().plusSeconds(60),ExitApprovalSupervisionService.Kind.EXIT,UUID.randomUUID()),new ExitApprovalSupervisionService.Cursor(Instant.EPOCH.minusSeconds(1),ExitApprovalSupervisionService.Kind.EXIT,UUID.randomUUID())))assertThrows(ResponseStatusException.class,()->supervision.queue(independent.user().getId(),independent.sid(),c));
    }
    @ParameterizedTest @ValueSource(strings={"disabled","expired","recovery","role"})
    void currentSupervisorAuthorityIsChecked(String how) throws Exception {
        original=prepared();var u=users.findById(independent.user().getId()).orElseThrow();switch(how){case "disabled"->u.setEnabled(false);case "expired"->u.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));case "recovery"->u.setOperatorRecoveryRequestId(UUID.randomUUID());case "role"->u.setRole(UserRole.OPERATOR);default->throw new AssertionError();}users.saveAndFlush(u);assertThrows(ResponseStatusException.class,this::queue);
    }
    @Test void revokedAndForeignSessionsCannotRead() throws Exception {original=prepared();assertThrows(ResponseStatusException.class,()->supervision.queue(independent.user().getId(),checker.sid(),beginning));var t=tokens.findBySessionId(independent.sid()).orElseThrow();t.setRevoked(true);tokens.saveAndFlush(t);assertThrows(ResponseStatusException.class,this::queue);}
    @Test void auditFailureReturnsNoListOrFinancialEffect() throws Exception {original=prepared();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EXIT_APPROVAL_SUPERVISION_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::queue);reset(audit);assertEquals(1,queue().items().size());assertEquals(0,active());}
    @Test void httpBoundaryNoStoreAndMalformedKind() throws Exception {
        original=prepared();String path="/api/admin/compliance/exit-approval-supervision";var req=get(path).header("Authorization","Bearer "+independent.access());if(beginning!=null)req.param("afterCreated",beginning.createdAt().toString()).param("afterKind",beginning.kind().name()).param("afterRequest",beginning.requestId().toString());String body=mvc.perform(req).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();assertTrue(body.contains(original.payload().id().toString()));assertFalse(body.contains(facts().rationale()));for(String token:List.of(maker.access(),customerAccess,account(UserRole.AUDITOR).access()))mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).header("Authorization","Bearer "+independent.access()).param("afterKind","UNKNOWN")).andExpect(status().isBadRequest());
    }
}
