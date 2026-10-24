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

/** 실제 노드·별도 PG에서 관찰 이력/현재 포인터/감사를 함께 검증한다. 고객 적립은 실행하지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
class BitcoinObservationIntegrationTest {
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
    @MockitoSpyBean BitcoinDepositReader reader;
    @MockitoSpyBean AuditService audit;
    @Autowired BitcoinObservationService observations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    String txid(){return System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX");}
    long count(){return jdbc.queryForObject("SELECT count(*) FROM bitcoin_transaction_observations",Long.class);}
    long head(){return jdbc.queryForObject("SELECT observation_id FROM bitcoin_observation_heads WHERE network='bitcoin-regtest' AND txid=?",Long.class,txid());}
    @Test void actualObservationCommitsSourceCurrentPointerAndAuditWithoutCredit(){
        var result=observations.refresh(txid());assertEquals(result.id(),head());
        assertEquals("CONFIRMED",jdbc.queryForObject("SELECT status FROM bitcoin_transaction_observations WHERE id=?",String.class,result.id()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_TRANSACTION_OBSERVED' AND detail=?",Long.class,"OBSERVATION:"+result.id()));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT read_started_at<=recorded_at FROM bitcoin_transaction_observations WHERE id=?",Boolean.class,result.id())));
        var amount=jdbc.queryForObject("SELECT output->>'amount' FROM bitcoin_transaction_observations o CROSS JOIN LATERAL jsonb_array_elements(o.outputs) output WHERE o.id=? AND output->>'address'=?",String.class,result.id(),System.getenv("CEX_BITCOIN_TEST_ADDRESS"));
        assertEquals(new java.math.BigDecimal("0.12345678"),new java.math.BigDecimal(amount));
    }
    @Test void repeatedIndependentReadsKeepOriginalAndAdvanceCurrentPointer(){
        var first=observations.refresh(txid());var second=observations.refresh(txid());assertTrue(second.id()>first.id());assertEquals(second.id(),head());
        assertEquals(first.tipHash(),second.tipHash());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM bitcoin_transaction_observations WHERE id IN (?,?)",Long.class,first.id(),second.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE bitcoin_observation_heads SET observation_id=? WHERE txid=?",first.id(),txid()));
    }
    @Test void concurrentFirstReadsAllowOnlyOneVersionWinner()throws Exception{
        // 새로운 거래 키로 두 조회의 초기 버전을 같게 만든다. 이전 시험의 원본/포인터는 지우지 않는다.
        String fresh=System.getenv("CEX_BITCOIN_TEST_UNCONFIRMED_TX");long before=count();var barrier=new CountDownLatch(2);
        doAnswer(call->{var result=call.callRealMethod();barrier.countDown();assertTrue(barrier.await(5,TimeUnit.SECONDS));return result;}).when(reader).inspect(fresh);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> run=()->{try{observations.refresh(fresh);return true;}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}};
            var a=pool.submit(run);var b=pool.submit(run);assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(before+1,count());
    }
    @Test void slowEarlierReadCannotReplaceNewerCommittedObservation()throws Exception{
        var arrived=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        doAnswer(call->{var result=call.callRealMethod();if(first.getAndSet(false)){arrived.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}return result;}).when(reader).inspect(txid());
        try(var pool=Executors.newSingleThreadExecutor()){
            var slow=pool.submit(()->observations.refresh(txid()));assertTrue(arrived.await(5,TimeUnit.SECONDS));
            try{
                var winner=observations.refresh(txid());release.countDown();
                var failed=assertThrows(ExecutionException.class,()->slow.get(5,TimeUnit.SECONDS));assertInstanceOf(ResponseStatusException.class,failed.getCause());
                assertEquals(409,((ResponseStatusException)failed.getCause()).getStatusCode().value());assertEquals(winner.id(),head());
            }finally{release.countDown();}
        }
    }
    @Test void auditFailureRollsBackNewSourceAndCurrentPointer(){
        var original=observations.refresh(txid());long before=count();
        doThrow(new IllegalStateException("시험용 감사 장애")).when(audit).record(isNull(),eq("BTC_TRANSACTION_OBSERVED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->observations.refresh(txid()));assertEquals(original.id(),head());assertEquals(before,count());
    }
    @Test void nodeFailureAndAmbientTransactionCannotChangeCurrentPointer(){
        var original=observations.refresh(txid());long before=count();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(reader).inspect(txid());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->observations.refresh(txid())).getStatusCode().value());
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->observations.refresh(txid())));
        assertEquals(before,count());assertEquals(original.id(),head());
    }
    @Test void immutableSourceAndHeadDeletionAreRejected(){
        var source=observations.refresh(txid());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE bitcoin_transaction_observations SET confirmations=confirmations+1 WHERE id=?",source.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM bitcoin_transaction_observations WHERE id=?",source.id()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE bitcoin_transaction_observations CASCADE"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM bitcoin_observation_heads WHERE txid=?",txid()));
        assertEquals(source.id(),head());
    }
    @Test void currentPointerCannotAttachOldFactToAnotherTransaction(){
        var source=observations.refresh(txid());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_observation_heads(network,txid,observation_id) VALUES('bitcoin-regtest',?,?)","22".repeat(32),source.id()));
        assertEquals(source.id(),head());
    }
    @Test void conflictingAndReorgObservationsPersistAsNonCreditedFacts(){
        for(String variable:List.of("CEX_BITCOIN_TEST_CONFLICTED_TX","CEX_BITCOIN_TEST_REORG_TX")){
            var result=observations.refresh(System.getenv(variable));assertNotEquals(BitcoinDepositReader.Status.CONFIRMED,result.status());
            assertNull(jdbc.queryForObject("SELECT block_hash FROM bitcoin_transaction_observations WHERE id=?",String.class,result.id()));
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));
    }
    @Test void readerResultForDifferentTransactionCannotBecomeRequestedHead(){
        var original=observations.refresh(txid());long before=count();
        var wrong=reader.inspect(System.getenv("CEX_BITCOIN_TEST_REORG_TX"));doReturn(wrong).when(reader).inspect(txid());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->observations.refresh(txid())).getStatusCode().value());
        assertEquals(original.id(),head());assertEquals(before,count());
    }
    @Test void higherButPreviouslyCommittedFactCannotBeActivatedAsFreshObservation(){
        var original=observations.refresh(txid());
        // 실제 업무는 이처럼 원본만 따로 확정하지 않는다. 포인터의 DB 경계가 이를 거부하는지 확인한다.
        long detached=jdbc.queryForObject("""
            INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,
              block_hash,block_height,tip_hash,tip_height,outputs,read_started_at)
            SELECT network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at
              FROM bitcoin_transaction_observations WHERE id=? RETURNING id
            """,Long.class,original.id());
        assertTrue(detached>original.id());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE bitcoin_observation_heads SET observation_id=? WHERE txid=?",detached,txid()));
        assertEquals(original.id(),head());
    }
}
