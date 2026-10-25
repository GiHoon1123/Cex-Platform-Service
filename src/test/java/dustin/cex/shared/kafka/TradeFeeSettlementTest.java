package dustin.cex.shared.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.balance.model.entity.UserBalance;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.order.model.OrderCommand;
import dustin.cex.domains.order.model.entity.*;
import dustin.cex.domains.order.repository.*;
import dustin.cex.domains.trade.repository.TradeRepository;
import dustin.cex.domains.settlement.trade.repository.TradeFeeRepository;

/** 실제 저장소와 서비스 트랜잭션으로 연속 체결·후속 예약 보존·잘못된 결과의 전체 롤백을 검증한다. */
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"})
@ContextConfiguration(classes=TradeFeeSettlementTest.Persistence.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class TradeFeeSettlementTest {
    @Configuration
    @EntityScan(basePackages="dustin.cex.domains")
    @EnableJpaRepositories(basePackages="dustin.cex.domains")
    @Import(KafkaOrderEventConsumer.class)
    static class Persistence {}
    @MockitoBean dustin.cex.domains.ledger.LedgerService ledger;
    @MockitoBean EngineEventInbox inbox;
    // 체결/수수료 전용 H2 시험은 입금 서비스를 사용하지 않는다. 입금 금융 경로는 별도 실제 PG 시험이 검증한다.
    @MockitoBean dustin.cex.domains.bitcoin.BitcoinCreditedEvents deposits;
    @Autowired KafkaOrderEventConsumer consumer;
    @Autowired UserRepository users; @Autowired OrderRepository orders;
    @Autowired OrderDispatchRepository dispatches;
    @Autowired OrderSubmissionRepository submissions; @Autowired UserBalanceRepository balances;
    @Autowired dustin.cex.domains.position.repository.UserPositionRepository positions;
    @Autowired TradeRepository trades; @Autowired TradeFeeRepository fees;
    final String eventEngine=UUID.randomUUID().toString();
    long buyer, seller, buy, sell;
    BigDecimal n(String value) { return new BigDecimal(value); }
    void balance(long user, String asset, String available, String locked) {
        var row=balances.findByUserIdAndMintAddress(user,asset).orElseGet(()->UserBalance.builder().userId(user).mintAddress(asset).build());
        row.setAvailable(n(available)); row.setLocked(n(locked)); balances.saveAndFlush(row);
    }
    long order(long user, String side, String price, String rate) {
        var order = orders.saveAndFlush(Order.builder().userId(user).orderType(side).orderSide("limit")
                .baseMint("BTC").quoteMint("USDT").price(n(price)).amount(n("2"))
                .filledAmount(BigDecimal.ZERO).filledQuoteAmount(BigDecimal.ZERO).status("pending").build());
        var command = new OrderCommand(side,"limit","BTC","USDT",n(price),n("2"),null);
        submissions.saveAndFlush(new OrderSubmission(user,UUID.randomUUID(),order.getId(),command,n(rate)));
        dispatches.saveAndFlush(new OrderDispatch(order));
        return order.getId();
    }
    @BeforeEach void seed() {
        // 각 테스트는 서로 다른 사용자를 사용한다. 외부 네트워크와 실제 소비자는 생성하지 않는다.
        buyer = users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@local.test").passwordHash("격리 검증").build()).getId();
        seller = users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@local.test").passwordHash("격리 검증").build()).getId();
        buy=order(buyer,"buy","100","0.001"); sell=order(seller,"sell","90","0.002");
        balance(buyer,"USDT","750","250"); balance(seller,"BTC","8","2");
        when(inbox.claim(anyString(),anyString(),anyString())).thenReturn(true);
    }
    String event(int sequence, String buyerFee) {
        return """
            {"event_type":"trade_executed","engine_id":"engine","event_id":"engine:%d:0",
             "buy_order_id":%d,"sell_order_id":%d,"buyer_id":%d,"seller_id":%d,
             "base_mint":"BTC","quote_mint":"USDT","price":"90","amount":"0.5",
             "buyer_fee_rate":"0.001","seller_fee_rate":"0.002","buyer_fee":"%s","seller_fee":"0.09",
             "timestamp":"2026-10-22T00:00:00Z"}
            """.formatted(sequence,buy,sell,buyer,seller,buyerFee).replace("engine:",eventEngine+":").replace("\"engine_id\":\"engine\"","\"engine_id\":\""+eventEngine+"\"");
    }
    void assertBalance(long user, String asset, String available, String locked) {
        var result = balances.findByUserIdAndMintAddress(user,asset).orElseThrow();
        assertEquals(0,result.getAvailable().compareTo(n(available)));
        assertEquals(0,result.getLocked().compareTo(n(locked)));
    }
    @Test void consecutivePartialFillsKeepFeesAndFutureReservation() {
        consumer.consumeOrderEvent(event(1,"0.0005"));
        consumer.consumeOrderEvent(event(2,"0.0005"));
        assertBalance(buyer,"BTC","0.999","0");
        assertBalance(buyer,"USDT","760","150");
        assertBalance(seller,"USDT","89.82","0");
        assertBalance(seller,"BTC","8","1");
        var buyerFees=fees.findByUserIdAndCreatedAtBetween(buyer,java.time.LocalDateTime.of(2026,10,22,0,0),java.time.LocalDateTime.of(2026,10,23,0,0));
        assertEquals(2,buyerFees.size()); assertTrue(buyerFees.stream().allMatch(f -> f.getFeeMint().equals("BTC")));
        assertEquals("partial",orders.findById(buy).orElseThrow().getStatus());
        var position=positions.findByUserIdAndBaseMintAndQuoteMint(buyer,"BTC","USDT").orElseThrow();
        assertEquals(0,position.getPositionAmount().compareTo(n("0.999")));
        assertEquals(0,position.getAvgEntryPrice().compareTo(n("90.090090090")));
    }
    @Test void alteredFeeOrFrozenRateRollsBackAllFinancialRows() {
        long before=trades.count();
        assertThrows(IllegalArgumentException.class, () -> consumer.consumeOrderEvent(event(1,"0.005")));
        assertThrows(IllegalArgumentException.class, () -> consumer.consumeOrderEvent(event(1,"0.0005").replace("\"buyer_fee_rate\":\"0.001\"","\"buyer_fee_rate\":\"0.002\"")));
        assertEquals(before,trades.count()); assertBalance(buyer,"USDT","750","250");
        assertEquals(0,orders.findById(buy).orElseThrow().getFilledAmount().signum());
    }
    @Test void lateBalanceFailureRollsBackTradeOrdersAndEarlierAccountChanges() {
        var source=balances.findByUserIdAndMintAddress(seller,"BTC").orElseThrow(); source.setLocked(BigDecimal.ZERO); balances.saveAndFlush(source);
        long before=trades.count();
        assertThrows(IllegalArgumentException.class, () -> consumer.consumeOrderEvent(event(1,"0.0005")));
        assertEquals(before,trades.count()); assertBalance(buyer,"USDT","750","250");
        assertTrue(balances.findByUserIdAndMintAddress(buyer,"BTC").isEmpty());
        assertEquals(0,orders.findById(buy).orElseThrow().getFilledAmount().signum());
    }
    long marketOrder(long user, String side, String budgetOrQuantity) {
        boolean buy = side.equals("buy");
        var row = orders.saveAndFlush(Order.builder().userId(user).orderType(side).orderSide("market")
                .baseMint("BTC").quoteMint("USDT").amount(buy ? BigDecimal.ZERO : n(budgetOrQuantity))
                .filledAmount(BigDecimal.ZERO).filledQuoteAmount(BigDecimal.ZERO).status("pending").build());
        var command = new OrderCommand(side,"market","BTC","USDT",null,buy ? null : n(budgetOrQuantity),buy ? n(budgetOrQuantity) : null);
        submissions.saveAndFlush(new OrderSubmission(user,UUID.randomUUID(),row.getId(),command,n(buy ? "0.001" : "0.002")));
        dispatches.saveAndFlush(new OrderDispatch(row));
        return row.getId();
    }
    String completed(long id, long user, String side, String reserved, String quantity, String spent, String refund, int count, String status) {
        return """
            {"event_type":"market_order_completed","event_id":"engine:90:2","engine_id":"engine",
             "order_id":%d,"user_id":%d,"order_type":"%s","base_mint":"BTC","quote_mint":"USDT",
             "reserved_amount":"%s","filled_amount":"%s","filled_quote_amount":"%s",
             "refund_amount":"%s","trade_count":%d,"status":"%s"}
            """.formatted(id,user,side,reserved,quantity,spent,refund,count,status);
    }
    @Test void marketBuyUsesBudgetAndReleasesOnlyItsUnusedReservation() {
        buy=marketOrder(buyer,"buy","200");
        consumer.consumeOrderEvent(event(1,"0.0005"));
        consumer.consumeOrderEvent(event(2,"0.0005"));
        assertEquals("partial",orders.findById(buy).orElseThrow().getStatus());
        assertBalance(buyer,"USDT","750","160");
        consumer.consumeOrderEvent(completed(buy,buyer,"buy","200","1","90","110",2,"expired"));
        assertBalance(buyer,"USDT","860","50");
        assertEquals("expired",orders.findById(buy).orElseThrow().getStatus());
        assertEquals(0,orders.findById(buy).orElseThrow().getAmount().signum());
        assertBalance(buyer,"BTC","0.999","0");
        // 다른 식별자로 종료를 반복해도 닫힌 주문에서 반환을 반복하지 않는다.
        assertThrows(IllegalStateException.class,()->consumer.consumeOrderEvent(completed(buy,buyer,"buy","200","1","90","110",2,"expired")));
        assertBalance(buyer,"USDT","860","50");
    }
    @Test void zeroFillMarketBuyAndSellReturnWholeReservation() {
        buy=marketOrder(buyer,"buy","200");
        sell=marketOrder(seller,"sell","2");
        consumer.consumeOrderEvent(completed(buy,buyer,"buy","200","0","0","200",0,"expired"));
        consumer.consumeOrderEvent(completed(sell,seller,"sell","2","0","0","2",0,"expired"));
        assertBalance(buyer,"USDT","950","50"); assertBalance(seller,"BTC","10","0");
        assertEquals("expired",orders.findById(buy).orElseThrow().getStatus());
        assertEquals("expired",orders.findById(sell).orElseThrow().getStatus());
    }
    @Test void marketSellPartialCompletionPreservesOtherReservation() {
        sell=marketOrder(seller,"sell","2"); balance(seller,"BTC","7.5","2.5");
        consumer.consumeOrderEvent(event(1,"0.0005")); consumer.consumeOrderEvent(event(2,"0.0005"));
        assertEquals("partial",orders.findById(sell).orElseThrow().getStatus());
        consumer.consumeOrderEvent(completed(sell,seller,"sell","2","1","90","1",2,"expired"));
        assertBalance(seller,"BTC","8.5","0.5"); assertBalance(seller,"USDT","89.82","0");
        assertEquals("expired",orders.findById(sell).orElseThrow().getStatus());
    }
    @Test void fullySpentMarketBuyWaitsForCompletionAndDoesNotRefund() {
        buy=marketOrder(buyer,"buy","90"); balance(buyer,"USDT","860","140");
        consumer.consumeOrderEvent(event(1,"0.0005")); consumer.consumeOrderEvent(event(2,"0.0005"));
        assertEquals("partial",orders.findById(buy).orElseThrow().getStatus());
        consumer.consumeOrderEvent(completed(buy,buyer,"buy","90","1","90","0",2,"filled"));
        assertBalance(buyer,"USDT","860","50"); assertEquals("filled",orders.findById(buy).orElseThrow().getStatus());
    }
    @Test void wrongCompletionTotalsCountsAndStatusDoNotReleaseReservation() {
        buy=marketOrder(buyer,"buy","200");
        consumer.consumeOrderEvent(event(1,"0.0005"));
        for (String invalid : java.util.List.of(
                completed(buy,buyer,"buy","200","0.5","45","0",1,"filled"),
                completed(buy,buyer,"buy","200","0.5","45","155",2,"expired"),
                completed(buy,buyer,"buy","200","1","45","155",1,"expired"),
                completed(buy,buyer,"buy","201","0.5","45","155",1,"expired"))) {
            assertThrows(IllegalArgumentException.class,()->consumer.consumeOrderEvent(invalid));
        }
        assertBalance(buyer,"USDT","750","205"); assertEquals("partial",orders.findById(buy).orElseThrow().getStatus());
    }
    @Test void overBudgetTradeAndFailedRefundRollBackAndAllowCorrectCompletion() {
        buy=marketOrder(buyer,"buy","40");
        long before=trades.count();
        assertThrows(IllegalStateException.class,()->consumer.consumeOrderEvent(event(1,"0.0005")));
        assertEquals(before,trades.count()); assertEquals(0,orders.findById(buy).orElseThrow().getFilledAmount().signum());
        buy=marketOrder(buyer,"buy","200");
        balance(buyer,"USDT","750","100");
        String completion=completed(buy,buyer,"buy","200","0","0","200",0,"expired");
        assertThrows(IllegalArgumentException.class,()->consumer.consumeOrderEvent(completion));
        assertEquals("pending",orders.findById(buy).orElseThrow().getStatus()); assertBalance(buyer,"USDT","750","100");
        balance(buyer,"USDT","750","250"); consumer.consumeOrderEvent(completion);
        assertBalance(buyer,"USDT","950","50");
    }

    @Test void marketBudgetDustSurvivesDatabaseCommitWithoutRounding() {
        buy=marketOrder(buyer,"buy","1"); sell=order(seller,"sell","3","0.002");
        balance(buyer,"USDT","750","51");
        String trade=event(1,"0.000333333").replace("\"price\":\"90\"","\"price\":\"3\"")
                .replace("\"amount\":\"0.5\"","\"amount\":\"0.333333333\"")
                .replace("\"seller_fee\":\"0.09\"","\"seller_fee\":\"0.001999999\"");
        consumer.consumeOrderEvent(trade);
        // 종료는 별도 트랜잭션에서 저장값을 다시 읽는다. 소수 8자리 컬럼이면 여기서 1로 반올림돼 실패한다.
        assertEquals(0,orders.findById(buy).orElseThrow().getFilledQuoteAmount().compareTo(n("0.999999999")));
        consumer.consumeOrderEvent(completed(buy,buyer,"buy","1","0.333333333","0.999999999","0.000000001",1,"expired"));
        assertBalance(buyer,"USDT","750.000000001","50");
        assertEquals("expired",orders.findById(buy).orElseThrow().getStatus());
    }

    String text(BigDecimal value) { return value==null ? null : value.toPlainString(); }
    String rejected(long id) {
        var original=submissions.findByOrderId(id).orElseThrow(); var c=original.command();
        var event=new tools.jackson.databind.ObjectMapper().createObjectNode();
        event.put("event_type","order_rejected"); event.put("engine_id","engine"); event.put("event_id","engine:91:0");
        event.put("order_id",id);event.put("user_id",original.getUserId());event.put("order_type",c.orderType());event.put("order_side",c.orderSide());
        event.put("base_mint",c.baseMint());event.put("quote_mint",c.quoteMint());event.put("reason_code","ENGINE_EXECUTION_REJECTED");
        event.put("price",text(c.price()));event.put("amount",text(c.amount()));event.put("quote_amount",text(c.quoteAmount()));event.put("fee_rate",text(original.getFeeRate()));
        return event.toString();
    }
    @Test void durableRejectionReturnsOnlyOriginalReservationAndConfirmsDelivery() {
        consumer.consumeOrderEvent(rejected(buy));
        assertBalance(buyer,"USDT","950","50");
        assertEquals("rejected",orders.findById(buy).orElseThrow().getStatus());
        assertNotNull(dispatches.findById(buy).orElseThrow().getConfirmedAt());
        assertThrows(IllegalStateException.class,()->consumer.consumeOrderEvent(rejected(buy)));
        assertBalance(buyer,"USDT","950","50");
    }
    @Test void marketBuyRejectionUsesOriginalBudgetWithExplicitNullQuantity() {
        buy=marketOrder(buyer,"buy","123.250000001"); balance(buyer,"USDT","750","173.250000001");
        consumer.consumeOrderEvent(rejected(buy)); assertBalance(buyer,"USDT","873.250000001","50");
        assertEquals("rejected",orders.findById(buy).orElseThrow().getStatus());
    }
    @Test void alteredRejectionAndPartiallyFilledOrderCannotReleaseFunds() {
        String source=rejected(buy);
        assertThrows(IllegalArgumentException.class,()->consumer.consumeOrderEvent(source.replace("\"price\":\"100", "\"price\":\"101")));
        consumer.consumeOrderEvent(event(1,"0.0005"));
        assertThrows(IllegalArgumentException.class,()->consumer.consumeOrderEvent(source));
        assertBalance(buyer,"USDT","755","200");
        assertNull(dispatches.findById(buy).orElseThrow().getConfirmedAt());
    }
    @Test void failedRejectionRefundRollsBackStateAndDispatchThenAllowsCorrectRetry() {
        balance(buyer,"USDT","750","100"); String source=rejected(buy);
        assertThrows(IllegalArgumentException.class,()->consumer.consumeOrderEvent(source));
        assertEquals("pending",orders.findById(buy).orElseThrow().getStatus());
        assertNull(dispatches.findById(buy).orElseThrow().getConfirmedAt());
        balance(buyer,"USDT","750","250"); consumer.consumeOrderEvent(source);
        assertBalance(buyer,"USDT","950","50");
    }

}
