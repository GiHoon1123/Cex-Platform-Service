package dustin.cex.shared.kafka;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 격리 실행기가 만든 PostgreSQL에서 실제 충돌·금융 반영·롤백 경계를 검사한다. */
class EngineEventInboxTest {
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    EngineEventInbox inbox;
    final String engine = UUID.randomUUID().toString();
    final String event = engine + ":1:0";

    @BeforeEach
    void setup() {
        String url = System.getenv("CEX_TEST_DATABASE_URL");
        assumeTrue(url != null, "격리 PostgreSQL 실행기로만 실행한다");
        DriverManagerDataSource source = new DriverManagerDataSource(url, "root", "1234");
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        inbox = new EngineEventInbox(jdbc);
        jdbc.execute("CREATE TABLE IF NOT EXISTS engine_event_receipts(event_id varchar(120) PRIMARY KEY,engine_id varchar(36) NOT NULL,payload_hash varchar(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS inbox_test_effects(event_id varchar(120) PRIMARY KEY,amount integer NOT NULL)");
    }

    @Test
    void duplicateDeliveryChangesFinancialStateOnce() {
        for (int i=0; i<2; i++) {
            transaction.executeWithoutResult(status -> {
                if (inbox.claim(event,engine,"original")) {
                    jdbc.update("INSERT INTO inbox_test_effects VALUES(?,100)",event);
                }
            });
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_test_effects WHERE event_id=?",Integer.class,event));
    }

    @Test
    void financialFailureRollsBackReceiptAndAllowsRetry() {
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            assertTrue(inbox.claim(event,engine,"original"));
            jdbc.update("INSERT INTO inbox_test_effects VALUES(?,100)",event);
            throw new IllegalStateException("금융 반영 실패 주입");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM engine_event_receipts WHERE event_id=?",Integer.class,event));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM inbox_test_effects WHERE event_id=?",Integer.class,event));
        transaction.executeWithoutResult(status -> assertTrue(inbox.claim(event,engine,"original")));
    }

    @Test
    void sameIdentityDifferentPayloadIsRejected() {
        transaction.executeWithoutResult(status -> assertTrue(inbox.claim(event,engine,"original")));
        assertThrows(IllegalStateException.class,() -> transaction.executeWithoutResult(status -> inbox.claim(event,engine,"changed")));
    }

    @Test
    void concurrentDeliveriesHaveOneWinner() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(() -> transaction.execute(status -> inbox.claim(event,engine,"original")));
            var b=executor.submit(() -> transaction.execute(status -> inbox.claim(event,engine,"original")));
            assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
    }

    @Test
    void actualConsumerFailureRollsBackTheRealInboxClaim() {
        var orders=org.mockito.Mockito.mock(dustin.cex.domains.order.repository.OrderRepository.class);
        org.mockito.Mockito.when(orders.findByIdForUpdate(1L)).thenReturn(java.util.Optional.empty());
        var consumer=new KafkaOrderEventConsumer(org.mockito.Mockito.mock(dustin.cex.domains.ledger.LedgerService.class), inbox,
                org.mockito.Mockito.mock(dustin.cex.domains.trade.repository.TradeRepository.class), orders,
                org.mockito.Mockito.mock(dustin.cex.domains.balance.repository.UserBalanceRepository.class),
                org.mockito.Mockito.mock(dustin.cex.domains.position.repository.UserPositionRepository.class),
                org.mockito.Mockito.mock(dustin.cex.domains.order.repository.OrderSubmissionRepository.class),
                org.mockito.Mockito.mock(dustin.cex.domains.settlement.trade.repository.TradeFeeRepository.class),
                org.mockito.Mockito.mock(dustin.cex.domains.auth.repository.UserRepository.class),
                org.mockito.Mockito.mock(dustin.cex.domains.order.repository.OrderDispatchRepository.class),org.mockito.Mockito.mock(dustin.cex.domains.bitcoin.BitcoinCreditedEvents.class));
        String payload="""
                {"event_type":"order_cancelled","event_id":"%s","engine_id":"%s","order_id":1,"user_id":2,"base_mint":"SOL","quote_mint":"USDT"}
                """.formatted(event,engine);
        assertThrows(IllegalStateException.class,()->transaction.executeWithoutResult(status -> consumer.consumeOrderEvent(payload)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM engine_event_receipts WHERE event_id=?",Integer.class,event));
    }

    @Test
    void transactionAndDurableIdentityAreRequired() {
        assertThrows(IllegalStateException.class,() -> inbox.claim(event,engine,"original"));
        assertThrows(IllegalArgumentException.class,() -> transaction.executeWithoutResult(status -> inbox.claim("",engine,"original")));
    }
}
