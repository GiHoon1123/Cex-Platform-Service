package dustin.cex.shared.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    @MockBean EngineEventInbox inbox;
    @Autowired KafkaOrderEventConsumer consumer;
    @Autowired UserRepository users; @Autowired OrderRepository orders;
    @Autowired OrderSubmissionRepository submissions; @Autowired UserBalanceRepository balances;
    @Autowired dustin.cex.domains.position.repository.UserPositionRepository positions;
    @Autowired TradeRepository trades; @Autowired TradeFeeRepository fees;
    long buyer, seller, buy, sell;
    BigDecimal n(String value) { return new BigDecimal(value); }
    void balance(long user, String asset, String available, String locked) {
        balances.saveAndFlush(UserBalance.builder().userId(user).mintAddress(asset).available(n(available)).locked(n(locked)).build());
    }
    long order(long user, String side, String price, String rate) {
        var order = orders.saveAndFlush(Order.builder().userId(user).orderType(side).orderSide("limit")
                .baseMint("BTC").quoteMint("USDT").price(n(price)).amount(n("2"))
                .filledAmount(BigDecimal.ZERO).filledQuoteAmount(BigDecimal.ZERO).status("pending").build());
        var command = new OrderCommand(side,"limit","BTC","USDT",n(price),n("2"),null);
        submissions.saveAndFlush(new OrderSubmission(user,UUID.randomUUID(),order.getId(),command,n(rate)));
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
            """.formatted(sequence,buy,sell,buyer,seller,buyerFee);
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
}
