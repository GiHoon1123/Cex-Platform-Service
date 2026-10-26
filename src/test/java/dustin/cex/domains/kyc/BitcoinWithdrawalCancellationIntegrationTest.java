package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dustin.cex.domains.bitcoin.BitcoinWithdrawalCancellations;
import dustin.cex.domains.security.OperatorMfaService;
import dustin.cex.shared.http.EngineWithdrawalClient;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 고객 서명·PG 금융 원본·소비 트랜잭션으로 취소 의도와 확정 반환의 경계를 검증한다. */
class BitcoinWithdrawalCancellationIntegrationTest extends BitcoinWithdrawalTestSupport {
    @Autowired BitcoinWithdrawalCancellations cancellations;
    final UUID engine=UUID.randomUUID();
    String reserveRaw;
    UUID reserved()throws Exception {
        return reserved(1);
    }
    UUID reserved(long sequence)throws Exception {
        var quote=price();UUID id=quote.payload().id();submit(id);
        reserveRaw=event(id,engine,"withdrawal_reserved",sequence);events.consumeOrderEvent(reserveRaw);
        when(engineWithdrawal.execute(argThat(c -> c!=null&&c.action()==EngineWithdrawalClient.Action.RELEASE)))
            .thenReturn(EngineWithdrawalClient.Outcome.DURABLY_RELEASED);
        return id;
    }
    String released(UUID id){return event(id,engine,"withdrawal_released",2);}
    void held(){amount(new BigDecimal("0.00999900"),GROSS);}

    @Test void confirmedReleaseAloneReturnsTheOriginalGrossIncludingTheFee()throws Exception {
        UUID id=reserved();
        assertEquals("CANCELLATION_PENDING",cancellations.cancel(customer.getId(),sid,id).state());held();
        assertEquals(1,count("bitcoin_withdrawal_release_attempts"));
        org.mockito.Mockito.verify(engineWithdrawal).execute(new EngineWithdrawalClient.Command(id,customer.getId(),"BTC",GROSS,EngineWithdrawalClient.Action.RELEASE));
        events.consumeOrderEvent(released(id));
        assertEquals("CANCELLED",withdrawals.status(customer.getId(),sid,id).state());amount(FUNDS,BigDecimal.ZERO);
        assertEquals(1,count("bitcoin_withdrawal_release_events"));
    }
    @Test void cancellationBeforeReserveConfirmationWaitsWithoutSendingRelease()throws Exception {
        when(engineWithdrawal.execute(any())).thenReturn(EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN);
        UUID id=price().payload().id();submit(id);
        assertEquals("CANCELLATION_PENDING",cancellations.cancel(customer.getId(),sid,id).state());
        assertEquals(0,count("bitcoin_withdrawal_release_attempts"));held();
        assertTrue(cancellations.processNext().isEmpty());
        events.consumeOrderEvent(event(id,engine,"withdrawal_reserved",1));
        when(engineWithdrawal.execute(any())).thenReturn(EngineWithdrawalClient.Outcome.DURABLY_RELEASED);
        assertEquals(id,cancellations.processNext().orElseThrow().withdrawalId());held();
        events.consumeOrderEvent(released(id));amount(FUNDS,BigDecimal.ZERO);
    }
    @Test void firstReserveRejectionFinishesPendingCancellationWithoutASecondRefund()throws Exception {
        UUID id=price().payload().id();submit(id);cancellations.cancel(customer.getId(),sid,id);
        events.consumeOrderEvent(event(id,engine,"withdrawal_rejected",1));
        assertEquals("REJECTED",cancellations.cancel(customer.getId(),sid,id).state());
        assertTrue(cancellations.processNext().isEmpty());
        assertEquals(0,count("bitcoin_withdrawal_release_attempts"));amount(FUNDS,BigDecimal.ZERO);
        assertThrows(IllegalStateException.class,()->events.consumeOrderEvent(released(id)));
        assertEquals(0,count("bitcoin_withdrawal_release_events"));
    }
    @Test void duplicateCancellationAndDuplicateReleaseCannotRefundTwice()throws Exception {
        UUID id=reserved();cancellations.cancel(customer.getId(),sid,id);cancellations.cancel(customer.getId(),sid,id);
        String raw=released(id);events.consumeOrderEvent(raw);events.consumeOrderEvent(raw);
        assertEquals("CANCELLED",cancellations.cancel(customer.getId(),sid,id).state());
        assertEquals(1,count("bitcoin_withdrawal_cancellations"));assertEquals(1,count("bitcoin_withdrawal_release_attempts"));
        assertEquals(1,count("bitcoin_withdrawal_release_events"));amount(FUNDS,BigDecimal.ZERO);
    }
    @Test void releaseWithoutCustomerCancellationRollsBackItsInboxReceipt()throws Exception {
        UUID id=reserved();assertThrows(IllegalStateException.class,()->events.consumeOrderEvent(released(id)));
        assertEquals(1,count("engine_event_receipts"));assertEquals(0,count("bitcoin_withdrawal_release_events"));held();
    }
    @Test void releaseFromAnotherEngineOrAnEarlierPositionCannotRefund()throws Exception {
        UUID id=reserved(2);cancellations.cancel(customer.getId(),sid,id);
        assertThrows(IllegalStateException.class,()->events.consumeOrderEvent(event(id,UUID.randomUUID(),"withdrawal_released",3)));
        assertThrows(IllegalStateException.class,()->events.consumeOrderEvent(event(id,engine,"withdrawal_released",1)));
        assertEquals(1,count("engine_event_receipts"));held();
    }
    @Test void uncertainReleaseHttpKeepsTheHoldAndTheSameDurableAttempt()throws Exception {
        UUID id=reserved();when(engineWithdrawal.execute(any())).thenReturn(EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN);
        cancellations.cancel(customer.getId(),sid,id);cancellations.cancel(customer.getId(),sid,id);
        assertEquals(1,count("bitcoin_withdrawal_release_attempts"));
        assertEquals("CANCELLATION_PENDING",withdrawals.status(customer.getId(),sid,id).state());held();
    }
    @Test void genericReleaseRejectionCannotRefundOrBecomeFinancialRejection()throws Exception {
        UUID id=reserved();when(engineWithdrawal.execute(any())).thenReturn(EngineWithdrawalClient.Outcome.DURABLY_REJECTED);
        cancellations.cancel(customer.getId(),sid,id);assertTrue(cancellations.processNext().isEmpty());held();
        assertEquals(0,count("bitcoin_withdrawal_release_events"));
        assertEquals("withdrawal_reserved",jdbc.queryForObject("SELECT event_type FROM bitcoin_withdrawal_reservation_events",String.class));
    }
    @Test void mandatoryAuditFailureRollsBackTheReleaseAndCanRetryTheSameEvent()throws Exception {
        UUID id=reserved();cancellations.cancel(customer.getId(),sid,id);String raw=released(id);
        doThrow(new IllegalStateException("격리 반환 감사 장애")).when(audit).record(isNull(),eq("BTC_WITHDRAWAL_RELEASED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->events.consumeOrderEvent(raw));reset(audit);
        assertEquals(1,count("engine_event_receipts"));assertEquals(0,count("bitcoin_withdrawal_release_events"));held();
        events.consumeOrderEvent(raw);amount(FUNDS,BigDecimal.ZERO);
    }
    @Test void cancellationOriginalAndReleaseHistoryCannotBeRewrittenOrTruncated()throws Exception {
        UUID id=reserved();cancellations.cancel(customer.getId(),sid,id);events.consumeOrderEvent(released(id));
        for(String table:List.of("bitcoin_withdrawal_cancellations","bitcoin_withdrawal_release_attempts","bitcoin_withdrawal_release_results","bitcoin_withdrawal_release_events")) {
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("DELETE FROM "+table));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE "+table+" CASCADE"));
        }
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("UPDATE bitcoin_withdrawal_cancellations SET session_id=gen_random_uuid()"));
        amount(FUNDS,BigDecimal.ZERO);
    }
    @Test void cancellationWithoutItsMandatoryAuditCannotCommit()throws Exception {
        UUID id=reserved();
        assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t ->
            jdbc.update("INSERT INTO bitcoin_withdrawal_cancellations(withdrawal_id,user_id,session_id) VALUES(?,?,?)",id,customer.getId(),sid)));
        assertEquals(0,count("bitcoin_withdrawal_cancellations"));held();
    }
    @Test void renewedCustomerSessionCanReadAndCancelItsHistoricalWithdrawal()throws Exception {
        UUID id=reserved();UUID old=sid;
        var login=auth.signin(new dustin.cex.domains.auth.model.dto.SigninRequest(customer.getEmail(),"security-test-password"));
        sid=UUID.fromString(jwt.verifyAccessToken(login.getAccessToken()).get("sid",String.class));
        var options=factor.authenticationOptions(customer.getId(),sid);
        var authenticated=factor.authenticate(customer.getId(),sid,options.challengeId(),browser.assertion(
            new OperatorMfaService.Options(options.challengeId(),options.publicKey()),"http://localhost:3003",true,counter++,null));
        sid=UUID.fromString(jwt.verifyAccessToken(authenticated.accessToken()).get("sid",String.class));assertNotEquals(old,sid);
        assertEquals("RESERVED",withdrawals.status(customer.getId(),sid,id).state());
        assertEquals("CANCELLATION_PENDING",cancellations.cancel(customer.getId(),sid,id).state());
        events.consumeOrderEvent(released(id));amount(FUNDS,BigDecimal.ZERO);
    }
    @Test void wrongOwnerAndRevokedSessionCannotCreateCancellationIntent()throws Exception {
        UUID id=reserved();
        assertThrows(ResponseStatusException.class,()->cancellations.cancel(customer.getId()+10000,sid,id));
        var token=tokens.findBySessionId(sid).orElseThrow();token.setRevoked(true);tokens.saveAndFlush(token);
        assertThrows(ResponseStatusException.class,()->cancellations.cancel(customer.getId(),sid,id));
        assertEquals(0,count("bitcoin_withdrawal_cancellations"));assertEquals(0,count("bitcoin_withdrawal_release_attempts"));held();
    }
    @Test void customerHttpCancellationAcceptsOnlyAnEmptyBody()throws Exception {
        UUID id=reserved();String path="/api/customer/withdrawals/BTC/quotes/"+id+"/reservations/cancellation";
        mvc.perform(post(path).header("Authorization","Bearer "+customerAccess).contentType("application/json").content("{\"amount\":\"2\"}"))
            .andExpect(status().isBadRequest());assertEquals(0,count("bitcoin_withdrawal_cancellations"));
        mvc.perform(post(path).header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization","Bearer "+customerAccess)).andExpect(status().isOk());
        assertEquals(1,count("bitcoin_withdrawal_cancellations"));held();
    }
    @Test void concurrentCancellationRequestsCreateOneReleaseAttempt()throws Exception {
        UUID id=reserved();var gate=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=workers.submit(()->{gate.await();return cancellations.cancel(customer.getId(),sid,id).state();});
            var b=workers.submit(()->{gate.await();return cancellations.cancel(customer.getId(),sid,id).state();});
            gate.countDown();assertEquals("CANCELLATION_PENDING",a.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("CANCELLATION_PENDING",b.get(15,java.util.concurrent.TimeUnit.SECONDS));
        }finally{gate.countDown();}
        assertEquals(1,count("bitcoin_withdrawal_cancellations"));assertEquals(1,count("bitcoin_withdrawal_release_attempts"));held();
    }
    @Test void independentReconciliationSeparatesPendingCancellationAndConfirmedRefund()throws Exception {
        UUID id=reserved();cancellations.cancel(customer.getId(),sid,id);
        var source=mock(dustin.cex.domains.reconciliation.EngineAuditClient.class);
        when(source.page(eq(0L),isNull(),isNull())).thenReturn(json.valueToTree(java.util.Map.of(
            "engine_id",engine.toString(),"through",1,"next_after",1,"events",List.of(reserveRaw))));
        var reconciliation=new dustin.cex.domains.reconciliation.ReconciliationService(jdbc,source,submissions,manager);
        assertEquals("INCOMPLETE",reconciliation.run().status());
        String release=released(id);events.consumeOrderEvent(release);
        when(source.page(eq(0L),isNull(),isNull())).thenReturn(json.valueToTree(java.util.Map.of(
            "engine_id",engine.toString(),"through",2,"next_after",2,"events",List.of(reserveRaw,release))));
        var report=reconciliation.run();
        assertEquals("MATCHED",report.status(),jdbc.queryForList("SELECT * FROM reconciliation_issues WHERE run_id=?",report.id()).toString());
        amount(FUNDS,BigDecimal.ZERO);
    }
    @Test void databaseRejectsReleaseAttemptBeforeTheReservationIsConfirmed()throws Exception {
        UUID id=price().payload().id();submit(id);cancellations.cancel(customer.getId(),sid,id);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "INSERT INTO bitcoin_withdrawal_release_attempts(id,withdrawal_id) VALUES(?,?)",UUID.randomUUID(),id));
        assertEquals(0,count("bitcoin_withdrawal_release_attempts"));held();
    }
    @Test void alteredGrossOrExtraReleaseFieldsCannotRefund()throws Exception {
        UUID id=reserved();cancellations.cancel(customer.getId(),sid,id);
        var raw=json.readTree(released(id));
        var altered=(tools.jackson.databind.node.ObjectNode)raw;altered.put("amount","0.01");
        assertThrows(IllegalStateException.class,()->events.consumeOrderEvent(json.writeValueAsString(altered)));
        altered.put("amount",GROSS.toPlainString());altered.put("fee","0");
        assertThrows(IllegalArgumentException.class,()->events.consumeOrderEvent(json.writeValueAsString(altered)));
        assertEquals(1,count("engine_event_receipts"));held();
    }
    @Test void uncertainReleaseRetriesOnlyTheSameIdAndGrossAfterTheDurableDelay()throws Exception {
        UUID id=reserved();when(engineWithdrawal.execute(any())).thenReturn(EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN);
        cancellations.cancel(customer.getId(),sid,id);assertTrue(cancellations.processNext().isEmpty());
        // 보존된 시각을 바꾸거나 불변 트리거를 끄지 않는다. 실제 재확인 대기 경계를 지난 뒤 실행한다.
        Thread.sleep(5100);
        when(engineWithdrawal.execute(any())).thenReturn(EngineWithdrawalClient.Outcome.DURABLY_RELEASED);
        assertEquals(id,cancellations.processNext().orElseThrow().withdrawalId());
        org.mockito.Mockito.verify(engineWithdrawal,times(2)).execute(new EngineWithdrawalClient.Command(id,customer.getId(),"BTC",GROSS,EngineWithdrawalClient.Action.RELEASE));
        assertEquals(2,count("bitcoin_withdrawal_release_attempts"));held();
        events.consumeOrderEvent(released(id));amount(FUNDS,BigDecimal.ZERO);
    }
    @Test void releaseReceiptAndLedgerWithoutMandatoryAuditCannotCommit()throws Exception {
        UUID id=reserved();cancellations.cancel(customer.getId(),sid,id);String raw=released(id),eventId=engine+":2:0";
        assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t -> {
            assertTrue(inbox.claim(eventId,engine.toString(),raw));
            ledger.record("engine:"+eventId,"BTC_WITHDRAWAL_RELEASED",List.of(
                dustin.cex.domains.ledger.LedgerService.p(customer.getId(),"LOCKED","BTC",GROSS.negate()),
                dustin.cex.domains.ledger.LedgerService.p(customer.getId(),"AVAILABLE","BTC",GROSS)));
            jdbc.update("""
                INSERT INTO bitcoin_withdrawal_release_events(withdrawal_id,event_id,engine_id,event_time,payload_hash,operation_key)
                 VALUES(?,?,?,?,?,?)
                """,id,eventId,engine,json.readTree(raw).path("timestamp").stringValue(),sha(raw),"engine:"+eventId);
        }));
        assertEquals(1,count("engine_event_receipts"));assertEquals(0,count("bitcoin_withdrawal_release_events"));held();
    }
}
