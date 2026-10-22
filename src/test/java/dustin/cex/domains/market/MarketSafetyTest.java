package dustin.cex.domains.market;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import tools.jackson.databind.ObjectMapper;
import dustin.cex.shared.kafka.EngineEventInbox;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

/** 실제 서버를 시작하지 않고 공개 계약의 순번·개인정보·호가 검증을 확인한다. */
class MarketSafetyTest {
    final String engine=UUID.randomUUID().toString();
    MarketSnapshot event(List<List<String>> bids,List<List<String>> asks) {
        return new MarketSnapshot("market_snapshot",engine+":2:market",engine,"2","1","BTC","USDT",Instant.now(),bids,asks,List.of());
    }
    @Test void acceptsSortedSnapshotAndSerializesNoPrivateAccountFields() throws Exception {
        var event=event(List.of(List.of("100","0.001")),List.of(List.of("101","0.002")));
        MarketService.validate(event);
        String payload=new ObjectMapper().writeValueAsString(event);
        assertFalse(payload.contains("user_id"));assertFalse(payload.contains("balance"));assertTrue(payload.contains("\"sequence\":\"2\""));
    }
    @Test void rejectsUnsortedNegativeAndOversizedDepth() {
        assertThrows(IllegalArgumentException.class,()->MarketService.validate(event(List.of(List.of("100","1"),List.of("101","1")),List.of())));
        assertThrows(IllegalArgumentException.class,()->MarketService.validate(event(List.of(),List.of(List.of("100","-1")))));
        assertThrows(IllegalArgumentException.class,()->MarketService.validate(event(Collections.nCopies(21,List.of("100","1")),List.of())));
    }
    @Test void rejectsForgedSequenceAndDuplicateTradeIdentity() {
        assertThrows(IllegalArgumentException.class,()->MarketService.validate(new MarketSnapshot("market_snapshot",engine+":2:market",engine,"2","2","BTC","USDT",Instant.now(),List.of(),List.of(),List.of())));
        var print=new MarketSnapshot.Print(engine+":2:0","100","1","buy");
        assertThrows(IllegalArgumentException.class,()->MarketService.validate(new MarketSnapshot("market_snapshot",engine+":2:market",engine,"2","1","BTC","USDT",Instant.now(),List.of(),List.of(),List.of(print,print))));
    }
    @Test void duplicateReceiptDoesNotRewriteBookOrTrades() throws Exception {
        var mapper=new ObjectMapper();var inbox=mock(EngineEventInbox.class);
        var states=mock(MarketStateRepository.class);var prints=mock(MarketPrintRepository.class);
        var event=event(List.of(),List.of());String payload=mapper.writeValueAsString(event);
        when(inbox.claim(event.eventId(),engine,payload)).thenReturn(false);
        new MarketService(mapper,inbox,states,prints).consume(payload);
        verifyNoInteractions(states,prints);
    }

    @Test void accountAmountsAndTradeIdsSerializeWithoutBrowserNumberLoss() throws Exception {
        var mapper=new ObjectMapper();
        var balance=dustin.cex.domains.balance.model.dto.BalanceResponse.builder().available(new java.math.BigDecimal("999999999999999999999.000000001")).locked(java.math.BigDecimal.ZERO).build();
        var body=mapper.valueToTree(balance);assertTrue(body.get("available").isTextual());assertEquals("999999999999999999999.000000001",body.get("available").asText());
        var trade=dustin.cex.domains.trade.model.dto.TradeResponse.builder().id(9007199254740993L).buyOrderId(9007199254740994L).price(new java.math.BigDecimal("60000.000000001")).build();
        body=mapper.valueToTree(trade);assertTrue(body.get("id").isTextual());assertTrue(body.get("buyOrderId").isTextual());assertEquals("60000.000000001",body.get("price").asText());
    }

    @Test void aggregateBookQuantityCanExceedSingleAccountColumnLimit() {
        MarketService.validate(event(List.of(List.of("1","1800000000000000000000")),List.of()));
    }
}
