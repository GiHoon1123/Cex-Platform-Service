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

/** 실제 고객 접수·열람 감사와 감독 역할을 사용해 메타데이터/원문 경계를 검증한다. */
class CustomerReviewSupervisionIntegrationTest extends ExitTestSupport {
    @Autowired CustomerReviewSupervisionService supervision;@Autowired CustomerExitReviewService requests;
    CustomerReviewSupervisionService.Cursor beginning;
    @BeforeEach void cursor(){var rows=jdbc.queryForList("SELECT id,created_at FROM customer_exit_review_requests ORDER BY created_at DESC,id DESC LIMIT 1");beginning=rows.isEmpty()?null:new CustomerReviewSupervisionService.Cursor(((Timestamp)rows.getFirst().get("created_at")).toInstant(),(UUID)rows.getFirst().get("id"));}
    CustomerReviewSupervisionService.Page queue(){return supervision.queue(independent.user().getId(),independent.sid(),beginning);}
    CustomerExitReviewService.Receipt submit(){return requests.submit(customer.getId(),sid,UUID.randomUUID(),new CustomerExitReviewService.Submission("고객 비공개 재검토 사유"));}
    void nextCustomer(){customer=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());var token=tokens.saveAndFlush(RefreshToken.builder().userId(customer.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());sid=token.getSessionId();customerAccess=jwt.generateAccessToken(customer.getId(),sid);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));}
    @Test void supervisorSeesUnviewedRequestButCannotReadReason() throws Exception {
        initiated();var receipt=submit();var item=queue().items().getFirst();assertEquals(receipt.id(),item.requestId());assertEquals(original.payload().id(),item.exitId());assertNull(item.latestViewRecordedAt());assertFalse(item.orphaned());String body=json.writeValueAsString(queue());for(String w:List.of("비공개 재검토 사유","rationale","content_mac","payloadHash"))assertFalse(body.contains(w));assertThrows(ResponseStatusException.class,()->requests.inspect(independent.user().getId(),independent.sid(),receipt.id()));assertEquals(1,active());
    }
    @Test void successfulViewIsVisibleButDoesNotHideOrCompleteRequest() throws Exception {initiated();var receipt=submit();requests.inspect(maker.user().getId(),maker.sid(),receipt.id());assertNotNull(queue().items().getFirst().latestViewRecordedAt());assertEquals(1,queue().items().size());assertEquals("RECEIVED",requests.own(customer.getId(),sid,receipt.id()).status());}
    @Test void viewAuditFailureDoesNotPretendReasonWasProvided() throws Exception {initiated();var receipt=submit();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_EXIT_REVIEW_VIEWED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->requests.inspect(maker.user().getId(),maker.sid(),receipt.id()));reset(audit);assertNull(queue().items().getFirst().latestViewRecordedAt());}
    @Test void withdrawalAndDisabledCustomerDoNotEraseOutstandingRequest() throws Exception {initiated();var receipt=submit();approveWithdrawal(withdraw(UUID.randomUUID()));var u=users.findById(customer.getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);assertEquals(receipt.id(),queue().items().getFirst().requestId());assertEquals(0,active());}
    @Test void missingOriginalPeopleAreVisibleWithoutGrantingTakeover() throws Exception {initiated();submit();for(var a:List.of(maker,checker)){var u=users.findById(a.user().getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);}assertTrue(queue().items().getFirst().orphaned());assertTrue(queue().items().getFirst().members().stream().noneMatch(CustomerReviewSupervisionService.Member::available));}
    @ParameterizedTest @ValueSource(strings={"disabled","expired","recovery","role"})
    void currentSupervisorAuthorityIsRequired(String how) throws Exception {var u=users.findById(independent.user().getId()).orElseThrow();switch(how){case "disabled"->u.setEnabled(false);case "expired"->u.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));case "recovery"->u.setOperatorRecoveryRequestId(UUID.randomUUID());case "role"->u.setRole(UserRole.OPERATOR);default->throw new AssertionError();}users.saveAndFlush(u);assertThrows(ResponseStatusException.class,this::queue);}
    @Test void staleRevokedAndForeignSessionsCannotRead(){assertThrows(ResponseStatusException.class,()->supervision.queue(independent.user().getId(),maker.sid(),beginning));var t=tokens.findBySessionId(independent.sid()).orElseThrow();t.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(t);assertThrows(ResponseStatusException.class,this::queue);t.setMfaVerifiedAt(LocalDateTime.now());t.setRevoked(true);tokens.saveAndFlush(t);assertThrows(ResponseStatusException.class,this::queue);}
    @Test void malformedCursorsAreRejected(){for(var c:List.of(new CustomerReviewSupervisionService.Cursor(null,UUID.randomUUID()),new CustomerReviewSupervisionService.Cursor(Instant.now(),null),new CustomerReviewSupervisionService.Cursor(Instant.EPOCH.minusSeconds(1),UUID.randomUUID()),new CustomerReviewSupervisionService.Cursor(Instant.now().plusSeconds(60),UUID.randomUUID())))assertThrows(ResponseStatusException.class,()->supervision.queue(independent.user().getId(),independent.sid(),c));}
    @Test void auditFailureReturnsNoMetadata(){doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_REVIEW_SUPERVISION_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,this::queue);reset(audit);assertNotNull(queue());}
    @Test void twentySixRealReceiptsSpanTwoPagesWithoutLosingHistory() throws Exception {for(int n=0;n<26;n++){if(n%3==0){if(n>0)nextCustomer();initiated();}submit();}var first=queue();assertEquals(25,first.items().size());assertNotNull(first.next());var second=supervision.queue(independent.user().getId(),independent.sid(),first.next());assertEquals(1,second.items().size());Set<UUID> ids=new HashSet<>();first.items().forEach(i->assertTrue(ids.add(i.requestId())));second.items().forEach(i->assertTrue(ids.add(i.requestId())));assertEquals(26,ids.size());assertNull(second.next());}
    @Test void httpNoStoreRoleAndCursorBoundaries() throws Exception {initiated();var receipt=submit();String path="/api/admin/compliance/customer-review-supervision";var request=get(path).header("Authorization","Bearer "+independent.access());if(beginning!=null)request.param("afterReceived",beginning.receivedAt().toString()).param("afterRequest",beginning.requestId().toString());String body=mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();assertTrue(body.contains(receipt.id().toString()));for(String access:List.of(maker.access(),customerAccess,account(UserRole.AUDITOR).access()))mvc.perform(get(path).header("Authorization","Bearer "+access)).andExpect(status().isForbidden());mvc.perform(get(path)).andExpect(status().isUnauthorized());}
}
