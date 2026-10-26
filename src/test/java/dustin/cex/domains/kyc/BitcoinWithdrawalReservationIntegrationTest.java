package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dustin.cex.domains.auth.model.dto.SigninRequest;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.mail.CustomerEmailVerification;
import dustin.cex.domains.security.*;
import dustin.cex.shared.http.EngineWithdrawalClient;
import dustin.cex.shared.kafka.KafkaOrderEventConsumer;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 고객 확인·패스키 서명·PG 원장·Kafka 소비기에서 출금 한 건의 잠금과 거절 반환을 검증한다. */
class BitcoinWithdrawalReservationIntegrationTest extends BitcoinWithdrawalTestSupport {
    @Test void exactSignatureReservesTheGrossAmountAndKeepsTheQuoteNetAmount()throws Exception {
        var quote=price();var result=submit(quote.payload().id());
        assertEquals("PENDING_ENGINE",result.state());assertEquals("0.01000000",result.amount());
        assertEquals("0.00000100",result.fee());assertEquals("0.01000100",result.total());
        amount(new BigDecimal("0.00999900"),GROSS);
        assertEquals(1,count("bitcoin_withdrawals"));assertEquals(1,count("customer_withdrawal_authorizations"));
        assertEquals(1,count("bitcoin_withdrawal_dispatch_attempts"));
        org.mockito.Mockito.verify(engineWithdrawal).execute(new EngineWithdrawalClient.Command(result.withdrawalId(),customer.getId(),"BTC",GROSS,EngineWithdrawalClient.Action.RESERVE));
    }
    @Test void loginAndQuoteWithoutOneTimeSignatureCannotReserve() {
        var quote=price();var options=challenge(quote.payload().id());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->withdrawals.submit(customer.getId(),sid,
            quote.payload().id(),null)).getStatusCode().value());
        assertEquals(0,count("bitcoin_withdrawals"));assertEquals(0,count("customer_withdrawal_authorizations"));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM customer_withdrawal_challenges WHERE id=?",Boolean.class,options.challengeId()));
        amount(FUNDS,BigDecimal.ZERO);verifyNoInteractions(engineWithdrawal);
    }
    @Test void signatureForAnotherQuoteCannotMoveTheGrossDebit()throws Exception {
        var original=price();var other=price();var proof=proof(challenge(original.payload().id()));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->withdrawals.submit(customer.getId(),sid,
            other.payload().id(),proof)).getStatusCode().value());
        assertEquals(0,count("bitcoin_withdrawals"));amount(FUNDS,BigDecimal.ZERO);verifyNoInteractions(engineWithdrawal);
    }
    @Test void duplicateRequestCannotConsumeTheProofOrReserveTwice()throws Exception {
        var quote=price();var options=challenge(quote.payload().id());var proof=proof(options);
        var first=withdrawals.submit(customer.getId(),sid,quote.payload().id(),proof);
        assertEquals(first,withdrawals.submit(customer.getId(),sid,quote.payload().id(),proof));
        amount(new BigDecimal("0.00999900"),GROSS);assertEquals(1,count("bitcoin_withdrawals"));
        assertEquals(1,count("bitcoin_withdrawal_dispatch_attempts"));
        org.mockito.Mockito.verify(engineWithdrawal,times(1)).execute(any());
    }
    @Test void confirmedReserveDoesNotDebitTwiceAndSecondEngineEventRollsBack()throws Exception {
        var quote=price();submit(quote.payload().id());UUID engine=UUID.randomUUID();
        String first=event(quote.payload().id(),engine,"withdrawal_reserved",1);
        events.consumeOrderEvent(first);events.consumeOrderEvent(first);
        assertEquals("RESERVED",withdrawals.status(customer.getId(),sid,quote.payload().id()).state());
        amount(new BigDecimal("0.00999900"),GROSS);
        assertEquals(1,count("bitcoin_withdrawal_reservation_events"));
        assertThrows(IllegalStateException.class,()->events.consumeOrderEvent(event(quote.payload().id(),engine,"withdrawal_rejected",2)));
        amount(new BigDecimal("0.00999900"),GROSS);
    }
    @Test void durableFinancialRejectionAloneReleasesTheOriginalGrossOnce()throws Exception {
        when(engineWithdrawal.execute(any())).thenReturn(EngineWithdrawalClient.Outcome.DURABLY_REJECTED);
        var quote=price();submit(quote.payload().id());
        // HTTP의 거절 확인은 금융 반환이 아니다. 카프카의 정확한 영속 결과를 별도 수신해야 한다.
        amount(new BigDecimal("0.00999900"),GROSS);
        String rejected=event(quote.payload().id(),UUID.randomUUID(),"withdrawal_rejected",1);
        events.consumeOrderEvent(rejected);events.consumeOrderEvent(rejected);
        assertEquals("REJECTED",withdrawals.status(customer.getId(),sid,quote.payload().id()).state());
        amount(FUNDS,BigDecimal.ZERO);assertEquals(1,count("bitcoin_withdrawal_reservation_events"));
        assertEquals(1,count("customer_withdrawal_authorizations"));
    }
    @Test void insufficientGrossBalanceDoesNotConsumeTheSignatureOrWriteAnOriginal()throws Exception {
        var quote=quotes.prepare(customer.getId(),sid,UUID.randomUUID(),ADDRESS,"0.02");
        var challenge=challenge(quote.payload().id());var signed=proof(challenge);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->withdrawals.submit(customer.getId(),sid,
            quote.payload().id(),signed)).getStatusCode().value());
        assertFalse(jdbc.queryForObject("SELECT consumed FROM customer_withdrawal_challenges WHERE id=?",Boolean.class,challenge.challengeId()));
        assertEquals(0,count("bitcoin_withdrawals"));assertEquals(0,count("customer_withdrawal_authorizations"));
        amount(FUNDS,BigDecimal.ZERO);verifyNoInteractions(engineWithdrawal);
    }
    @Test void auditFailureRollsBackTheProofAndTheEntireFinancialHold()throws Exception {
        var quote=price();var options=challenge(quote.payload().id());var signed=proof(options);
        doThrow(new IllegalStateException("격리 출금 감사 장애")).when(audit).record(
            eq(customer.getId()),eq("BTC_WITHDRAWAL_REQUESTED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->withdrawals.submit(customer.getId(),sid,quote.payload().id(),signed));
        reset(audit);assertEquals(0,count("bitcoin_withdrawals"));
        assertEquals(0,count("customer_withdrawal_authorizations"));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM customer_withdrawal_challenges WHERE id=?",Boolean.class,options.challengeId()));
        amount(FUNDS,BigDecimal.ZERO);verifyNoInteractions(engineWithdrawal);
        assertEquals("PENDING_ENGINE",withdrawals.submit(customer.getId(),sid,quote.payload().id(),signed).state());
    }
    @Test void revokedSessionCannotUseAnAlreadyCreatedWithdrawalChallenge()throws Exception {
        var quote=price();var signed=proof(challenge(quote.payload().id()));
        var token=tokens.findBySessionId(sid).orElseThrow();token.setRevoked(true);tokens.saveAndFlush(token);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->withdrawals.submit(customer.getId(),sid,
            quote.payload().id(),signed)).getStatusCode().value());
        assertEquals(0,count("bitcoin_withdrawals"));amount(FUNDS,BigDecimal.ZERO);verifyNoInteractions(engineWithdrawal);
    }
    @Test void uncertainEngineResponsePreservesTheSameLockedFundsAndDurableAttempt()throws Exception {
        when(engineWithdrawal.execute(any())).thenReturn(EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN);
        var quote=price();var signed=proof(challenge(quote.payload().id()));
        var first=withdrawals.submit(customer.getId(),sid,quote.payload().id(),signed);
        assertEquals("PENDING_ENGINE",first.state());
        assertEquals(first,withdrawals.submit(customer.getId(),sid,quote.payload().id(),signed));
        assertEquals(1,count("bitcoin_withdrawal_dispatch_attempts"));
        amount(new BigDecimal("0.00999900"),GROSS);
        org.mockito.Mockito.verify(engineWithdrawal,times(1)).execute(any());
    }
    @Test void httpAcceptsOnlyTheCurrentCustomersSignedQuoteWithoutInputFee()throws Exception {
        var quote=price();String path="/api/customer/withdrawals/BTC/quotes/"+quote.payload().id();
        var options=json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/authorization-options")
            .header("Authorization","Bearer "+customerAccess)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals("0.01000100",options.path("quote").path("payload").path("total").stringValue());
        UUID challengeId=UUID.fromString(options.path("challengeId").stringValue());
        var signed=new CustomerWithdrawalAuthorization.Proof(challengeId,browser.assertion(
            new OperatorMfaService.Options(challengeId,options.path("publicKey")),"http://localhost:3003",true,counter++,null));
        String result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/reservations")
            .header("Authorization","Bearer "+customerAccess).contentType("application/json").content(json.writeValueAsString(signed)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertEquals("PENDING_ENGINE",json.readTree(result).path("state").stringValue());
        assertEquals("0.01000100",json.readTree(result).path("total").stringValue());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"/reservations")
            .header("Authorization","Bearer "+customerAccess)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        amount(new BigDecimal("0.00999900"),GROSS);
    }
    @Test void httpRejectsInjectedAmountDuplicateJsonAndOperatorAccess()throws Exception {
        var quote=price();String path="/api/customer/withdrawals/BTC/quotes/"+quote.payload().id();
        for(String raw:java.util.List.of("{\"challengeId\":\""+UUID.randomUUID()+"\",\"publicKey\":{},\"fee\":\"0\"}",
            "{\"challengeId\":\""+UUID.randomUUID()+"\",\"publicKey\":{},\"publicKey\":{}}"))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/reservations")
                .header("Authorization","Bearer "+customerAccess).contentType("application/json").content(raw))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path+"/authorization-options")
            .header("Authorization","Bearer "+maker.access())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        assertEquals(0,count("bitcoin_withdrawals"));amount(FUNDS,BigDecimal.ZERO);verifyNoInteractions(engineWithdrawal);
    }

    @Test void unsignedDirectFinancialOriginalCannotCommit() {
        var quote=price();
        assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{
            var p=quote.payload();jdbc.update("""
                INSERT INTO bitcoin_withdrawals(id,user_id,session_id,network,asset,amount,fee,total,quote_hash,operation_key)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,p.id(),customer.getId(),sid,p.network(),"BTC",new BigDecimal(p.amount()),new BigDecimal(p.fee()),
                new BigDecimal(p.total()),quote.payloadHash(),"withdrawal-reserve:"+p.id());
        }));
        assertEquals(0,count("bitcoin_withdrawals"));amount(FUNDS,BigDecimal.ZERO);verifyNoInteractions(engineWithdrawal);
    }
    @Test void reservedOriginalSignatureAndDispatchHistoryCannotBeEditedOrDeleted()throws Exception {
        var quote=price();submit(quote.payload().id());
        for(String sql:java.util.List.of("UPDATE bitcoin_withdrawals SET amount=amount",
                "DELETE FROM bitcoin_withdrawals","TRUNCATE bitcoin_withdrawals CASCADE",
                "UPDATE customer_withdrawal_authorizations SET quote_hash=quote_hash",
                "DELETE FROM bitcoin_withdrawal_dispatch_attempts"))
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));
        assertEquals(1,count("bitcoin_withdrawals"));assertEquals(1,count("customer_withdrawal_authorizations"));
        amount(new BigDecimal("0.00999900"),GROSS);
    }
    @Test void forgedEngineValueAndReasonCannotReleaseOrAcknowledgeTheHold()throws Exception {
        var quote=price();submit(quote.payload().id());UUID engine=UUID.randomUUID();
        var wrong=json.readTree(event(quote.payload().id(),engine,"withdrawal_rejected",1));
        var altered=(tools.jackson.databind.node.ObjectNode)wrong;altered.put("amount","0.00999900");
        assertThrows(IllegalStateException.class,()->events.consumeOrderEvent(json.writeValueAsString(altered)));
        altered.put("amount",GROSS.toPlainString());altered.put("reason_code","OTHER_REJECTION");
        assertThrows(IllegalArgumentException.class,()->events.consumeOrderEvent(json.writeValueAsString(altered)));
        assertEquals(0,count("bitcoin_withdrawal_reservation_events"));
        assertEquals(0,count("engine_event_receipts"));
        amount(new BigDecimal("0.00999900"),GROSS);
    }
    @Test void twoDistinctQuotesCannotSpendTheSameAvailableSatoshis()throws Exception {
        var left=price();var right=price();var first=proof(challenge(left.payload().id()));
        var second=proof(challenge(right.payload().id()));
        // 둘 모두 예약하려면 0.020002 BTC가 필요하다. 실제 잠금 순서와 관계없이 한 건 이하만 승인된다.
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=workers.submit(()->{gate.await();try{return withdrawals.submit(customer.getId(),sid,left.payload().id(),first).state();}
                catch(ResponseStatusException rejected){return "DENIED";}});
            var b=workers.submit(()->{gate.await();try{return withdrawals.submit(customer.getId(),sid,right.payload().id(),second).state();}
                catch(ResponseStatusException rejected){return "DENIED";}});
            gate.countDown();var states=java.util.List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,states.stream().filter("PENDING_ENGINE"::equals).count());
            assertEquals(1,states.stream().filter("DENIED"::equals).count());
        }finally{gate.countDown();}
        assertEquals(1,count("bitcoin_withdrawals"));amount(new BigDecimal("0.00999900"),GROSS);
    }

    @Test void eventWithoutItsMandatoryAuditCannotCommitEvenWithReceiptAndLedger()throws Exception {
        var quote=price();submit(quote.payload().id());UUID engine=UUID.randomUUID();
        String raw=event(quote.payload().id(),engine,"withdrawal_reserved",1);
        String id=engine+":1:0";
        assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{
            assertTrue(inbox.claim(id,engine.toString(),raw));
            ledger.record("engine:"+id,"BTC_WITHDRAWAL_RESERVED",java.util.List.of());
            jdbc.update("""
                INSERT INTO bitcoin_withdrawal_reservation_events
                  (withdrawal_id,event_id,engine_id,event_type,event_time,payload_hash,operation_key)
                VALUES(?,?,?,?,?,?,?)
                """,quote.payload().id(),id,engine,"withdrawal_reserved",
                json.readTree(raw).path("timestamp").stringValue(),sha(raw),"engine:"+id);
        }));
        assertEquals(0,count("bitcoin_withdrawal_reservation_events"));
        assertEquals(0,count("engine_event_receipts"));
        amount(new BigDecimal("0.00999900"),GROSS);
    }
    @Test void independentLedgerReconciliationSeesThePendingAndConfirmedReserve()throws Exception {
        var quote=price();submit(quote.payload().id());UUID engine=UUID.randomUUID();
        var source=mock(dustin.cex.domains.reconciliation.EngineAuditClient.class);
        when(source.page(eq(0L),isNull(),isNull())).thenReturn(json.valueToTree(java.util.Map.of(
            "engine_id",engine.toString(),"through",0,"next_after",0,"events",java.util.List.of())));
        var audit=new dustin.cex.domains.reconciliation.ReconciliationService(jdbc,source,submissions,manager);
        assertEquals("INCOMPLETE",audit.run().status());
        String raw=event(quote.payload().id(),engine,"withdrawal_reserved",1);events.consumeOrderEvent(raw);
        when(source.page(eq(0L),isNull(),isNull())).thenReturn(json.valueToTree(java.util.Map.of(
            "engine_id",engine.toString(),"through",1,"next_after",1,"events",java.util.List.of(raw))));
        var completed=audit.run();
        assertEquals("MATCHED",completed.status(),jdbc.queryForList("SELECT * FROM reconciliation_issues WHERE run_id=?",completed.id()).toString());
    }
    @Test void independentLedgerReconciliationSeesTheExactRejectionRefund()throws Exception {
        var quote=price();submit(quote.payload().id());UUID engine=UUID.randomUUID();
        String raw=event(quote.payload().id(),engine,"withdrawal_rejected",1);events.consumeOrderEvent(raw);
        var source=mock(dustin.cex.domains.reconciliation.EngineAuditClient.class);
        when(source.page(eq(0L),isNull(),isNull())).thenReturn(json.valueToTree(java.util.Map.of(
            "engine_id",engine.toString(),"through",1,"next_after",1,"events",java.util.List.of(raw))));
        var audit=new dustin.cex.domains.reconciliation.ReconciliationService(jdbc,source,submissions,manager);
        assertEquals("MATCHED",audit.run().status());amount(FUNDS,BigDecimal.ZERO);
    }

}
