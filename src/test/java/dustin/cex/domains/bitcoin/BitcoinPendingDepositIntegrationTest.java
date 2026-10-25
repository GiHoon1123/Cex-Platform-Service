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

/** 실제 입금/지갑 사용/현재 판단과 이중 항목 원장의 원자성·멱등·우회 거부를 소유 노드/PG에서 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class BitcoinPendingDepositIntegrationTest {
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
    @Autowired BitcoinScanService scans;
    @Autowired BitcoinDepositReceiptService receipts;
    @Autowired BitcoinDepositObservationJobs jobs;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    String ordinary(){return System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX");}
    String recent(){return System.getenv("CEX_BITCOIN_TEST_REORG_TX");}
    UUID id(String tx){return jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,tx);}
    long count(){return jdbc.queryForObject("SELECT count(*) FROM bitcoin_pending_deposits",Long.class);}
    long decision(String tx){return jdbc.queryForObject("SELECT decision_id FROM bitcoin_deposit_chain_heads WHERE receipt_id=?",Long.class,id(tx));}
    long observation(String tx){return jdbc.queryForObject("SELECT observation_id FROM bitcoin_observation_heads WHERE txid=?",Long.class,tx);}
    String reason(String tx){return jdbc.queryForObject("SELECT reason FROM bitcoin_deposit_chain_decisions WHERE id=?",String.class,decision(tx));}
    String key(UUID id){return "btc:pending:"+id;}
    void refresh(String tx){jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE txid=?",tx);assertTrue(jobs.refreshDue());}
    @org.junit.jupiter.api.BeforeAll void sources(){
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email("pending-ledger@example.test").passwordHash("격리 시험의 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),System.getenv("CEX_BITCOIN_TEST_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SCRIPT"));
        int n=0;while(scans.scanNext().isPresent())assertTrue(++n<200);n=0;while(receipts.projectNext().isPresent())assertTrue(++n<200);
    }
    @org.junit.jupiter.api.BeforeEach void isolate(){jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()+interval '1 day',lease_id=NULL,lease_until=NULL");clearInvocations(engine);}
    @Test @org.junit.jupiter.api.Order(1) void explicitOpeningIsRequiredAndNeverInferredFromObservedFunds(){
        refresh(ordinary());assertEquals("CHAIN_READY",reason(ordinary()));
        assertThrows(IllegalStateException.class,()->pending.recognize(id(ordinary())));assertEquals(0,count());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
        ledger.initializeOpening();assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'",Long.class));
    }
    @Test @org.junit.jupiter.api.Order(2) void actualDepositCreatesBalancedPendingForDisabledCustomerWithoutAvailableOrEngineCredit(){
        refresh(ordinary());long owner=jdbc.queryForObject("SELECT user_id FROM bitcoin_deposit_receipts WHERE id=?",Long.class,id(ordinary()));
        var user=users.findById(owner).orElseThrow();user.setEnabled(false);users.saveAndFlush(user);
        var result=pending.recognize(id(ordinary()));assertTrue(result.created());assertEquals(key(id(ordinary())),result.operationKey());
        assertEquals(new java.math.BigDecimal("0.123456780"),jdbc.queryForObject("SELECT delta FROM ledger_entries WHERE operation_key=? AND bucket='PENDING'",java.math.BigDecimal.class,result.operationKey()));
        assertEquals(new java.math.BigDecimal("-0.123456780"),jdbc.queryForObject("SELECT delta FROM ledger_entries WHERE operation_key=? AND bucket='CUSTODY'",java.math.BigDecimal.class,result.operationKey()));
        assertEquals(0,jdbc.queryForObject("SELECT sum(delta) FROM ledger_entries WHERE operation_key=?",java.math.BigDecimal.class,result.operationKey()).signum());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));verifyNoInteractions(engine);
    }
    @Test @org.junit.jupiter.api.Order(3) void sameDepositRetryPreservesOperationAndNeverCreatesSecondPosting(){
        long before=count(),entries=jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class);
        var result=pending.recognize(id(ordinary()));assertFalse(result.created());assertEquals(key(id(ordinary())),result.operationKey());assertEquals(before,count());assertEquals(entries,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class));
    }
    @Test @org.junit.jupiter.api.Order(4) void simultaneousRecognitionOfAnotherRealDepositHasExactlyOneCreator()throws Exception{
        String tx=System.getenv("CEX_BITCOIN_TEST_REPLACEMENT_TX");refresh(tx);long before=count();var ready=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<BitcoinPendingDepositService.Recognized> call=()->{assertTrue(ready.await(5,TimeUnit.SECONDS));return pending.recognize(id(tx));};
            var a=pool.submit(call);var b=pool.submit(call);ready.countDown();var x=a.get(10,TimeUnit.SECONDS);var y=b.get(10,TimeUnit.SECONDS);
            assertNotEquals(x.created(),y.created());assertEquals(x.operationKey(),y.operationKey());
        }
        assertEquals(before+1,count());assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE operation_key=?",Long.class,key(id(tx))));
    }
    @Test @org.junit.jupiter.api.Order(5) void confirmedInternalWalletTransferCannotCreateExternalDepositLedger(){
        String tx=System.getenv("CEX_BITCOIN_TEST_INTERNAL_TX");refresh(tx);assertEquals("CHAIN_READY",reason(tx));
        assertTrue(jdbc.queryForObject("SELECT wallet_debit FROM bitcoin_observation_wallet_debits WHERE observation_id=?",Boolean.class,observation(tx)));
        long before=count();assertEquals(409,assertThrows(ResponseStatusException.class,()->pending.recognize(id(tx))).getStatusCode().value());assertEquals(before,count());
    }
    long copyObservation(String tx,boolean proof,boolean stale){
        long original=observation(tx);
        return new org.springframework.transaction.support.TransactionTemplate(manager).execute(t->{
            long source=jdbc.queryForObject("""
                INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at)
                 SELECT network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,
                  CASE WHEN ? THEN clock_timestamp()-interval '3 minutes' ELSE read_started_at END
                  FROM bitcoin_transaction_observations WHERE id=? RETURNING id
                """,Long.class,stale,original);
            if(proof)jdbc.update("INSERT INTO bitcoin_observation_wallet_debits(observation_id,wallet_debit) VALUES(?,false)",source);
            jdbc.update("UPDATE bitcoin_observation_heads SET observation_id=? WHERE txid=?",source,tx);qualification.evaluate(source);return source;
        });
    }
    @Test @org.junit.jupiter.api.Order(6) void observationWithoutWalletDebitEvidenceIsNeverAssumedExternal(){
        refresh(recent());assertEquals("CHAIN_READY",reason(recent()));copyObservation(recent(),false,false);assertEquals("CHAIN_READY",reason(recent()));
        long before=count();assertEquals(409,assertThrows(ResponseStatusException.class,()->pending.recognize(id(recent()))).getStatusCode().value());assertEquals(before,count());
    }
    @Test @org.junit.jupiter.api.Order(7) void staleObservationCannotCreatePendingMoney(){
        refresh(recent());copyObservation(recent(),true,true);assertEquals("STALE_OBSERVATION",reason(recent()));long before=count();
        assertEquals(409,assertThrows(ResponseStatusException.class,()->pending.recognize(id(recent()))).getStatusCode().value());assertEquals(before,count());
    }
    @Test @org.junit.jupiter.api.Order(8) void mandatoryAuditFailureRollsBackReceiptAndBothLedgerEntries(){
        refresh(recent());long before=count(),transactions=jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class),entries=jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class);
        doThrow(new IllegalStateException("격리 보류 반입 감사 장애")).when(audit).record(isNull(),eq("BTC_PENDING_DEPOSIT_RECOGNIZED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->pending.recognize(id(recent())));assertEquals(before,count());assertEquals(transactions,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));assertEquals(entries,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class));
    }
    @Test @org.junit.jupiter.api.Order(9) void databaseRejectsBalancedButWrongAmountAgainstCurrentDepositSource(){
        refresh(recent());long before=count();UUID receipt=id(recent());var row=jdbc.queryForMap("SELECT user_id,amount FROM bitcoin_deposit_receipts WHERE id=?",receipt);
        var wrong=((java.math.BigDecimal)row.get("amount")).add(java.math.BigDecimal.ONE);long owner=((Number)row.get("user_id")).longValue();
        assertThrows(RuntimeException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->{
            ledger.record(key(receipt),"BTC_DEPOSIT_PENDING",List.of(dustin.cex.domains.ledger.LedgerService.p(owner,"PENDING","BTC",wrong),dustin.cex.domains.ledger.LedgerService.p(0,"CUSTODY","BTC",wrong.negate())));
            jdbc.update("INSERT INTO bitcoin_pending_deposits(receipt_id,decision_id,operation_key) VALUES(?,?,?)",receipt,decision(recent()),key(receipt));
        }));assertEquals(before,count());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key=?",Long.class,key(receipt)));
    }
    @Test @org.junit.jupiter.api.Order(10) void databaseRejectsPendingOperationWithoutReceiptAndGenericBucketBypass(){
        long owner=jdbc.queryForObject("SELECT user_id FROM bitcoin_deposit_receipts WHERE id=?",Long.class,id(recent()));
        for(String kind:List.of("BTC_DEPOSIT_PENDING","TEST")){
            String operation="unbacked:"+kind;
            assertThrows(RuntimeException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->ledger.record(operation,kind,
                List.of(dustin.cex.domains.ledger.LedgerService.p(owner,"PENDING","BTC",java.math.BigDecimal.ONE),dustin.cex.domains.ledger.LedgerService.p(0,"CUSTODY","BTC",java.math.BigDecimal.ONE.negate())))));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key=?",Long.class,operation));
        }
    }
    @Test @org.junit.jupiter.api.Order(11) void committedPendingSourceCannotBeEditedDeletedOrTruncated(){
        long before=count();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("UPDATE bitcoin_pending_deposits SET operation_key=operation_key"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("DELETE FROM bitcoin_pending_deposits"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE bitcoin_pending_deposits"));assertEquals(before,count());
    }
    @Test @org.junit.jupiter.api.Order(12) void previouslyRecognizedFactIsIdempotentAfterLaterChainHold(){
        var real=reader.inspect(ordinary());var changed=new BitcoinDepositReader.Observation(real.network(),real.txid(),BitcoinDepositReader.Status.UNCONFIRMED,0,real.coinbase(),real.walletDebit(),null,null,real.tipHash(),real.tipHeight(),real.outputs());
        doReturn(changed).when(reader).inspect(ordinary());refresh(ordinary());assertEquals("WAITING_CHAIN",reason(ordinary()));long before=count();
        var original=pending.recognize(id(ordinary()));assertFalse(original.created());assertEquals(key(id(ordinary())),original.operationKey());assertEquals(before,count());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));
    }
    @Test @org.junit.jupiter.api.Order(13) void invalidIdentifierAndSurroundingTransactionCannotWritePartialMoney(){
        long before=count();assertThrows(IllegalArgumentException.class,()->pending.recognize(null));
        assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(t->pending.recognize(id(recent()))));assertEquals(before,count());
    }
    @Test @org.junit.jupiter.api.Order(14) void nextRecognitionSkipsInternalAndCompletedSourcesAndFindsEligibleExternalDeposit(){
        refresh(recent());long before=count();var next=pending.recognizeNext().orElseThrow();assertEquals(id(recent()),next.depositId());assertTrue(next.created());assertEquals(before+1,count());
        assertTrue(pending.recognizeNext().isEmpty());assertFalse(pending.recognize(id(recent())).created());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));verifyNoInteractions(engine);
    }
}
