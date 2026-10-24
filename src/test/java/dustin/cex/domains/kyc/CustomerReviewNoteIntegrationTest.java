package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.CustomerDeclaration;
import dustin.cex.domains.edd.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 본인 제공·불변 처리 기록·암호화·동시 순번과 감사 원자성을 검증한다. */
class CustomerReviewNoteIntegrationTest extends ExitTestSupport {
    @Autowired CustomerExitReviewService requests;@Autowired CustomerReviewNoteService notes;
    CustomerExitReviewService.Receipt receipt;
    @BeforeEach void request() throws Exception {initiated();receipt=requests.submit(customer.getId(),sid,UUID.randomUUID(),new CustomerExitReviewService.Submission("고객 비공개 사유"));}
    CustomerReviewNoteService.Facts factsNote(){return new CustomerReviewNoteService.Facts(CustomerReviewNoteService.Progress.INVESTIGATING,"담당자의 비공개 처리 메모");}
    void view(Account a){requests.inspect(a.user().getId(),a.sid(),receipt.id());}
    CustomerReviewNoteService.Note record(UUID key){return notes.record(maker.user().getId(),maker.sid(),receipt.id(),key,factsNote());}
    long count(){return jdbc.queryForObject("SELECT count(*) FROM customer_exit_review_notes WHERE request_id=?",Long.class,receipt.id());}
    @Test void personalViewAllowsEncryptedInternalObservationWithoutCustomerStatusChange(){view(maker);var n=record(UUID.randomUUID());assertEquals(1,n.sequence());assertEquals(factsNote(),notes.inspect(checker.user().getId(),checker.sid(),n.id()).facts());assertFalse(json.writeValueAsString(n).contains("비공개 처리 메모"));assertEquals("RECEIVED",requests.own(customer.getId(),sid,receipt.id()).status());assertEquals(1,active());blocked();}
    @Test void absentOrOtherPersonsViewDoesNotAuthorizeNote(){assertThrows(ResponseStatusException.class,()->record(UUID.randomUUID()));view(checker);assertThrows(ResponseStatusException.class,()->record(UUID.randomUUID()));assertEquals(0,count());}
    @Test void anotherSessionViewCannotAuthorizeNote(){view(maker);assertThrows(ResponseStatusException.class,()->notes.record(maker.user().getId(),checker.sid(),receipt.id(),UUID.randomUUID(),factsNote()));assertEquals(0,count());}
    @Test void expiredViewIsNotARecentPersonalReceipt(){jdbc.update("INSERT INTO customer_exit_review_views(id,request_id,actor_id,actor_person,actor_version,session_id,custody_generation,custody_stamp,provided_at) VALUES(?,?,?,?,?,?,?,?,clock_timestamp()-interval '6 minutes')",UUID.randomUUID(),receipt.id(),maker.user().getId(),maker.user().getOperatorPersonId(),users.findById(maker.user().getId()).orElseThrow().getVersion(),maker.sid(),0,requests.assignedContext(maker.user().getId(),maker.sid(),receipt.id()).assignment().stamp());assertThrows(ResponseStatusException.class,()->record(UUID.randomUUID()));}
    @Test void sameKeyReplaysButDifferentProgressOrTextConflicts(){view(maker);UUID key=UUID.randomUUID();var first=record(key);assertEquals(first,record(key));assertThrows(ResponseStatusException.class,()->notes.record(maker.user().getId(),maker.sid(),receipt.id(),key,new CustomerReviewNoteService.Facts(CustomerReviewNoteService.Progress.ADDITIONAL_CHECK_REQUIRED,"다른 메모")));assertEquals(1,count());}
    @Test void concurrentDistinctKeysReceiveConsecutiveLogicalSequences() throws Exception {view(maker);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->{start.await();return record(UUID.randomUUID());});var b=pool.submit(()->{start.await();return record(UUID.randomUUID());});start.countDown();assertEquals(Set.of(1L,2L),Set.of(a.get(10,TimeUnit.SECONDS).sequence(),b.get(10,TimeUnit.SECONDS).sequence()));assertEquals(2,count());}finally{pool.shutdownNow();}}
    @Test void tenDailyNotesAreAllowedButReplayDoesNotSpendAnotherSlot(){view(maker);UUID key=UUID.randomUUID();var first=record(key);for(int i=1;i<10;i++)record(UUID.randomUUID());assertEquals(429,assertThrows(ResponseStatusException.class,()->record(UUID.randomUUID())).getStatusCode().value());assertEquals(first,record(key));}
    @Test void noteAuditFailureRollsBackSequenceAndAllowsSameKeyRetry(){view(maker);UUID key=UUID.randomUUID();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_REVIEW_NOTE_RECORDED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->record(key));assertEquals(0,count());reset(audit);assertEquals(1,record(key).sequence());}
    @Test void reasonAuditFailureAlsoRollsBackPersonalReceipt(){doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_EXIT_REVIEW_VIEWED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->view(maker));reset(audit);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM customer_exit_review_views WHERE request_id=?",Long.class,receipt.id()));assertThrows(ResponseStatusException.class,()->record(UUID.randomUUID()));}
    @Test void noteViewAuditFailureDoesNotReturnPlaintext(){view(maker);var n=record(UUID.randomUUID());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_REVIEW_NOTE_VIEWED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->notes.inspect(maker.user().getId(),maker.sid(),n.id()));reset(audit);assertNotNull(notes.inspect(maker.user().getId(),maker.sid(),n.id()));}
    @Test void outsiderAndCustomerCannotListOrReadInternalNotes(){view(maker);var n=record(UUID.randomUUID());assertThrows(ResponseStatusException.class,()->notes.list(independent.user().getId(),independent.sid(),receipt.id(),0));assertThrows(ResponseStatusException.class,()->notes.inspect(customer.getId(),sid,n.id()));}
    @Test void newActorVersionRequiresNewPersonalView(){view(maker);var u=users.findById(maker.user().getId()).orElseThrow();u.setUsername("버전 변경");users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->record(UUID.randomUUID()));view(maker);assertNotNull(record(UUID.randomUUID()));}
    @ParameterizedTest @ValueSource(strings={"disabled","expired","recovery","identity"})
    void lostCurrentAuthorityCannotWrite(String how){view(maker);var u=users.findById(maker.user().getId()).orElseThrow();switch(how){case "disabled"->u.setEnabled(false);case "expired"->u.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));case "recovery"->u.setOperatorRecoveryRequestId(UUID.randomUUID());case "identity"->u.setOperatorPersonId(UUID.randomUUID());default->throw new AssertionError();}users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->record(UUID.randomUUID()));}
    @Test void copiedCipherFailsItsRowAndLogicalSequenceContext(){view(maker);var n=record(UUID.randomUUID());UUID copied=UUID.randomUUID();jdbc.update("INSERT INTO customer_exit_review_notes(id,request_id,sequence,actor_id,request_key,progress,note_cipher,content_mac,evidence_key_id,fingerprint_key_id) SELECT ?,request_id,2,actor_id,?,progress,note_cipher,content_mac,evidence_key_id,fingerprint_key_id FROM customer_exit_review_notes WHERE id=?",copied,UUID.randomUUID(),n.id());assertThrows(IllegalStateException.class,()->notes.inspect(maker.user().getId(),maker.sid(),copied));}
    @Test void bothNewTablesRejectUpdateDeleteAndTruncate(){view(maker);record(UUID.randomUUID());for(String table:List.of("customer_exit_review_views","customer_exit_review_notes"))for(String sql:List.of("UPDATE "+table+" SET id=id","DELETE FROM "+table,"TRUNCATE "+table))assertThrows(DataAccessException.class,()->jdbc.execute(sql));assertEquals(1,count());}
    @Test void withdrawalDoesNotEraseInternalHistory() throws Exception {view(maker);var n=record(UUID.randomUUID());approveWithdrawal(withdraw(UUID.randomUUID()));assertEquals(List.of(n),notes.list(maker.user().getId(),maker.sid(),receipt.id(),0));assertEquals(0,active());}
    @Test void invalidFactsPagesAndAmbientTransactionAreRejected(){view(maker);assertThrows(ResponseStatusException.class,()->notes.record(maker.user().getId(),maker.sid(),receipt.id(),null,factsNote()));assertThrows(ResponseStatusException.class,()->notes.list(maker.user().getId(),maker.sid(),receipt.id(),-1));assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->new TransactionTemplate(manager).execute(t->record(UUID.randomUUID())));}
    @Test void httpInternalRoutesRequireAssignedCurrentPeople() throws Exception {view(maker);String url="/api/admin/compliance/customer-review-requests/"+receipt.id()+"/notes";String body=mvc.perform(post(url).header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(factsNote()))).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();UUID id=UUID.fromString(json.readTree(body).get("id").asText());assertFalse(body.contains("비공개 처리 메모"));mvc.perform(post("/api/admin/compliance/customer-review-notes/"+id+"/inspect").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk()).andExpect(jsonPath("$.facts.note").value(factsNote().note()));mvc.perform(get(url).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());mvc.perform(get(url).header("Authorization","Bearer "+independent.access())).andExpect(status().isNotFound());}
}
