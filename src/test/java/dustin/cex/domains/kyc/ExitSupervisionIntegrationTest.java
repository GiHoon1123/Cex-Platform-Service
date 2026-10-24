package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.CustomerDeclaration;
import dustin.cex.domains.edd.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** 실제 종료/철회 승인으로 현재 효과를 만들고 감독이 원문 권한이나 금융 변경을 만들지 않는지 확인한다. */
class ExitSupervisionIntegrationTest extends ExitTestSupport {
    @Autowired ExitSupervisionService supervision;
    ExitSupervisionService.Cursor beginning;
    @BeforeEach void cursor() {
        // 다른 테스트의 불변 역사를 지우지 않고 이번 표본 앞의 확정 위치를 저장한다.
        var rows=jdbc.queryForList("SELECT review_id,decided_at FROM relationship_exit_decisions WHERE approved ORDER BY decided_at DESC,review_id DESC LIMIT 1");
        beginning=rows.isEmpty()?null:new ExitSupervisionService.Cursor(((Timestamp)rows.getFirst().get("decided_at")).toInstant(),(UUID)rows.getFirst().get("review_id"));
    }
    ExitSupervisionService.Page queue(){return supervision.queue(independent.user().getId(),independent.sid(),beginning);}
    void unavailable(Account account,String how) {
        var u=users.findById(account.user().getId()).orElseThrow();
        switch(how){case "disabled"->u.setEnabled(false);case "expired"->u.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));case "recovery"->u.setOperatorRecoveryRequestId(UUID.randomUUID());case "role"->u.setRole(UserRole.AUDITOR);case "identity"->u.setOperatorPersonId(UUID.randomUUID());default->throw new AssertionError();}
        users.saveAndFlush(u);
    }
    void nextCustomer() {
        customer=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());
        var token=tokens.saveAndFlush(RefreshToken.builder().userId(customer.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());
        sid=token.getSessionId();customerAccess=jwt.generateAccessToken(customer.getId(),sid);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));
    }
    @Test void unrelatedApproverSeesActiveMetadataWithoutPrivateJudgmentOrAuthority() throws Exception {
        initiated();var page=queue();assertEquals(1,page.scanned());var item=page.items().getFirst();assertEquals(original.payload().id(),item.exitId());assertFalse(item.requesterUnavailable());assertEquals(1,item.members().stream().filter(ExitSupervisionService.Member::rescissionRequesterAvailable).count());
        String body=json.writeValueAsString(page);for(String word:List.of(facts().rationale(),"factsMac","payloadHash","requesterPerson","dossier"))assertFalse(body.contains(word));
        assertThrows(ResponseStatusException.class,()->reporting.inspect(independent.user().getId(),independent.sid(),original.payload().id(),0));
        assertThrows(ResponseStatusException.class,()->rescissions.propose(independent.user().getId(),independent.sid(),original.payload().id(),checker.user().getId(),UUID.randomUUID(),withdrawalFacts()));blocked();
    }
    @Test void pendingRejectedAndRescindedApprovalsAreNotActive() throws Exception {
        original=prepared();assertTrue(queue().items().isEmpty());reportDecide(original,false,reportProof(checker,original,false));assertTrue(queue().items().isEmpty());
        original=request(UUID.randomUUID(),facts());reportDecide(original,true,reportProof(checker,original,true));assertEquals(1,queue().items().size());approveWithdrawal(withdraw(UUID.randomUUID()));assertTrue(queue().items().isEmpty());assertEquals(1,queue().scanned());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","expired","recovery","role","identity"})
    void noEligibleOriginalRequesterRemainsVisible(String how) throws Exception {
        initiated();unavailable(maker,how);var item=queue().items().getFirst();assertTrue(item.requesterUnavailable());assertTrue(item.members().get(1).historyAvailable());assertEquals(1,active());
    }
    @Test void oldMfaDoesNotMeanStaffIdentityOrRoleHasDisappeared() throws Exception {
        initiated();var t=tokens.findBySessionId(maker.sid()).orElseThrow();t.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(t);assertFalse(queue().items().getFirst().requesterUnavailable());
    }
    @Test void newCustomerDeclarationAndDisabledCustomerDoNotHideActiveExit() throws Exception {
        initiated();cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));var u=users.findById(customer.getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);assertEquals(original.payload().id(),queue().items().getFirst().exitId());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","expired","recovery","role"})
    void serviceRechecksSupervisorRoleAndAccess(String how) throws Exception {initiated();unavailable(independent,how);assertThrows(ResponseStatusException.class,this::queue);}
    @Test void staleRevokedAndForeignSessionCannotRead() throws Exception {
        initiated();assertThrows(ResponseStatusException.class,()->supervision.queue(independent.user().getId(),checker.sid(),beginning));var t=tokens.findBySessionId(independent.sid()).orElseThrow();t.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(t);assertThrows(ResponseStatusException.class,this::queue);t.setMfaVerifiedAt(LocalDateTime.now());t.setRevoked(true);tokens.saveAndFlush(t);assertThrows(ResponseStatusException.class,this::queue);
    }
    @Test void incompleteNegativeAndFutureCursorsAreRejected() {
        for(var c:List.of(new ExitSupervisionService.Cursor(null,UUID.randomUUID()),new ExitSupervisionService.Cursor(Instant.now(),null),new ExitSupervisionService.Cursor(Instant.EPOCH.minusSeconds(1),UUID.randomUUID()),new ExitSupervisionService.Cursor(Instant.now().plusSeconds(60),UUID.randomUUID())))assertThrows(ResponseStatusException.class,()->supervision.queue(independent.user().getId(),independent.sid(),c));
    }
    @Test void auditFailureReturnsNoMetadataAndRetryDoesNotChangeHold() throws Exception {
        initiated();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("EXIT_SUPERVISION_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::queue);reset(audit);assertEquals(1,queue().items().size());assertEquals(1,active());assertFalse(jdbc.queryForList("SELECT detail FROM security_audit WHERE action='EXIT_SUPERVISION_READ'").toString().contains(facts().rationale()));
    }
    @Test void emptyFilteredPageAdvancesAndLaterActiveExitIsNotLost() throws Exception {
        // 표본 26개 모두 실제 회부/종료/철회 서명을 거친다. 이력 제약이나 트리거를 끄지 않는다.
        for(int n=0;n<25;n++){if(n>0)nextCustomer();initiated();approveWithdrawal(withdraw(UUID.randomUUID()));}
        var first=queue();assertEquals(25,first.scanned());assertTrue(first.items().isEmpty());assertNotNull(first.next());nextCustomer();initiated();
        var second=supervision.queue(independent.user().getId(),independent.sid(),first.next());assertEquals(List.of(original.payload().id()),second.items().stream().map(ExitSupervisionService.Item::exitId).toList());assertNull(second.next());
    }
    @Test void httpRoleBoundaryNoStoreAndIncompleteCursor() throws Exception {
        initiated();String path="/api/admin/compliance/exit-supervision";var request=get(path).header("Authorization","Bearer "+independent.access());if(beginning!=null)request.param("afterDecision",beginning.decidedAt().toString()).param("afterReview",beginning.reviewId().toString());String body=mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();assertTrue(body.contains(original.payload().id().toString()));assertFalse(body.contains(facts().rationale()));
        for(String token:List.of(maker.access(),customerAccess,account(UserRole.AUDITOR).access()))mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).header("Authorization","Bearer "+independent.access()).param("afterReview",UUID.randomUUID().toString())).andExpect(status().isBadRequest());
    }
}
