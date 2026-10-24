package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.CustomerDeclaration;
import dustin.cex.domains.cdd.EddService;
import dustin.cex.domains.security.OperatorMfaService;
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

/** 본인 접수부터 지정 인물 열람·감사·암호화·중복/경합과 기존 금융/보고 보존까지 검증한다. */
class CustomerExitReviewIntegrationTest extends ExitTestSupport {
    @Autowired CustomerExitReviewService requests;
    @Autowired ReportingReviewService reports;@Autowired ReportingFollowupService followups;
    static final String REASON="고객이 정정 검토를 요청한 비공개 사유\n확인 부탁드립니다";
    CustomerExitReviewService.Submission reason(){return new CustomerExitReviewService.Submission(REASON);}
    CustomerExitReviewService.Receipt submit(UUID key){return requests.submit(customer.getId(),sid,key,reason());}
    long rows(){return jdbc.queryForObject("SELECT count(*) FROM customer_exit_review_requests WHERE user_id=?",Long.class,customer.getId());}
    @Test void customerReceivesOnlyReceiptAndNoFinancialOrOriginalDecisionChanges() throws Exception {
        initiated();var before=service.status(customer.getId());var receipt=submit(UUID.randomUUID());assertEquals("RECEIVED",receipt.status());assertEquals(1,rows());assertEquals(1,active());assertEquals(before,service.status(customer.getId()));assertEquals("EXIT_PENDING",reporting.customerStatus(customer.getId()).state());blocked();
        String text=json.writeValueAsString(receipt);for(String w:List.of("비공개 사유","exitId","userId","APPROVED","factsMac",original.payloadHash()))assertFalse(text.contains(w));assertEquals(receipt,requests.own(customer.getId(),sid,receipt.id()));assertEquals(List.of(receipt),requests.ownList(customer.getId(),sid,0));
    }
    @Test void sameKeyReturnsSameReceiptButChangedReasonConflicts() throws Exception {
        initiated();UUID key=UUID.randomUUID();var first=submit(key);assertEquals(first,submit(key));assertThrows(ResponseStatusException.class,()->requests.submit(customer.getId(),sid,key,new CustomerExitReviewService.Submission("다른 사유")));assertEquals(1,rows());
    }
    @Test void simultaneousSameKeyCreatesExactlyOneReceipt() throws Exception {
        initiated();UUID key=UUID.randomUUID();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->{start.await();return submit(key);});var b=pool.submit(()->{start.await();return submit(key);});start.countDown();assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(1,rows());}finally{pool.shutdownNow();}
    }
    @Test void dailyLimitAllowsSupplementaryRequestsAndDoesNotBlockSameKeyReplay() throws Exception {
        initiated();UUID key=UUID.randomUUID();var first=submit(key);submit(UUID.randomUUID());submit(UUID.randomUUID());var e=assertThrows(ResponseStatusException.class,()->submit(UUID.randomUUID()));assertEquals(429,e.getStatusCode().value());assertEquals(first,submit(key));assertEquals(3,rows());
    }
    @Test void noCurrentApprovedExitCannotAcceptNewRequest() throws Exception {
        assertThrows(ResponseStatusException.class,()->submit(UUID.randomUUID()));original=prepared();assertThrows(ResponseStatusException.class,()->submit(UUID.randomUUID()));reportDecide(original,false,reportProof(checker,original,false));assertThrows(ResponseStatusException.class,()->submit(UUID.randomUUID()));assertEquals(0,rows());
    }
    @Test void responseLossReplayAfterWithdrawalKeepsOldReceiptWithoutNewApplication() throws Exception {
        initiated();UUID key=UUID.randomUUID();var first=submit(key);approveWithdrawal(withdraw(UUID.randomUUID()));assertEquals(first,submit(key));assertThrows(ResponseStatusException.class,()->submit(UUID.randomUUID()));assertEquals(first,requests.own(customer.getId(),sid,first.id()));assertEquals(REASON,requests.inspect(maker.user().getId(),maker.sid(),first.id()).rationale());assertEquals(1,rows());assertEquals(0,active());
    }
    @Test void oldKeyIsNotRetargetedToLaterExit() throws Exception {
        initiated();UUID key=UUID.randomUUID();var first=submit(key);UUID old=original.payload().id();approveWithdrawal(withdraw(UUID.randomUUID()));
        referral=propose(EddService.Finding.COMPLIANCE_ESCALATION);decide(referral,true,proof(checker,referral,true));original=request(UUID.randomUUID(),facts());reportDecide(original,true,reportProof(checker,original,true));var next=submit(UUID.randomUUID());assertEquals(first,submit(key));assertNotEquals(first.id(),next.id());assertEquals(old,jdbc.queryForObject("SELECT exit_id FROM customer_exit_review_requests WHERE id=?",UUID.class,first.id()));assertEquals(original.payload().id(),jdbc.queryForObject("SELECT exit_id FROM customer_exit_review_requests WHERE id=?",UUID.class,next.id()));assertEquals(1,active());
    }
    @Test void onlyCurrentOriginalParticipantsCanReadReasonAndAssignedReceipts() throws Exception {
        initiated();var receipt=submit(UUID.randomUUID());for(var a:List.of(maker,checker)){assertEquals(REASON,requests.inspect(a.user().getId(),a.sid(),receipt.id()).rationale());assertEquals(List.of(receipt),requests.assignedList(a.user().getId(),a.sid(),original.payload().id(),0));}
        assertThrows(ResponseStatusException.class,()->requests.inspect(independent.user().getId(),independent.sid(),receipt.id()));assertThrows(ResponseStatusException.class,()->requests.assignedList(independent.user().getId(),independent.sid(),original.payload().id(),0));assertThrows(ResponseStatusException.class,()->requests.inspect(customer.getId(),sid,receipt.id()));
    }
    @Test void customerCannotReadAnotherCustomersReceipt() throws Exception {
        initiated();var receipt=submit(UUID.randomUUID());var other=users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash(hash).build());var token=tokens.saveAndFlush(RefreshToken.builder().userId(other.getId()).tokenHash(UUID.randomUUID().toString()).expiresAt(LocalDateTime.now().plusHours(1)).build());assertThrows(ResponseStatusException.class,()->requests.own(other.getId(),token.getSessionId(),receipt.id()));assertTrue(requests.ownList(other.getId(),token.getSessionId(),0).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","expired","revoked","foreign"})
    void serviceChecksCurrentCustomerAndSession(String how) throws Exception {
        initiated();UUID session=sid;var token=tokens.findBySessionId(sid).orElseThrow();switch(how){case "disabled"->{var u=users.findById(customer.getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);}case "expired"->{token.setExpiresAt(LocalDateTime.now().minusSeconds(1));tokens.saveAndFlush(token);}case "revoked"->{token.setRevoked(true);tokens.saveAndFlush(token);}case "foreign"->session=maker.sid();default->throw new AssertionError();}UUID checked=session;assertThrows(ResponseStatusException.class,()->requests.submit(customer.getId(),checked,UUID.randomUUID(),reason()));assertEquals(0,rows());
    }
    @Test void changedIdentityAndStaleMfaCannotProvideCustomerReason() throws Exception {
        initiated();var receipt=submit(UUID.randomUUID());var token=tokens.findBySessionId(maker.sid()).orElseThrow();token.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(token);assertThrows(ResponseStatusException.class,()->requests.inspect(maker.user().getId(),maker.sid(),receipt.id()));var u=users.findById(checker.user().getId()).orElseThrow();u.setOperatorPersonId(UUID.randomUUID());users.saveAndFlush(u);assertThrows(ResponseStatusException.class,()->requests.inspect(checker.user().getId(),checker.sid(),receipt.id()));
    }
    @Test void auditFailureRollsBackReceiptAndSameKeyRetries() throws Exception {
        initiated();UUID key=UUID.randomUUID();doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_EXIT_REVIEW_RECEIVED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->submit(key));assertEquals(0,rows());reset(audit);assertNotNull(submit(key));assertEquals(1,rows());assertEquals(1,active());
    }
    @Test void failedViewAuditDoesNotReturnPlaintextAndRetrySucceeds() throws Exception {
        initiated();var receipt=submit(UUID.randomUUID());doThrow(new IllegalStateException("격리 감사 실패")).when(audit).record(any(),eq("CUSTOMER_EXIT_REVIEW_VIEWED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->requests.inspect(maker.user().getId(),maker.sid(),receipt.id()));reset(audit);assertEquals(REASON,requests.inspect(maker.user().getId(),maker.sid(),receipt.id()).rationale());
    }
    @Test void ciphertextCopiedToAnotherRequestFailsContextAuthentication() throws Exception {
        initiated();var first=submit(UUID.randomUUID());UUID copied=UUID.randomUUID();jdbc.update("INSERT INTO customer_exit_review_requests(id,user_id,exit_id,request_key,reason_cipher,content_mac,fingerprint_key_id,evidence_key_id) SELECT ?,user_id,exit_id,?,reason_cipher,content_mac,fingerprint_key_id,evidence_key_id FROM customer_exit_review_requests WHERE id=?",copied,UUID.randomUUID(),first.id());assertThrows(IllegalStateException.class,()->requests.inspect(maker.user().getId(),maker.sid(),copied));
    }
    @Test void immutableRecordAndAuditContainNoReasonPlaintext() throws Exception {
        initiated();submit(UUID.randomUUID());for(String sql:List.of("UPDATE customer_exit_review_requests SET content_mac=content_mac","DELETE FROM customer_exit_review_requests","TRUNCATE customer_exit_review_requests"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));assertEquals(1,rows());byte[] stored=jdbc.queryForObject("SELECT reason_cipher FROM customer_exit_review_requests WHERE user_id=?",byte[].class,customer.getId());assertFalse(new String(stored,java.nio.charset.StandardCharsets.UTF_8).contains("비공개 사유"));assertFalse(jdbc.queryForList("SELECT detail FROM security_audit WHERE actor_id IN (?,?,?)",customer.getId(),maker.user().getId(),checker.user().getId()).toString().contains("비공개 사유"));
    }
    @Test void customerRequestAndInspectionPreserveExistingFinancialReservation() throws Exception {
        verify(false);allowed();if(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'",Long.class)==0)ledger.initializeOpening();balances.saveAndFlush(dustin.cex.domains.balance.model.entity.UserBalance.builder().userId(customer.getId()).mintAddress("USDT").available(new java.math.BigDecimal("1000")).locked(java.math.BigDecimal.ZERO).build());new TransactionTemplate(manager).executeWithoutResult(t->ledger.record("test-customer-review-seed:"+UUID.randomUUID(),"TEST_FIXTURE",List.of(dustin.cex.domains.ledger.LedgerService.p(customer.getId(),"AVAILABLE","USDT",new java.math.BigDecimal("1000")),dustin.cex.domains.ledger.LedgerService.p(0,"OPENING","USDT",new java.math.BigDecimal("-1000")))));
        when(feePolicies.getFeeConfig("BTC","USDT")).thenReturn(dustin.cex.domains.fee.model.entity.FeeConfig.builder().feeRate(java.math.BigDecimal.ZERO).feeType("both").isActive(true).build());when(engine.submitOrder(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),any())).thenReturn(true);
        var command=dustin.cex.domains.order.model.dto.CreateOrderRequest.builder().baseMint("BTC").quoteMint("USDT").orderType("buy").orderSide("limit").price(new java.math.BigDecimal("10000")).amount(new java.math.BigDecimal("0.001")).build();UUID orderKey=UUID.randomUUID();orders.createOrder(customer.getId(),orderKey,command);cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(CustomerDeclaration.Pep.NO));initiated();var before=balances.findByUserIdAndMintAddress(customer.getId(),"USDT").orElseThrow();long count=jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE owner_id=?",Long.class,customer.getId());var receipt=submit(UUID.randomUUID());requests.inspect(maker.user().getId(),maker.sid(),receipt.id());var after=balances.findByUserIdAndMintAddress(customer.getId(),"USDT").orElseThrow();assertEquals(before.getAvailable(),after.getAvailable());assertEquals(before.getLocked(),after.getLocked());assertEquals(count,jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE owner_id=?",Long.class,customer.getId()));assertFalse(orders.createOrder(customer.getId(),orderKey,command).created());blocked();org.mockito.Mockito.verify(engine,times(1)).submitOrder(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),any());
    }
    @Test void receivedRequestDoesNotRemoveConfidentialReportingDuty() throws Exception {
        initiated();var report=reports.propose(maker.user().getId(),maker.sid(),referral.payload().id(),checker.user().getId(),UUID.randomUUID(),new ReportingReviewService.Facts(ReportingReviewService.Conclusion.REPORT_REQUIRED,"고객 재검토와 별개의 비공개 보고 근거"));for(int page=0;page<report.payload().dossier().pageCount();page++)reports.inspect(checker.user().getId(),checker.sid(),report.payload().id(),page);var o=reports.options(checker.user().getId(),checker.sid(),report.payload().id(),report.payloadHash(),true).authentication();reports.decide(checker.user().getId(),checker.sid(),report.payload().id(),report.payloadHash(),true,new OperatorMfaService.AuthorizationProof(o.challengeId(),checker.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null)));submit(UUID.randomUUID());assertTrue(followups.queue(maker.user().getId(),maker.sid(),null).items().stream().anyMatch(i->i.reviewId().equals(report.payload().id())));assertEquals(1,active());blocked();
    }
    @Test void existingAmbientTransactionCannotChangeSubmissionIsolation() throws Exception {
        initiated();assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->new TransactionTemplate(manager).execute(t->submit(UUID.randomUUID())));assertEquals(0,rows());
    }
    @Test void badPagesAndNullInputsAreRejectedWithoutCreatingRequests() throws Exception {
        initiated();assertThrows(ResponseStatusException.class,()->requests.ownList(customer.getId(),sid,-1));assertThrows(ResponseStatusException.class,()->requests.assignedList(maker.user().getId(),maker.sid(),original.payload().id(),10001));assertThrows(ResponseStatusException.class,()->submit(null));assertThrows(ResponseStatusException.class,()->requests.submit(customer.getId(),sid,UUID.randomUUID(),null));assertEquals(0,rows());
    }
    @Test void httpEndToEndReceiptListAndDesignatedReasonInspection() throws Exception {
        initiated();String path="/api/customer/relationship/review-requests";String raw=mvc.perform(post(path).header("Authorization","Bearer "+customerAccess).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(reason()))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED")).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();UUID id=UUID.fromString(json.readTree(raw).get("id").asText());assertFalse(raw.contains("비공개 사유"));mvc.perform(get(path+"/"+id).header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk());mvc.perform(get(path).header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));mvc.perform(get("/api/admin/compliance/relationship-exits/"+original.payload().id()+"/review-requests").header("Authorization","Bearer "+maker.access())).andExpect(status().isOk());mvc.perform(post("/api/admin/compliance/customer-review-requests/"+id+"/inspect").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk()).andExpect(jsonPath("$.rationale").value(REASON));
    }
    @Test void httpCustomerOperatorAuditorAndAnonymousBoundaries() throws Exception {
        initiated();var receipt=submit(UUID.randomUUID());String path="/api/customer/relationship/review-requests";for(String token:List.of(maker.access(),independent.access(),account(UserRole.AUDITOR).access()))mvc.perform(post(path).header("Authorization","Bearer "+token).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(reason()))).andExpect(status().isForbidden());mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(post("/api/admin/compliance/customer-review-requests/"+receipt.id()+"/inspect").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());mvc.perform(post("/api/admin/compliance/customer-review-requests/"+receipt.id()+"/inspect").header("Authorization","Bearer "+independent.access())).andExpect(status().isNotFound());
    }
}
