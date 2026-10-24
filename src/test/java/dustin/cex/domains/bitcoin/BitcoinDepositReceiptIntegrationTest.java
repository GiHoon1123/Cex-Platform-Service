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

/** 실제 수신 발견과 영속 고객 입금 ID를 검증한다. 복제 원본은 중복/위조 경계의 시험 자료로만 사용한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class BitcoinDepositReceiptIntegrationTest {
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
    @MockitoSpyBean AuditService audit;
    @Autowired BitcoinScanService scans;
    @Autowired BitcoinDepositReceiptService receipts;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    long count(){return jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_receipts",Long.class);}
    long marks(){return jdbc.queryForObject("SELECT count(*) FROM bitcoin_scan_receipt_projections",Long.class);}
    long source(){return jdbc.queryForObject("SELECT first_scan_id FROM bitcoin_deposit_receipts WHERE txid=?",Long.class,System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX"));}
    UUID receipt(){return jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX"));}
    long copy(long original,String changed){
        // 이 복제는 실제 체인 재조직이 아니다. 동일/상충 출력이 다시 전달되는 경계를 불변 새 원본으로 검증한다.
        return jdbc.queryForObject("""
            INSERT INTO bitcoin_block_scans(network,wallet,previous_scan_id,height,block_hash,parent_hash,tip_hash,tip_height,registry_id,received)
             SELECT network,wallet,previous_scan_id,height,block_hash,parent_hash,tip_hash,tip_height,registry_id,
                    CASE WHEN ?::jsonb IS NULL THEN received ELSE ?::jsonb END
              FROM bitcoin_block_scans WHERE id=? RETURNING id
            """,Long.class,changed,changed,original);
    }
    String changedOutput(String field,String value){return jdbc.queryForObject("SELECT jsonb_set(received,ARRAY['0',?],?::jsonb)::text FROM bitcoin_block_scans WHERE id=?",String.class,field,value,source());}
    @Test @org.junit.jupiter.api.Order(1) void noCompletedSourceIsIdleAndEmptyBlockHasDurableProjection(){
        assertTrue(receipts.projectNext().isEmpty());
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email("receipts@example.test").passwordHash("격리 시험의 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),System.getenv("CEX_BITCOIN_TEST_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SCRIPT"));
        var block=scans.scanNext().orElseThrow();var result=receipts.projectNext().orElseThrow();
        assertEquals(block.id(),result.scanId());assertEquals(0,result.outputs());assertEquals(0,result.created());assertEquals(1,marks());assertEquals(0,count());
        assertTrue(receipts.projectNext().isEmpty());
    }
    @Test @org.junit.jupiter.api.Order(2) void realDepositAuditFailureRollsBackIdentityAndCompletionThenRetrySucceeds(){
        int scanned=0;while(scans.scanNext().isPresent())assertTrue(++scanned<200);
        long firstPositive=jdbc.queryForObject("SELECT min(id) FROM bitcoin_block_scans WHERE jsonb_array_length(received)>0",Long.class);
        while(jdbc.queryForObject("SELECT min(s.id) FROM bitcoin_block_scans s WHERE NOT EXISTS(SELECT 1 FROM bitcoin_scan_receipt_projections p WHERE p.scan_id=s.id)",Long.class)<firstPositive)receipts.projectNext().orElseThrow();
        long before=marks();
        doThrow(new IllegalStateException("격리 입금 감사 장애")).when(audit).record(isNull(),eq("BTC_DEPOSIT_DISCOVERED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->receipts.projectNext());assertEquals(0,count());assertEquals(before,marks());
        reset(audit);var result=receipts.projectNext().orElseThrow();assertEquals(firstPositive,result.scanId());assertTrue(result.created()>0);
        assertEquals(new java.math.BigDecimal("0.12345678"),jdbc.queryForObject("SELECT amount FROM bitcoin_deposit_receipts WHERE txid=?",java.math.BigDecimal.class,System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX")));
    }
    @Test @org.junit.jupiter.api.Order(3) void allSourcesProduceOwnedStableFactsWithoutBalanceOrLedgerCredit(){
        int done=0;while(receipts.projectNext().isPresent())assertTrue(++done<200);
        assertTrue(count()>=2);assertEquals(105,marks());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id WHERE r.txid=? AND r.user_id=a.user_id AND r.asset='BTC'",Long.class,System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX")));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
        assertEquals(count(),jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_DEPOSIT_DISCOVERED'",Long.class));
    }
    @Test @org.junit.jupiter.api.Order(4) void repeatedOutpointKeepsOriginalIdOwnerAmountAndFirstSource(){
        UUID id=receipt();long original=source(),before=count();long duplicate=copy(original,null);
        var result=receipts.projectNext().orElseThrow();assertEquals(duplicate,result.scanId());assertEquals(0,result.created());assertTrue(result.outputs()>0);
        assertEquals(id,receipt());assertEquals(original,source());assertEquals(before,count());
    }
    @Test @org.junit.jupiter.api.Order(5) void concurrentProjectionCommitsOneCompletionAndKeepsSameReceiptIds()throws Exception{
        long duplicate=copy(source(),null),before=count(),completed=marks();UUID id=receipt();var ready=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Optional<BitcoinDepositReceiptService.Projected>> call=()->{assertTrue(ready.await(5,TimeUnit.SECONDS));return receipts.projectNext();};
            var a=pool.submit(call);var b=pool.submit(call);ready.countDown();var x=a.get(10,TimeUnit.SECONDS);var y=b.get(10,TimeUnit.SECONDS);
            assertNotEquals(x.isPresent(),y.isPresent());assertEquals(duplicate,(x.isPresent()?x:y).orElseThrow().scanId());
        }
        assertEquals(completed+1,marks());assertEquals(before,count());assertEquals(id,receipt());
    }
    @Test @org.junit.jupiter.api.Order(6) void zeroValueOutputRemainsSourceAndNeverBecomesEconomicReceipt(){
        long before=count();long zero=copy(source(),changedOutput("amount","0"));
        var result=receipts.projectNext().orElseThrow();assertEquals(zero,result.scanId());assertEquals(0,result.outputs());assertEquals(0,result.created());assertEquals(before,count());
        assertEquals("0",jdbc.queryForObject("SELECT received->0->>'amount' FROM bitcoin_block_scans WHERE id=?",String.class,zero));
    }
    @Test @org.junit.jupiter.api.Order(7) void databaseRejectsForgedSourceBindingAndIncompleteProjection(){
        long before=count(),completed=marks();
        var r=jdbc.queryForMap("SELECT * FROM bitcoin_deposit_receipts WHERE txid=?",System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("""
            INSERT INTO bitcoin_deposit_receipts(id,network,txid,vout,address_id,user_id,asset,amount,first_scan_id)
             VALUES(?,'bitcoin-regtest',?,?,?,?,'BTC',?,?)
            """,UUID.randomUUID(),"aa".repeat(32),r.get("vout"),r.get("address_id"),r.get("user_id"),r.get("amount"),r.get("first_scan_id")));
        assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{
            long changed=copy(source(),changedOutput("txid","\""+"bb".repeat(32)+"\""));
            jdbc.update("INSERT INTO bitcoin_scan_receipt_projections(scan_id,output_count,new_count) VALUES(?,1,0)",changed);
        }));
        // 이미 존재하는 출력은 신규 입금으로 집계할 수 없다. 원본 복제/거짓 완료 표는 같은 TX에서 전부 롤백한다.
        assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{
            long duplicate=copy(source(),null);
            jdbc.update("INSERT INTO bitcoin_scan_receipt_projections(scan_id,output_count,new_count) VALUES(?,1,1)",duplicate);
        }));
        assertEquals(before,count());assertEquals(completed,marks());
    }
    @Test @org.junit.jupiter.api.Order(8) void immutableReceiptsAndProjectionMarkersRejectChangesDeletionAndTruncation(){
        UUID id=receipt();
        for(String table:List.of("bitcoin_deposit_receipts","bitcoin_scan_receipt_projections")){
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("DELETE FROM "+table));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE "+table+" CASCADE"));
        }
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("UPDATE bitcoin_deposit_receipts SET amount=amount+1"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("UPDATE bitcoin_scan_receipt_projections SET new_count=0"));assertEquals(id,receipt());
    }
    @Test @org.junit.jupiter.api.Order(9) void callerTransactionCannotTurnIndependentProjectionIntoPartialCommit(){
        long before=count(),completed=marks();assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->receipts.projectNext()));
        assertEquals(before,count());assertEquals(completed,marks());
    }
    @Test @org.junit.jupiter.api.Order(10) void sameOutpointWithChangedAmountFailsWithoutReplacingFinancialIdentity(){
        UUID id=receipt();long before=count(),completed=marks();copy(source(),changedOutput("amount","1.12345678"));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->receipts.projectNext()).getStatusCode().value());
        assertEquals(before,count());assertEquals(completed,marks());assertEquals(id,receipt());
        assertEquals(new java.math.BigDecimal("0.12345678"),jdbc.queryForObject("SELECT amount FROM bitcoin_deposit_receipts WHERE id=?",java.math.BigDecimal.class,id));
    }
}
