package dustin.cex.shared.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.balance.repository.UserBalanceRepository;
import dustin.cex.domains.fee.service.FeeConfigService;
import dustin.cex.domains.order.repository.OrderRepository;
import dustin.cex.domains.position.repository.UserPositionRepository;
import dustin.cex.domains.settlement.trade.repository.TradeFeeRepository;
import dustin.cex.domains.trade.repository.TradeRepository;
import org.junit.jupiter.api.Test;

/** 수신 중복 검사가 실제 금융 처리 분기보다 먼저 실행되는지 확인한다. */
class KafkaOrderEventConsumerTest {
    final EngineEventInbox inbox=mock(EngineEventInbox.class);
    final OrderRepository orders=mock(OrderRepository.class);
    final TradeRepository trades=mock(TradeRepository.class);
    final KafkaOrderEventConsumer consumer=new KafkaOrderEventConsumer(inbox,trades,orders,
            mock(UserBalanceRepository.class),mock(UserPositionRepository.class),mock(FeeConfigService.class),
            mock(TradeFeeRepository.class),mock(UserRepository.class));
    final String message="""
            {"event_type":"order_cancelled","event_id":"engine:1:0","engine_id":"engine","order_id":1,"user_id":2,"base_mint":"SOL","quote_mint":"USDT"}
            """;

    @Test
    void knownDuplicateDoesNotTouchFinancialRepositories() {
        when(inbox.claim("engine:1:0","engine",message)).thenReturn(false);
        consumer.consumeOrderEvent(message);
        verifyNoInteractions(orders,trades);
    }

    @Test
    void inboxFailurePropagatesBeforeFinancialUpdates() {
        when(inbox.claim("engine:1:0","engine",message)).thenThrow(new IllegalStateException("동일 키 본문 충돌"));
        assertThrows(IllegalStateException.class,()->consumer.consumeOrderEvent(message));
        verifyNoInteractions(orders,trades);
    }

    @Test
    void missingFinancialOrderFailsInsteadOfCommittingOnlyReceipt() {
        when(inbox.claim("engine:1:0","engine",message)).thenReturn(true);
        when(orders.findByIdForUpdate(1L)).thenReturn(java.util.Optional.empty());
        assertThrows(IllegalStateException.class,()->consumer.consumeOrderEvent(message));
    }

    @Test
    void consumerConfigurationStopsInsteadOfDiscardingFailedFinancialEvent() {
        KafkaConfig config = new KafkaConfig();
        org.springframework.test.util.ReflectionTestUtils.setField(config,"bootstrapServers","127.0.0.1:1");
        org.springframework.test.util.ReflectionTestUtils.setField(config,"groupId","isolated-test");
        org.springframework.test.util.ReflectionTestUtils.setField(config,"autoOffsetReset","earliest");
        assertInstanceOf(org.springframework.kafka.listener.CommonContainerStoppingErrorHandler.class,
                org.springframework.test.util.ReflectionTestUtils.getField(config.kafkaListenerContainerFactory(),"commonErrorHandler"));
        assertFalse(config.kafkaListenerContainerFactory().createContainer("isolated-test").isAutoStartup());
        org.springframework.test.util.ReflectionTestUtils.setField(config,"listenerAutoStartup",true);
        assertTrue(config.kafkaListenerContainerFactory().createContainer("isolated-test").isAutoStartup());
    }

    @Test
    void newEventCannotSilentlySkipAnAlreadyClosedOrder() {
        when(inbox.claim("engine:1:0","engine",message)).thenReturn(true);
        when(orders.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(
                dustin.cex.domains.order.model.entity.Order.builder().id(1L).status("cancelled").build()));
        assertThrows(IllegalStateException.class,()->consumer.consumeOrderEvent(message));
        verify(orders,never()).save(any());
    }

    @Test
    void cancellationMustMatchOriginalOwnerAndMarket() {
        when(inbox.claim("engine:1:0","engine",message)).thenReturn(true);
        when(orders.findByIdForUpdate(1L)).thenReturn(java.util.Optional.of(
                dustin.cex.domains.order.model.entity.Order.builder().id(1L).status("pending")
                        .userId(99L).baseMint("SOL").quoteMint("USDT").build()));
        assertThrows(IllegalStateException.class,()->consumer.consumeOrderEvent(message));
        verify(orders,never()).save(any());
    }

    @Test
    void fullApplicationTestConfigurationNeverEnablesBots() {
        assertEquals(0,new dustin.cex.config.TestConfig().testBotConfig().getBotCount());
    }

    @Test
    void unknownEventTypeIsRejected() {
        assertThrows(IllegalArgumentException.class,()->consumer.consumeOrderEvent("{\"event_type\":\"unknown\"}"));
        verifyNoInteractions(inbox,orders,trades);
    }
}
