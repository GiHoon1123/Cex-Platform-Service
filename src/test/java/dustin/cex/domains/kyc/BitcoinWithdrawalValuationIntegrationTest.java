package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.valuation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 신청 당시 가격·지급액 참고평가·서명·원장·감사를 실제 PG의 한 커밋에 결합한다. */
class BitcoinWithdrawalValuationIntegrationTest extends BitcoinWithdrawalTestSupport {
    @MockitoSpyBean BitcoinWithdrawalValuations valuationWrites;
    @Autowired BitcoinWithdrawalCancellations cancellations;
    Map<String,Object> valuation(UUID id){return jdbc.queryForMap("SELECT * FROM bitcoin_withdrawal_valuations WHERE withdrawal_id=?",id);}
    void noReservation(){assertEquals(0,count("bitcoin_withdrawals"));assertEquals(0,count("bitcoin_withdrawal_valuations"));assertEquals(0,count("customer_withdrawal_authorizations"));amount(FUNDS,BigDecimal.ZERO);verifyNoInteractions(engineWithdrawal);}
    void failPrice(){when(priceClient.read()).thenThrow(new IllegalStateException("격리 가격 장애"));assertEquals("FAILED",referencePrices.refresh().status());}

    @Test void exactReferenceUsesNetPaymentAndPreservesTheFullProduct()throws Exception {
        UUID id=price().payload().id();submit(id);var row=valuation(id);
        assertEquals(0,new BigDecimal("0.01000000").compareTo((BigDecimal)row.get("amount")));
        assertEquals(0,new BigDecimal("1000000.0012345678000000").compareTo((BigDecimal)row.get("krw_value")));
        assertNotEquals(0,GROSS.multiply((BigDecimal)row.get("price")).compareTo((BigDecimal)row.get("krw_value")));
        assertEquals(jdbc.queryForObject("SELECT created_at FROM bitcoin_withdrawals WHERE id=?",java.sql.Timestamp.class,id),row.get("reference_at"));
        assertEquals(jdbc.queryForObject("SELECT created_txid FROM bitcoin_withdrawals WHERE id=?",Long.class,id),row.get("created_txid"));
        assertEquals(BitcoinWithdrawalValuations.POLICY,row.get("policy"));
    }
    @Test void duplicateRequestDoesNotRevalueAnOriginalAfterThePriceHeadFails()throws Exception {
        UUID id=price().payload().id();var signed=proof(challenge(id));withdrawals.submit(customer.getId(),sid,id,signed);
        var original=valuation(id);failPrice();
        assertEquals("PENDING_ENGINE",withdrawals.submit(customer.getId(),sid,id,signed).state());
        assertEquals(original,valuation(id));assertEquals(1,count("bitcoin_withdrawal_valuations"));
        amount(new BigDecimal("0.00999900"),GROSS);
    }
    @Test void failedCurrentPriceCannotUseAHistoricalSuccessOrConsumeTheSignature()throws Exception {
        UUID id=price().payload().id();var options=challenge(id);var signed=proof(options);failPrice();
        assertThrows(ResponseStatusException.class,()->withdrawals.submit(customer.getId(),sid,id,signed));
        noReservation();assertFalse(jdbc.queryForObject("SELECT consumed FROM customer_withdrawal_challenges WHERE id=?",Boolean.class,options.challengeId()));
    }
    @Test void newUnfinishedPriceHeadCannotFallBackToItsPreviousSuccess()throws Exception {
        UUID id=price().payload().id();var signed=proof(challenge(id));
        var started=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        when(priceClient.read()).thenAnswer(call->{started.countDown();assertTrue(finish.await(10,java.util.concurrent.TimeUnit.SECONDS));return referenceQuote(new BigDecimal("100000000"));});
        try(var workers=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var refresh=workers.submit(()->referencePrices.refresh());
            try {assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(ResponseStatusException.class,()->withdrawals.submit(customer.getId(),sid,id,signed));noReservation();
            }finally{finish.countDown();}
            assertEquals("SUCCESS",refresh.get(10,java.util.concurrent.TimeUnit.SECONDS).status());
        }
    }
    @Test void staleProviderTradeCannotCreateANewFinancialReservation()throws Exception {
        var normal=referenceQuote(new BigDecimal("100000000"));
        when(priceClient.read()).thenReturn(new BitcoinKrwPriceClient.Quote(normal.price(),Instant.now().minusSeconds(121),normal.providerAt(),normal.readStartedAt(),normal.readFinishedAt(),normal.payload(),normal.payloadHash()));
        assertEquals("FAILED",referencePrices.refresh().status());UUID id=price().payload().id();
        assertThrows(ResponseStatusException.class,()->submit(id));noReservation();
    }
    @Test void valuationAuditFailureRollsBackTheOriginalSignatureAndHold()throws Exception {
        UUID id=price().payload().id();var options=challenge(id);var signed=proof(options);
        doThrow(new IllegalStateException("격리 평가 감사 장애")).when(audit).record(eq(customer.getId()),eq("BTC_WITHDRAWAL_VALUED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->withdrawals.submit(customer.getId(),sid,id,signed));reset(audit);noReservation();
        assertFalse(jdbc.queryForObject("SELECT consumed FROM customer_withdrawal_challenges WHERE id=?",Boolean.class,options.challengeId()));
    }
    @Test void exactFinancialHoldWithoutItsValuationCannotCommit()throws Exception {
        UUID id=price().payload().id();
        // 시험의 호출 생략도 제품 프록시의 금융 TX 경계를 지킨다. 실제 예약 시 커밋 제약이 누락을 검사한다.
        new TransactionTemplate(manager).executeWithoutResult(t -> doNothing().when(valuationWrites).record(any(),anyLong(),any()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->submit(id));noReservation();
    }
    @Test void valuationWithoutItsMandatoryAuditCannotCommit()throws Exception {
        UUID id=price().payload().id();doNothing().when(audit).record(eq(customer.getId()),eq("BTC_WITHDRAWAL_VALUED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(org.springframework.dao.DataAccessException.class,()->submit(id));reset(audit);noReservation();
    }
    @Test void storedPriceAndAmountCannotUseAnUnboundSourceHash()throws Exception {
        UUID id=price().payload().id();
        new TransactionTemplate(manager).executeWithoutResult(t -> doAnswer(call->{BitcoinKrwPriceService.Snapshot s=call.getArgument(2);
            var wrong=new BitcoinKrwPriceService.Snapshot(s.attemptId(),s.price(),s.tradeAt(),s.providerAt(),s.readStartedAt(),s.recordedAt(),"f".repeat(64));
            new BitcoinWithdrawalValuations(referencePrices,jdbc,audit).record(call.getArgument(0),call.getArgument(1),wrong);return null;
        }).when(valuationWrites).record(any(),anyLong(),any()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->submit(id));noReservation();
    }
    @Test void valuationCannotBeRewrittenDeletedOrTruncated()throws Exception {
        UUID id=price().payload().id();submit(id);var original=valuation(id);
        for(String sql:List.of("UPDATE bitcoin_withdrawal_valuations SET price=1","DELETE FROM bitcoin_withdrawal_valuations","TRUNCATE bitcoin_withdrawal_valuations CASCADE"))
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));
        assertEquals(original,valuation(id));amount(new BigDecimal("0.00999900"),GROSS);
    }
    @Test void laterReferencePriceDoesNotReplaceTheHistoricalRequestValuation()throws Exception {
        UUID id=price().payload().id();submit(id);var original=valuation(id);
        when(priceClient.read()).thenAnswer(call->referenceQuote(new BigDecimal("90000000.87654321")));assertEquals("SUCCESS",referencePrices.refresh().status());
        assertEquals(original,valuation(id));assertEquals("PENDING_ENGINE",withdrawals.status(customer.getId(),sid,id).state());
    }
    @Test void independentReconciliationUsesTheHistoricalSourceAfterAHeadChange()throws Exception {
        UUID id=price().payload().id();submit(id);UUID engine=UUID.randomUUID();String raw=event(id,engine,"withdrawal_reserved",1);events.consumeOrderEvent(raw);
        when(priceClient.read()).thenAnswer(call->referenceQuote(new BigDecimal("90000000")));assertEquals("SUCCESS",referencePrices.refresh().status());
        var source=mock(dustin.cex.domains.reconciliation.EngineAuditClient.class);
        when(source.page(eq(0L),isNull(),isNull())).thenReturn(json.valueToTree(Map.of("engine_id",engine.toString(),"through",1,"next_after",1,"events",List.of(raw))));
        var reconciliation=new dustin.cex.domains.reconciliation.ReconciliationService(jdbc,source,submissions,manager);
        var report=reconciliation.run();assertEquals("MATCHED",report.status(),jdbc.queryForList("SELECT * FROM reconciliation_issues WHERE run_id=?",report.id()).toString());
    }
    @Test void cancellationPreservesTheValuationWhileReturningTheOriginalGross()throws Exception {
        UUID id=price().payload().id();submit(id);var original=valuation(id);UUID engine=UUID.randomUUID();events.consumeOrderEvent(event(id,engine,"withdrawal_reserved",1));
        when(engineWithdrawal.execute(any())).thenReturn(dustin.cex.shared.http.EngineWithdrawalClient.Outcome.DURABLY_RELEASED);
        cancellations.cancel(customer.getId(),sid,id);events.consumeOrderEvent(event(id,engine,"withdrawal_released",2));
        assertEquals(original,valuation(id));amount(FUNDS,BigDecimal.ZERO);
    }
    @Test void pastRequestCannotAcquireANewValuationInALaterTransaction()throws Exception {
        UUID id=price().payload().id();submit(id);var original=valuation(id);
        assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->
            valuationWrites.record(id,customer.getId(),valuationWrites.capture())));
        assertEquals(original,valuation(id));
    }
    @Test void futureProviderTimestampCannotBecomeTheEarlierRequestReference()throws Exception {
        var normal=referenceQuote(new BigDecimal("100000000"));
        var provider=Instant.ofEpochMilli(Instant.now().plusSeconds(5).toEpochMilli());
        // 미래 시각 자체의 판단을 검증한다. 원문과 메타데이터 불일치로 더 앞에서 거절되는 대역을 만들지 않는다.
        String raw=json.writeValueAsString(List.of(Map.of("market","KRW-BTC","trade_price",normal.price(),"trade_timestamp",normal.tradeAt().toEpochMilli(),"timestamp",provider.toEpochMilli())));
        when(priceClient.read()).thenReturn(new BitcoinKrwPriceClient.Quote(normal.price(),normal.tradeAt(),provider,normal.readStartedAt(),normal.readFinishedAt(),raw,sha(raw)));
        assertEquals("SUCCESS",referencePrices.refresh().status());UUID id=price().payload().id();
        assertEquals(503,assertThrows(ResponseStatusException.class,()->submit(id)).getStatusCode().value());noReservation();
    }
}
