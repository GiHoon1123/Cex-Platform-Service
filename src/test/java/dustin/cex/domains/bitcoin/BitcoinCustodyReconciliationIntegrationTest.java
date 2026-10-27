package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 PostgreSQL에서 노드 잔액과 수탁 원장의 읽기 전용 대사·불변 증거를 검증한다. */
@SpringBootTest(properties={"spring.kafka.listener.auto-startup=false","bot.bot-count=0","order.dispatch.enabled=false","bitcoin.processing.enabled=false","codevasp.processing.enabled=false","customer-mail.dispatch-enabled=false"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+")
class BitcoinCustodyReconciliationIntegrationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();static final String BLOCK="a".repeat(64);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values){
        values.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));values.add("spring.datasource.username",()->"root");values.add("spring.datasource.password",()->"1234");
        values.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");values.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");values.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");values.add("jwt.secret",()->"isolated-custody-reconciliation-jwt-secret");values.add("engine.service.token",()->"isolated-custody-reconciliation-engine-token");
        values.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");values.add("bitcoin.rpc-user",()->"custody_audit");values.add("bitcoin.rpc-password",()->"p".repeat(32));values.add("bitcoin.wallet",()->"watch");values.add("bitcoin.network",()->"REGTEST");
    }
    @MockitoBean BitcoinRpcClient rpc;@MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer kafka;@MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    @MockitoBean dustin.cex.domains.kyc.DiditClient didit;
    @Autowired BitcoinCustodyReconciliationService service;@Autowired dustin.cex.domains.ledger.LedgerService ledger;@Autowired JdbcTemplate jdbc;
    @BeforeEach void prepare(){if(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'",Long.class)==0)ledger.initializeOpening();when(rpc.ready()).thenReturn(new BitcoinRpcClient.Ready(object("{\"bestblockhash\":\""+BLOCK+"\",\"blocks\":100}"),object("{}")));when(rpc.call("getbalances",java.util.List.of())).thenReturn(balances("0","0","0"));}
    JsonNode object(String value){return JSON.readTree(value);}
    JsonNode balances(String trusted,String pending,String immature){return object("{\"mine\":{\"trusted\":"+trusted+",\"untrusted_pending\":"+pending+",\"immature\":"+immature+"},\"lastprocessedblock\":{\"hash\":\""+BLOCK+"\",\"height\":100}}");}
    @Test void exactStableWalletAndLedgerProduceImmutableMatchedEvidence(){var report=service.run();assertEquals("MATCHED",report.status(),()->jdbc.queryForList("SELECT * FROM bitcoin_custody_reconciliation_issues WHERE run_id=?",report.id()).toString());assertEquals(new BigDecimal("0.00000000"),jdbc.queryForObject("SELECT wallet_trusted FROM bitcoin_custody_reconciliation_runs WHERE id=?",BigDecimal.class,report.id()));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM bitcoin_custody_reconciliation_runs WHERE id=?",report.id()));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE bitcoin_custody_reconciliation_runs CASCADE"));}
    @Test void trustedWalletDifferenceIsRecordedWithoutRepairingEitherSide(){when(rpc.call("getbalances",java.util.List.of())).thenReturn(balances("1.00000000","0","0"));long ledgerRows=jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class);var report=service.run();assertEquals("MISMATCH",report.status());assertEquals("CUSTODY_BALANCE_MISMATCH",jdbc.queryForObject("SELECT code FROM bitcoin_custody_reconciliation_issues WHERE run_id=?",String.class,report.id()));assertEquals(ledgerRows,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class));}
    @Test void transitionalNodeBalanceIsIncompleteRatherThanAFalseRepair(){when(rpc.call("getbalances",java.util.List.of())).thenReturn(balances("0","0.10000000","0"));var report=service.run();assertEquals("INCOMPLETE",report.status());assertEquals("NODE_BALANCE_PENDING",jdbc.queryForObject("SELECT code FROM bitcoin_custody_reconciliation_issues WHERE run_id=?",String.class,report.id()));}
    @Test void nodeFailureIsStoredWithoutLeakingItsMessage(){doThrow(new IllegalStateException("격리비밀노출금지")).when(rpc).ready();var report=service.run();assertEquals("FAILED",report.status());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_custody_reconciliation_issues WHERE run_id=? AND actual LIKE '%격리비밀%'",Long.class,report.id()));}
}
