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

/** 실제 발견 원본과 입금 ID를 전용 워커가 잇고 새 워커가 이어가는지 소유 노드/PG에서 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class BitcoinProcessingWorkerIntegrationTest {
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
    @Autowired BitcoinSettings config;
    @Autowired JdbcTemplate jdbc;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    @Test void actualWorkerDiscoversCustomerReceiptAndFreshWorkerResumesWithoutDuplicateId(){
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email("worker@example.test").passwordHash("격리 시험의 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),System.getenv("CEX_BITCOIN_TEST_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SCRIPT"));
        long target=Long.parseLong(System.getenv("CEX_BITCOIN_TEST_CONFIRMED_HEIGHT"));
        // 빈 과거 블록은 동기로 준비한다. 실제 수신 블록과 이후 재시작 진행은 실제 장수 워커가 처리한다.
        for(long h=0;h<target;h++){assertEquals(h,scans.scanNext().orElseThrow().height());receipts.projectNext().orElseThrow();}
        var worker=new BitcoinProcessingWorker(config,scans,receipts,true);
        try{
            worker.start();org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_receipts WHERE txid=?",Long.class,System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX"))==1);
        }finally{worker.stop();}
        var id=jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX"));
        var fresh=new BitcoinProcessingWorker(config,scans,receipts,true);
        try{
            fresh.start();org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->jdbc.queryForObject("SELECT count(*) FROM bitcoin_scan_receipt_projections",Long.class)==105);
        }finally{fresh.stop();}
        assertEquals(id,jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX")));
        assertEquals(105,jdbc.queryForObject("SELECT count(*) FROM bitcoin_block_scans",Long.class));
        assertEquals(new java.math.BigDecimal("0.12345678"),jdbc.queryForObject("SELECT amount FROM bitcoin_deposit_receipts WHERE id=?",java.math.BigDecimal.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
    }
}
