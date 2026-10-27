package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dustin.cex.domains.market.*;
import dustin.cex.domains.security.OperatorMfaService;
import dustin.cex.domains.trade.model.entity.Trade;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 PostgreSQL과 보안키 서명으로 시장 중단·재개가 주문 허용 경계와 함께 움직이는지 검증한다. */
class MarketControlIntegrationTest extends EddTestSupport {
    @Autowired MarketControlService controls;
    @Autowired MarketSurveillanceInputs inputs;
    @Autowired MarketSurveillanceService surveillance;

    @Test void independentApprovalHaltsAndResumesOnlyNewOrders()throws Exception{
        verify(false);allowed();UUID signal=priceSignal();
        var halt=controls.propose(maker.user().getId(),maker.sid(),"BTC","USDT","HALTED",signal,checker.user().getId(),"급격한 체결가 변동을 조사하는 동안 신규 주문 중단",UUID.randomUUID());
        assertEquals("PENDING",halt.status());assertThrows(ResponseStatusException.class,()->controls.options(checker.user().getId(),checker.sid(),halt.payload().id(),halt.payloadHash(),true));
        approve(halt,true);assertEquals("HALTED",controls.state("BTC","USDT").state());
        var blocked=assertThrows(ResponseStatusException.class,()->new TransactionTemplate(manager).executeWithoutResult(s->trading.requireNewOrder(customer.getId(),"BTC","USDT")));
        assertEquals(503,blocked.getStatusCode().value());

        var resume=controls.propose(maker.user().getId(),maker.sid(),"BTC","USDT","OPEN",null,checker.user().getId(),"조사 결과와 운영 점검을 완료해 신규 주문 재개",UUID.randomUUID());
        approve(resume,true);assertEquals("OPEN",controls.state("BTC","USDT").state());allowed();
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM market_state_changes",Long.class));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE market_state_requests SET reason='변조'"));
        assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM market_state_decisions"));
    }

    @Test void rejectedOrUnboundRequestsCannotChangeTheMarket()throws Exception{
        UUID signal=priceSignal();var rejected=controls.propose(maker.user().getId(),maker.sid(),"BTC","USDT","HALTED",signal,checker.user().getId(),"검토 뒤 반려할 중단 제안",UUID.randomUUID());
        approve(rejected,false);assertEquals("OPEN",controls.state("BTC","USDT").state());
        assertThrows(ResponseStatusException.class,()->controls.propose(maker.user().getId(),maker.sid(),"BTC","USDT","HALTED",UUID.randomUUID(),checker.user().getId(),"존재하지 않는 신호",UUID.randomUUID()));
        assertThrows(DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(s->jdbc.update("UPDATE market_trading_states SET state='HALTED',version=version+1 WHERE base_asset='BTC' AND quote_asset='USDT'")));
    }

    @Test void httpRoutesAreHeadlessAndRoleBound()throws Exception{
        UUID signal=priceSignal();String body=json.writeValueAsString(Map.of("baseAsset","BTC","quoteAsset","USDT","desiredState","HALTED","signalId",signal,"checkerId",checker.user().getId(),"reason","운영 화면 요청"));
        String raw=mvc.perform(post("/api/admin/market-controls").header("Authorization","Bearer "+maker.access()).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id=UUID.fromString(json.readTree(raw).path("payload").path("id").asString());
        mvc.perform(post("/api/admin/market-controls/"+id+"/inspect").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk());
        mvc.perform(get("/api/admin/market-controls/BTC/USDT").header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OPEN"));
        mvc.perform(get("/api/admin/market-controls/BTC/USDT").header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
    }

    @Test void missingDecisionAuditRollsBackTheStateChange()throws Exception{
        UUID signal=priceSignal();var halt=controls.propose(maker.user().getId(),maker.sid(),"BTC","USDT","HALTED",signal,checker.user().getId(),"감사 누락 검증",UUID.randomUUID());
        controls.inspect(checker.user().getId(),checker.sid(),halt.payload().id());var proof=signature(halt,true);
        doNothing().when(audit).record(eq(checker.user().getId()),eq("MARKET_STATE_DECIDED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(DataAccessException.class,()->controls.decide(checker.user().getId(),checker.sid(),halt.payload().id(),halt.payloadHash(),true,proof));
        assertEquals("OPEN",controls.state("BTC","USDT").state());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM market_state_changes",Long.class));reset(audit);
    }

    private MarketControlService.Request approve(MarketControlService.Request request,boolean approved)throws Exception{
        controls.inspect(checker.user().getId(),checker.sid(),request.payload().id());var proof=signature(request,approved);
        return controls.decide(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),approved,proof);
    }
    private OperatorMfaService.AuthorizationProof signature(MarketControlService.Request request,boolean approved)throws Exception{
        var option=controls.options(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),approved).authentication();
        return new OperatorMfaService.AuthorizationProof(option.challengeId(),checker.key().assertion(option,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    private UUID priceSignal(){
        Instant at=Instant.parse("2026-10-27T01:00:00Z");trade(at,new BigDecimal("100.000000000"),1);surveillance.processNext().orElseThrow();
        trade(at.plusSeconds(1),new BigDecimal("111.000000000"),2);surveillance.processNext().orElseThrow();
        return jdbc.queryForObject("SELECT id FROM market_surveillance_signals ORDER BY created_at DESC LIMIT 1",UUID.class);
    }
    private void trade(Instant at,BigDecimal price,int sequence){new TransactionTemplate(manager).executeWithoutResult(status->{
        long buy=order("buy",at),sell=order("sell",at);String engine=UUID.randomUUID().toString(),event=engine+":"+sequence+":0";
        jdbc.update("INSERT INTO engine_event_receipts(event_id,engine_id,payload_hash) VALUES(?,?,?)",event,engine,"2".repeat(64));
        long id=jdbc.queryForObject("INSERT INTO trades(engine_event_id,buy_order_id,sell_order_id,buyer_id,seller_id,base_mint,quote_mint,price,amount,created_at) VALUES(?,?,?,?,?,'BTC','USDT',?,1,?) RETURNING id",Long.class,event,buy,sell,maker.user().getId(),checker.user().getId(),price,java.sql.Timestamp.valueOf(LocalDateTime.ofInstant(at,ZoneId.of("Asia/Seoul"))));
        inputs.trade(event,Trade.builder().id(id).engineEventId(event).buyOrderId(buy).sellOrderId(sell).buyerId(maker.user().getId()).sellerId(checker.user().getId()).baseMint("BTC").quoteMint("USDT").price(price).amount(BigDecimal.ONE).createdAt(LocalDateTime.ofInstant(at,ZoneId.of("Asia/Seoul"))).build());
    });}
    private long order(String side,Instant at){return jdbc.queryForObject("INSERT INTO orders(user_id,order_type,order_side,base_mint,quote_mint,price,amount,filled_amount,filled_quote_amount,status,created_at,updated_at) VALUES(?,?,'limit','BTC','USDT',100,1,1,100,'filled',?,?) RETURNING id",Long.class,"buy".equals(side)?maker.user().getId():checker.user().getId(),side,java.sql.Timestamp.from(at.minusSeconds(10)),java.sql.Timestamp.from(at));}
}
