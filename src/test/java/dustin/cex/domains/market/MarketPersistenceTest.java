package dustin.cex.domains.market;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import dustin.cex.shared.kafka.EngineEventInbox;
import java.time.Instant;
import java.util.*;

/** 실행기가 소유한 PostgreSQL에서만 실제 수신 키·호가·체결의 원자성을 검증한다. */
@DataJpaTest(properties={"spring.kafka.listener.auto-startup=false","spring.kafka.bootstrap-servers=127.0.0.1:1","bot.bot-count=0"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({MarketService.class,EngineEventInbox.class,dustin.cex.shared.kafka.KafkaConfig.class,MarketPersistenceTest.JsonConfig.class})
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class MarketPersistenceTest {
    @org.springframework.boot.test.context.TestConfiguration static class JsonConfig {
        @Bean ObjectMapper mapper() {return tools.jackson.databind.json.JsonMapper.builder().disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();}
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));
        registry.add("spring.datasource.username",()->"root");registry.add("spring.datasource.password",()->"1234");
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.jpa.database-platform",()->"org.hibernate.dialect.PostgreSQLDialect");
    }
    @Autowired MarketService service; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    String engine=UUID.randomUUID().toString();
    String base="T"+UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase();
    String payload(long sequence,long previous,boolean trade) throws Exception {
        return mapper.writeValueAsString(new MarketSnapshot("market_snapshot",engine+":"+sequence+":market",engine,Long.toString(sequence),Long.toString(previous),base,"USDT",Instant.parse("2026-10-21T00:00:00Z"),List.of(),List.of(),trade?List.of(new MarketSnapshot.Print(engine+":"+sequence+":0","60000","0.001","buy")):List.of()));
    }
    @Test void duplicateAfterCommitPreservesOneTradeAndOneCandle() throws Exception {
        String payload=payload(2,1,true);
        service.consume(payload);service.consume(payload);
        var view=service.view(base,"USDT");assertEquals("2",view.book().sequence());assertEquals(1,view.trades().size());
        var candles=service.candles(base,"USDT");assertEquals(1,candles.size());assertEquals(0,new java.math.BigDecimal("0.001").compareTo(new java.math.BigDecimal(candles.getFirst().volume())));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM engine_event_receipts WHERE event_id=?",Integer.class,engine+":2:market"));
    }
    @Test void gapRollsBackReceiptAndCanRetryAfterMissingEvent() throws Exception {
        service.consume(payload(1,0,false));
        String gap=payload(4,3,true);
        assertThrows(IllegalStateException.class,()->service.consume(gap));
        assertEquals("1",service.view(base,"USDT").book().sequence());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM engine_event_receipts WHERE event_id=?",Integer.class,engine+":4:market"));
        service.consume(payload(3,1,false));service.consume(gap);
        assertEquals("4",service.view(base,"USDT").book().sequence());assertEquals(1,service.view(base,"USDT").trades().size());
    }
    @Test void changedPayloadCannotOverwriteConfirmedSnapshot() throws Exception {
        service.consume(payload(1,0,false));
        String changed=payload(1,0,false).replace("2026-10-21T00:00:00Z","2026-10-21T00:00:01Z");
        assertThrows(IllegalStateException.class,()->service.consume(changed));
        assertEquals(Instant.parse("2026-10-21T00:00:00Z"),service.view(base,"USDT").book().timestamp());
    }

    @Test void earlierRestartSnapshotDoesNotRollBackBookOrDuplicateTrades() throws Exception {
        service.consume(payload(4,3,true));String earlier=payload(2,1,true);
        service.consume(earlier);service.consume(earlier);
        assertEquals("4",service.view(base,"USDT").book().sequence());assertEquals(2,service.view(base,"USDT").trades().size());
    }
}
