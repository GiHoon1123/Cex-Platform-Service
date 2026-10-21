package dustin.cex.domains.bot.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import dustin.cex.domains.bot.model.BotConfig;
import dustin.cex.domains.auth.repository.UserRepository;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

/** 테스트에서 봇·바이낸스·엔진을 실제로 시작하지 않고 기본 차단 정책을 확인한다. */
class BotSafetyTest {
    @Test void disabledBotsDoNotConnectOrReadAccounts() {
        var config=new BotConfig();var client=mock(BinanceWebSocketClient.class);var users=mock(UserRepository.class);
        assertEquals(0,config.getBotCount());assertEquals("BTC",config.getBotBaseMint(1L));assertTrue(config.getBinanceWsUrl().contains("btcusdt"));
        new BinancePriceService(config,client).start();new BotManagerService(config,users).initializeBots();
        verifyNoInteractions(client,users);
    }
    @Test void missingReferenceAndStaleReferenceNeverInventPrice() {
        var service=new BinancePriceService(new BotConfig(),mock(BinanceWebSocketClient.class));
        assertThrows(IllegalStateException.class,service::getMidPrice);
        var quote=new AtomicReference<>(new BinancePriceService.Quote(new BigDecimal("60000"),new BigDecimal("60002"),System.nanoTime()-11_000_000_000L,1));
        ReflectionTestUtils.setField(service,"quote",quote);
        assertThrows(IllegalStateException.class,service::getMidPrice);
        quote.set(new BinancePriceService.Quote(new BigDecimal("60000"),new BigDecimal("60002"),System.nanoTime(),1));
        assertEquals(new BigDecimal("60001.00"),service.getMidPrice());
    }
    @Test void enabledBotRequiresProvisionedAccountAndDoesNotCreateMoney() {
        var config=new BotConfig();config.setBotCount(1);var users=mock(UserRepository.class);
        when(users.findByEmail("bot1@bot.com")).thenReturn(java.util.Optional.empty());
        assertThrows(IllegalStateException.class,()->new BotManagerService(config,users).initializeBots());
        verify(users).findByEmail("bot1@bot.com");verifyNoMoreInteractions(users);
    }

    @Test void excessiveOrDifferentAssetBotOrdersDoNotReachNormalOrderService() {
        var orders=mock(dustin.cex.domains.order.repository.OrderRepository.class);
        var normal=mock(dustin.cex.domains.order.service.OrderService.class);
        var service=new BotService(normal,orders);
        assertThrows(IllegalArgumentException.class,()->service.createLimitBuyOrder(1L,"BTC",new BigDecimal("60000"),BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class,()->service.createLimitBuyOrder(1L,"ETH",new BigDecimal("1000"),new BigDecimal("0.001")));
        verifyNoInteractions(normal,orders);
    }
    @Test void fullBotOrderBudgetCancelsOldestAndWaitsForResultBeforeAdding() {
        var orders=mock(dustin.cex.domains.order.repository.OrderRepository.class);
        var normal=mock(dustin.cex.domains.order.service.OrderService.class);
        var active=java.util.List.of("pending","partial");
        when(orders.countByUserIdAndStatusIn(1L,active)).thenReturn(20L);
        when(orders.findFirstByUserIdAndStatusInOrderByCreatedAtAsc(1L,active)).thenReturn(java.util.Optional.of(dustin.cex.domains.order.model.entity.Order.builder().id(5L).build()));
        var service=new BotService(normal,orders);
        assertThrows(IllegalStateException.class,()->service.createLimitBuyOrder(1L,"BTC",new BigDecimal("60000"),new BigDecimal("0.001")));
        verify(normal).cancelOrder(1L,5L);verifyNoMoreInteractions(normal);
    }

    @Test void actualPartialDepthContractUpdatesBtcReferenceAndIgnoresOlderSnapshot() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var service=new BinancePriceService(new BotConfig(),mock(BinanceWebSocketClient.class));
        var snapshot=mapper.readValue("{\"lastUpdateId\":2,\"bids\":[[\"60000\",\"1\"]],\"asks\":[[\"60002\",\"1\"]]}",dustin.cex.domains.bot.model.BinanceOrderbookUpdate.class);
        service.update(snapshot);assertEquals(new BigDecimal("60001.00"),service.getMidPrice());
        snapshot.setLastUpdateId(1L);snapshot.setBids(java.util.List.of(java.util.List.of("100","1")));
        service.update(snapshot);assertEquals(new BigDecimal("60001.00"),service.getMidPrice());
        snapshot.setLastUpdateId(3L);snapshot.setBids(java.util.List.of(java.util.List.of("60000","-1")));
        assertThrows(IllegalArgumentException.class,()->service.update(snapshot));
    }
}
