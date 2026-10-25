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

/** 실제6회 확인과 미성숙 보상 거래를 판정하고 출력 위조/오래된 근거/감사 실패를 독립 주입한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class BitcoinDepositQualificationIntegrationTest {
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
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    String txid(){return System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX");}
    UUID receipt(String tx){return jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,tx);}
    long observation(String tx){return jdbc.queryForObject("SELECT latest_observation_id FROM bitcoin_deposit_observation_jobs WHERE txid=?",Long.class,tx);}
    long head(String tx){return jdbc.queryForObject("SELECT decision_id FROM bitcoin_deposit_chain_heads WHERE receipt_id=?",Long.class,receipt(tx));}
    String reason(String tx){return jdbc.queryForObject("SELECT d.reason FROM bitcoin_deposit_chain_heads h JOIN bitcoin_deposit_chain_decisions d ON d.id=h.decision_id WHERE h.receipt_id=?",String.class,receipt(tx));}
    void refresh(String tx){jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE txid=?",tx);assertTrue(jobs.refreshDue());}
    @org.junit.jupiter.api.BeforeAll void sources(){
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email("qualification@example.test").passwordHash("격리 시험의 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),System.getenv("CEX_BITCOIN_TEST_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SCRIPT"));
        int n=0;while(scans.scanNext().isPresent())assertTrue(++n<200);n=0;while(receipts.projectNext().isPresent())assertTrue(++n<200);
    }
    @org.junit.jupiter.api.BeforeEach void onlyOneDue(){jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()+interval '1 day',lease_id=NULL,lease_until=NULL");}
    @Test void actualSixConfirmationsCreatesChainReadyWithoutFinancialBalance(){
        refresh(txid());assertEquals(6,jdbc.queryForObject("SELECT confirmations FROM bitcoin_transaction_observations WHERE id=?",Long.class,observation(txid())));
        assertEquals("CHAIN_READY",reason(txid()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
    }
    @Test void actualImmatureCoinbaseRemainsHeld(){
        String coinbase=System.getenv("CEX_BITCOIN_TEST_COINBASE_TX");refresh(coinbase);assertEquals("IMMATURE_COINBASE",reason(coinbase));
        assertTrue(jdbc.queryForObject("SELECT coinbase FROM bitcoin_transaction_observations WHERE id=?",Boolean.class,observation(coinbase)));
    }
    @Test void actualOrdinaryDepositBelowThresholdWaits(){
        String recent=System.getenv("CEX_BITCOIN_TEST_REORG_TX");refresh(recent);assertEquals("WAITING_CONFIRMATIONS",reason(recent));
        assertTrue(jdbc.queryForObject("SELECT confirmations<6 FROM bitcoin_transaction_observations WHERE id=?",Boolean.class,observation(recent)));
    }
    @Test void sameCurrentSourceAndReasonDoesNotDuplicateDecision(){
        refresh(txid());long before=head(txid()),source=observation(txid());
        assertEquals(0,new TransactionTemplate(manager).<Integer>execute(t->qualification.evaluate(source)).intValue());assertEquals(0,new TransactionTemplate(manager).<Integer>execute(t->qualification.evaluate(source)).intValue());assertEquals(before,head(txid()));
    }
    @Test void mismatchedAddressScriptAmountIndexAndDuplicateOutputNeverBecomeReady(){
        var real=reader.inspect(txid());var original=real.outputs().getFirst();
        var wrong=List.of(new BitcoinDepositReader.Output(original.vout(),"different",original.scriptPubKey(),original.amount()),
            new BitcoinDepositReader.Output(original.vout(),original.address(),"0014"+"ab".repeat(20),original.amount()),
            new BitcoinDepositReader.Output(original.vout(),original.address(),original.scriptPubKey(),original.amount().add(new java.math.BigDecimal("0.00000001"))),
            new BitcoinDepositReader.Output(original.vout()+1,original.address(),original.scriptPubKey(),original.amount()));
        var variants=new ArrayList<List<BitcoinDepositReader.Output>>();for(var output:wrong)variants.add(List.of(output));variants.add(List.of(original,original));
        for(var outputs:variants){
            var changed=new BitcoinDepositReader.Observation(real.network(),real.txid(),real.status(),real.confirmations(),real.coinbase(),real.blockHash(),real.blockHeight(),real.tipHash(),real.tipHeight(),outputs);
            doReturn(changed).when(reader).inspect(txid());refresh(txid());assertEquals("OUTPUT_MISMATCH",reason(txid()));
        }
    }
    @Test void staleEvidenceCreatesNewHoldAndDatabaseCannotForceReady(){
        refresh(txid());long original=head(txid()),source=observation(txid());UUID receipt=receipt(txid());
        long stale=new TransactionTemplate(manager).execute(t->{
            long id=jdbc.queryForObject("""
                INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at)
                 SELECT network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,clock_timestamp()-interval '3 minutes'
                  FROM bitcoin_transaction_observations WHERE id=? RETURNING id
                """,Long.class,source);
            jdbc.update("UPDATE bitcoin_observation_heads SET observation_id=? WHERE txid=?",id,txid());qualification.evaluate(id);return id;
        });
        assertEquals("STALE_OBSERVATION",reason(txid()));assertTrue(head(txid())>original);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_deposit_chain_decisions(receipt_id,observation_id,policy_id,reason) VALUES(?,?,'BTC_CHAIN_V1','CHAIN_READY')",receipt,stale));
    }
    @Test void databaseRejectsReadyOnImmatureCoinbaseAndForeignObservation(){
        String coinbase=System.getenv("CEX_BITCOIN_TEST_COINBASE_TX");refresh(coinbase);long source=observation(coinbase);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_deposit_chain_decisions(receipt_id,observation_id,policy_id,reason) VALUES(?,?,'BTC_CHAIN_V1','CHAIN_READY')",receipt(coinbase),source));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_deposit_chain_decisions(receipt_id,observation_id,policy_id,reason) VALUES(?,?,'BTC_CHAIN_V1','WAITING_CONFIRMATIONS')",receipt(txid()),source));
    }
    @Test void qualificationAuditFailureRollsBackDecisionAndJobButPreservesNodeObservation(){
        refresh(txid());long before=head(txid()),source=observation(txid()),count=jdbc.queryForObject("SELECT count(*) FROM bitcoin_transaction_observations",Long.class);
        doThrow(new IllegalStateException("격리 판단 감사 장애")).when(audit).record(isNull(),eq("BTC_DEPOSIT_CHAIN_EVALUATED"),anyString(),anyString(),anyString());
        jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE txid=?",txid());assertThrows(IllegalStateException.class,jobs::refreshDue);
        assertEquals(before,head(txid()));assertEquals(source,observation(txid()));assertEquals(count+1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_transaction_observations",Long.class));
    }
    @Test void originalDecisionsAndCurrentHeadCannotBeChangedDeletedOrReactivated(){
        refresh(txid());long old=head(txid());refresh(txid());long current=head(txid());assertTrue(current>old);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE bitcoin_deposit_chain_heads SET decision_id=? WHERE receipt_id=?",old,receipt(txid())));
        for(String table:List.of("bitcoin_deposit_chain_decisions","bitcoin_deposit_chain_heads")){
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("DELETE FROM "+table));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE "+table+" CASCADE"));
        }
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("UPDATE bitcoin_deposit_chain_decisions SET reason='CHAIN_READY'"));assertEquals(current,head(txid()));
    }
    @Test void supersededObservationIsNotCurrentAndCallerMustOwnTransaction(){
        refresh(txid());long oldSource=observation(txid());refresh(txid());long current=head(txid());
        assertEquals(0,new TransactionTemplate(manager).<Integer>execute(t->qualification.evaluate(oldSource)).intValue());assertEquals(current,head(txid()));
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->qualification.evaluate(observation(txid())));
    }
}
