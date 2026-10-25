package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import dustin.cex.domains.security.AuditService;

/** 세 독립 워커의 실제 발견→관찰→보류 원장과 종료/재개를 확인한다. 원장 최초 반입은 명시적으로 수행한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class BitcoinPendingDepositWorkerIntegrationTest {
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");
        r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
        r.add("customer-mail.dispatch-enabled",()->false);
        r.add("bitcoin.rpc-url",()->System.getenv("CEX_BITCOIN_TEST_URL"));r.add("bitcoin.rpc-user",()->System.getenv("CEX_BITCOIN_TEST_USER"));
        r.add("bitcoin.rpc-password",()->System.getenv("CEX_BITCOIN_TEST_PASSWORD"));r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");
    }
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    @MockitoBean dustin.cex.domains.kyc.DiditClient didit;
    @Autowired BitcoinScanService scans;
    @Autowired BitcoinDepositReceiptService receipts;
    @Autowired BitcoinDepositObservationJobs jobs;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired BitcoinSettings config;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    void address(String label,String address,String script){
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(label+"@example.test").passwordHash("격리 시험의 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),address,script);
    }
    @Test void threeWorkersWaitForExplicitOpeningThenResumeBothCustomerPendingBalances(){
        address("automatic-pending-first",System.getenv("CEX_BITCOIN_TEST_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SCRIPT"));
        address("automatic-pending-second",System.getenv("CEX_BITCOIN_TEST_SECOND_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SECOND_SCRIPT"));
        long target=Long.parseLong(System.getenv("CEX_BITCOIN_TEST_CONFIRMED_HEIGHT"));
        for(long h=0;h<target;h++){assertEquals(h,scans.scanNext().orElseThrow().height());receipts.projectNext().orElseThrow();}
        String known=System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX");clearInvocations(engine);
        var discovery=new BitcoinProcessingWorker(config,scans,receipts,true);var observer=new BitcoinDepositObservationWorker(config,jobs,true);var financial=new BitcoinPendingDepositWorker(config,pending,true);
        try{
            discovery.start();observer.start();financial.start();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).until(()->jdbc.queryForObject("""
                SELECT count(*) FROM bitcoin_deposit_chain_heads h JOIN bitcoin_deposit_chain_decisions d ON d.id=h.decision_id
                 JOIN bitcoin_deposit_receipts r ON r.id=h.receipt_id WHERE r.txid=? AND d.reason='CHAIN_READY'
                """,Long.class,known)==1);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_pending_deposits",Long.class));
            ledger.initializeOpening();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->jdbc.queryForObject("SELECT count(*) FROM bitcoin_pending_deposits f JOIN bitcoin_deposit_receipts r ON r.id=f.receipt_id WHERE r.txid=?",Long.class,known)==1);
        }finally{financial.stop();observer.stop();discovery.stop();}
        UUID id=jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,known);
        var freshDiscovery=new BitcoinProcessingWorker(config,scans,receipts,true);var freshObserver=new BitcoinDepositObservationWorker(config,jobs,true);var freshFinancial=new BitcoinPendingDepositWorker(config,pending,true);
        try{
            freshDiscovery.start();freshObserver.start();freshFinancial.start();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).until(()->
                jdbc.queryForObject("SELECT count(*) FROM bitcoin_scan_receipt_projections",Long.class)==111
                 &&jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_observation_jobs WHERE latest_observation_id IS NULL",Long.class)==0
                 &&jdbc.queryForObject("SELECT count(*) FROM bitcoin_pending_deposits f JOIN bitcoin_deposit_receipts r ON r.id=f.receipt_id WHERE r.txid=?",Long.class,System.getenv("CEX_BITCOIN_TEST_SECOND_TX"))==1);
        }finally{freshFinancial.stop();freshObserver.stop();freshDiscovery.stop();}
        assertEquals(id,jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,known));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_pending_deposits WHERE receipt_id=?",Long.class,id));
        assertEquals(new java.math.BigDecimal("0.200000000"),jdbc.queryForObject("SELECT e.delta FROM ledger_entries e JOIN bitcoin_pending_deposits f ON f.operation_key=e.operation_key JOIN bitcoin_deposit_receipts r ON r.id=f.receipt_id WHERE r.txid=? AND e.bucket='PENDING'",java.math.BigDecimal.class,System.getenv("CEX_BITCOIN_TEST_SECOND_TX")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_pending_deposits f JOIN bitcoin_deposit_receipts r ON r.id=f.receipt_id WHERE r.txid=?",Long.class,System.getenv("CEX_BITCOIN_TEST_INTERNAL_TX")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));verifyNoInteractions(engine);
    }
}
