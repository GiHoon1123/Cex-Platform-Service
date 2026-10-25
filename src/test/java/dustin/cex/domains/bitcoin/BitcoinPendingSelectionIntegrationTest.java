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

/** 실제 두 고객의 입금에서 잠긴 고객을 기다리지 않고 다른 고객을 선택하는지 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class BitcoinPendingSelectionIntegrationTest {
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
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    long address(String label,String address,String script){
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(label+"@example.test").passwordHash("격리 시험의 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),address,script);return user.getId();
    }
    void refresh(String tx){jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE txid=?",tx);assertTrue(jobs.refreshDue());}
    UUID id(String tx){return jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,tx);}
    @Test void lockedFirstCustomerCannotStarveAnotherCustomersReadyDeposit()throws Exception{
        long first=address("locked-pending-first",System.getenv("CEX_BITCOIN_TEST_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SCRIPT"));
        address("unlocked-pending-second",System.getenv("CEX_BITCOIN_TEST_SECOND_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SECOND_SCRIPT"));
        int n=0;while(scans.scanNext().isPresent())assertTrue(++n<200);n=0;while(receipts.projectNext().isPresent())assertTrue(++n<200);ledger.initializeOpening();
        jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()+interval '1 day'");
        String firstTx=System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX"),secondTx=System.getenv("CEX_BITCOIN_TEST_SECOND_TX");refresh(firstTx);refresh(secondTx);
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var holder=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(t->{users.lockById(first).orElseThrow();locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return true;}));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            try{
                var next=pool.submit(pending::recognizeNext).get(3,TimeUnit.SECONDS).orElseThrow();assertEquals(id(secondTx),next.depositId());assertTrue(next.created());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_pending_deposits WHERE receipt_id=?",Long.class,id(firstTx)));
            }finally{release.countDown();}assertTrue(holder.get(5,TimeUnit.SECONDS));
        }
        assertEquals(id(firstTx),pending.recognizeNext().orElseThrow().depositId());assertTrue(pending.recognizeNext().isEmpty());
    }
}
