package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dustin.cex.domains.market.*;
import dustin.cex.domains.order.model.entity.Order;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.domains.trade.model.entity.Trade;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 PostgreSQL에서 확정 결과 입력, 순차 평가, 신호 조회, 근거 불변성을 한 흐름으로 검증한다. */
class MarketSurveillanceIntegrationTest extends EddTestSupport {
    @Autowired MarketSurveillanceInputs inputs;
    @Autowired MarketSurveillanceService surveillance;
    @Autowired MarketSurveillanceCases cases;
    @Autowired OrderRepository orders;

    @Test void confirmedEventsCreateReviewSignalsWithoutAutomaticallyRestrictingTheCustomer()throws Exception{
        Instant start=Instant.parse("2026-10-27T00:00:00Z");
        for(int index=0;index<5;index++)cancel(start.plusSeconds(index*30L),index);
        for(int index=0;index<4;index++)assertEquals("NO_SIGNAL",surveillance.processNext().orElseThrow().outcome());
        var repeated=surveillance.processNext().orElseThrow();
        assertEquals("REVIEW_REQUIRED",repeated.outcome());assertEquals("RAPID_UNFILLED_CANCELLATION",repeated.reason());
        assertFalse(users.findById(customer.getId()).orElseThrow().isTradingRestricted());

        trade(start.plusSeconds(400),new BigDecimal("100.000000000"),10);
        assertEquals("NO_SIGNAL",surveillance.processNext().orElseThrow().outcome());
        trade(start.plusSeconds(401),new BigDecimal("111.000000000"),11);
        var price=surveillance.processNext().orElseThrow();assertEquals("ABRUPT_EXECUTION_PRICE",price.reason());
        assertTrue(surveillance.processNext().isEmpty());

        var ids=jdbc.queryForList("SELECT id FROM market_surveillance_signals ORDER BY created_at",UUID.class);
        assertEquals(2,ids.size());
        var investigations=cases.page(0);assertEquals(2,investigations.size());var selected=investigations.getFirst();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->cases.observe(checker.user().getId(),checker.sid(),selected.id(),"배정 전 의견"));
        assertEquals("UNDER_REVIEW",cases.claim(maker.user().getId(),maker.sid(),selected.id()).status());
        assertEquals("OBSERVATION",cases.observe(maker.user().getId(),maker.sid(),selected.id(),"두 체결 가격과 취소 묶음의 원본을 확인했습니다").type());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->cases.close(checker.user().getId(),checker.sid(),selected.id(),"ESCALATED","다른 담당자의 결론"));
        assertEquals("ESCALATED",cases.close(maker.user().getId(),maker.sid(),selected.id(),"ESCALATED","시장 통제 검토로 회부합니다").status());
        assertEquals(4,cases.history(selected.id(),0).size());
        var remaining=investigations.get(1);doNothing().when(audit).record(eq(maker.user().getId()),eq("MARKET_CASE_CLAIMED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(DataAccessException.class,()->cases.claim(maker.user().getId(),maker.sid(),remaining.id()));reset(audit);assertEquals("OPEN",cases.detail(remaining.id()).status());
        mvc.perform(get("/api/admin/market-surveillance/signals").header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/admin/market-surveillance/signals/"+ids.getFirst()).header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andExpect(jsonPath("$.source_hash").isString());
        mvc.perform(get("/api/admin/market-surveillance/signals").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());

        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE market_activity_events SET base_asset='ETH'"));
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM market_surveillance_assessments"));
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM market_surveillance_case_events"));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE market_surveillance_cases SET status='OPEN'"));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE market_surveillance_state SET observed_event=0 WHERE singleton=true"));
    }

    private void cancel(Instant at,int sequence){
        new TransactionTemplate(manager).executeWithoutResult(status->{
            long order=jdbc.queryForObject("""
                INSERT INTO orders(user_id,order_type,order_side,base_mint,quote_mint,price,amount,filled_amount,filled_quote_amount,status,created_at,updated_at)
                VALUES(?,'buy','limit','BTC','USDT',100,1,0,0,'cancelled',?,?) RETURNING id
                """,Long.class,customer.getId(),java.sql.Timestamp.from(at.minusSeconds(20)),java.sql.Timestamp.from(at));
            String event=UUID.randomUUID()+":"+(sequence+1)+":0";
            jdbc.update("INSERT INTO engine_event_receipts(event_id,engine_id,payload_hash) VALUES(?,?,?)",event,event.substring(0,36),"0".repeat(64));
            Order source=orders.findById(order).orElseThrow();inputs.cancelled(event,at,source);
        });
    }

    private void trade(Instant at,BigDecimal price,int sequence){
        new TransactionTemplate(manager).executeWithoutResult(status->{
            long buy=order("buy",at),sell=order("sell",at);String engine=UUID.randomUUID().toString(),event=engine+":"+sequence+":0";
            jdbc.update("INSERT INTO engine_event_receipts(event_id,engine_id,payload_hash) VALUES(?,?,?)",event,engine,"1".repeat(64));
            long id=jdbc.queryForObject("""
                INSERT INTO trades(engine_event_id,buy_order_id,sell_order_id,buyer_id,seller_id,base_mint,quote_mint,price,amount,created_at)
                VALUES(?,?,?,?,?,'BTC','USDT',?,1,?) RETURNING id
                """,Long.class,event,buy,sell,maker.user().getId(),checker.user().getId(),price,java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(at,ZoneId.of("Asia/Seoul"))));
            inputs.trade(event,Trade.builder().id(id).engineEventId(event).buyOrderId(buy).sellOrderId(sell).buyerId(maker.user().getId()).sellerId(checker.user().getId())
                .baseMint("BTC").quoteMint("USDT").price(price).amount(BigDecimal.ONE).createdAt(LocalDateTime.ofInstant(at,ZoneId.of("Asia/Seoul"))).build());
        });
    }

    private long order(String side,Instant at){
        return jdbc.queryForObject("""
            INSERT INTO orders(user_id,order_type,order_side,base_mint,quote_mint,price,amount,filled_amount,filled_quote_amount,status,created_at,updated_at)
            VALUES(?,?,'limit','BTC','USDT',100,1,1,100,'filled',?,?) RETURNING id
            """,Long.class,"buy".equals(side)?maker.user().getId():checker.user().getId(),side,java.sql.Timestamp.from(at.minusSeconds(10)),java.sql.Timestamp.from(at));
    }
}
