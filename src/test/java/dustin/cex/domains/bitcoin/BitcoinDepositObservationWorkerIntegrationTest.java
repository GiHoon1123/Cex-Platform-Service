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

/** 실제 블록→입금 ID→임대→거래 관찰을 독립 워커 둘이 잇고 재시작해도 보존하는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class BitcoinDepositObservationWorkerIntegrationTest {
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
    @Autowired BitcoinSettings config;
    @Autowired JdbcTemplate jdbc;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    String txid(){return System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX");}
    @Test void independentWorkersDiscoverObserveAndResumeActualCustomerDepositWithoutCredit(){
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email("observation-worker@example.test").passwordHash("격리 시험의 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),System.getenv("CEX_BITCOIN_TEST_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SCRIPT"));
        long target=Long.parseLong(System.getenv("CEX_BITCOIN_TEST_CONFIRMED_HEIGHT"));
        for(long h=0;h<target;h++){assertEquals(h,scans.scanNext().orElseThrow().height());receipts.projectNext().orElseThrow();}
        clearInvocations(engine);
        var discovery=new BitcoinProcessingWorker(config,scans,receipts,true);var observer=new BitcoinDepositObservationWorker(config,jobs,true);
        try{
            discovery.start();observer.start();org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).until(()->jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_observation_jobs WHERE txid=? AND latest_observation_id IS NOT NULL",Long.class,txid())==1);
        }finally{discovery.stop();observer.stop();}
        var id=jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,txid());
        long observed=jdbc.queryForObject("SELECT latest_observation_id FROM bitcoin_deposit_observation_jobs WHERE txid=?",Long.class,txid());
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT status FROM bitcoin_transaction_observations WHERE id=?",String.class,observed));
        assertEquals(new java.math.BigDecimal("0.12345678"),jdbc.queryForObject("SELECT amount FROM bitcoin_deposit_receipts WHERE id=?",java.math.BigDecimal.class,id));
        var freshDiscovery=new BitcoinProcessingWorker(config,scans,receipts,true);var freshObserver=new BitcoinDepositObservationWorker(config,jobs,true);
        try{
            freshDiscovery.start();freshObserver.start();org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).until(()->
                jdbc.queryForObject("SELECT count(*) FROM bitcoin_scan_receipt_projections",Long.class)==105
                 &&jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_observation_jobs WHERE latest_observation_id IS NULL",Long.class)==0);
        }finally{freshDiscovery.stop();freshObserver.stop();}
        assertEquals(id,jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,txid()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));verifyNoInteractions(engine);
    }
}
