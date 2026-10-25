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

/** 실제 노드의 새 읽기와 보류 원장의 독립 대사 및 실패 시 증거 보존을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class BitcoinDepositReconciliationIntegrationTest {
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
    @Autowired BitcoinDepositReconciliationService reconciliation;
    String ordinary(){return System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX");}
    UUID id(String tx){return jdbc.queryForObject("SELECT id FROM bitcoin_deposit_receipts WHERE txid=?",UUID.class,tx);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    @org.junit.jupiter.api.BeforeAll void sources(){
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email("receipt-audit@example.test").passwordHash("격리 시험의 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),System.getenv("CEX_BITCOIN_TEST_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SCRIPT"));
        int n=0;while(scans.scanNext().isPresent())assertTrue(++n<200);n=0;while(receipts.projectNext().isPresent())assertTrue(++n<200);
        ledger.initializeOpening();
        jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()+interval '1 day'");
        jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE txid=?",ordinary());
        assertTrue(jobs.refreshDue());assertTrue(pending.recognize(id(ordinary())).created());
    }
    @org.junit.jupiter.api.BeforeEach void isolate(){clearInvocations(engine);}
    @org.junit.jupiter.api.AfterEach void financialFactsStayUntouched(){assertEquals(1,count("bitcoin_pending_deposits"));assertEquals(0,count("user_balances"));verifyNoInteractions(engine);}
    @Test void actualRecognizedDepositMatchesAndRepeatedAuditKeepsSeparateImmutableHistory()throws Exception{
        long entries=count("ledger_entries");
        doAnswer(invocation->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return invocation.callRealMethod();}).when(reader).inspect(ordinary());
        var first=reconciliation.reconcile(id(ordinary()));var second=reconciliation.reconcile(id(ordinary()));
        assertEquals(BitcoinDepositReconciliationService.Status.MATCHED,first.status());assertNotEquals(first.id(),second.id());assertTrue(first.issues().isEmpty());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=?",Long.class,first.id()));
        for(var row:jdbc.queryForList("SELECT payload,payload_hash FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=?",first.id())){
            var digest=java.security.MessageDigest.getInstance("SHA-256").digest(((String)row.get("payload")).getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals(HexFormat.of().formatHex(digest),row.get("payload_hash"));
        }
        assertEquals("PENDING_RECEIPT",jdbc.queryForObject("SELECT scope FROM bitcoin_deposit_reconciliation_runs WHERE id=?",String.class,first.id()));assertEquals(entries,count("ledger_entries"));
    }
    @Test void eligibleUnrecognizedExternalOutputIsIncompleteInsteadOfInventingFinancialEntries(){
        var result=reconciliation.reconcile(id(System.getenv("CEX_BITCOIN_TEST_REORG_TX")));assertEquals(BitcoinDepositReconciliationService.Status.INCOMPLETE,result.status());assertEquals("PENDING_RECOGNITION_MISSING",result.issues().getFirst().code());
    }
    @Test void internalWalletOutputIsHeldRatherThanCreditedAsExternalDeposit(){
        var result=reconciliation.reconcile(id(System.getenv("CEX_BITCOIN_TEST_INTERNAL_TX")));assertEquals(BitcoinDepositReconciliationService.Status.HELD,result.status());assertTrue(result.issues().stream().anyMatch(i->i.code().equals("NODE_WALLET_DEBIT")));
    }
    BitcoinDepositReader.Observation changed(BitcoinDepositReader.Observation real,boolean held,List<BitcoinDepositReader.Output> outputs){return new BitcoinDepositReader.Observation(real.network(),real.txid(),held?BitcoinDepositReader.Status.UNCONFIRMED:real.status(),held?0:real.confirmations(),real.coinbase(),real.walletDebit(),held?null:real.blockHash(),held?null:real.blockHeight(),real.tipHash(),real.tipHeight(),outputs);}
    @Test void differentOutputAmountIsMismatchWithoutRepairingTheLedger(){
        var real=reader.inspect(ordinary());var original=real.outputs().getFirst();var wrong=new BitcoinDepositReader.Output(original.vout(),original.address(),original.scriptPubKey(),original.amount().add(java.math.BigDecimal.ONE));
        doReturn(changed(real,false,List.of(wrong))).when(reader).inspect(ordinary());var result=reconciliation.reconcile(id(ordinary()));assertEquals(BitcoinDepositReconciliationService.Status.MISMATCH,result.status());assertEquals("NODE_OUTPUT_MISMATCH",result.issues().getFirst().code());
    }
    @Test void laterChainHoldOfRecognizedAssetIsMismatchWithoutDeletingOriginalFact(){
        var real=reader.inspect(ordinary());doReturn(changed(real,true,real.outputs())).when(reader).inspect(ordinary());var result=reconciliation.reconcile(id(ordinary()));assertEquals(BitcoinDepositReconciliationService.Status.MISMATCH,result.status());assertEquals("NODE_CHAIN_NOT_READY",result.issues().getFirst().code());
    }
    @Test void failedNodeReadCannotBecomeMatchedOrLeakExceptionText(){
        String secret="격리비밀노출금지";doThrow(new IllegalStateException(secret)).when(reader).inspect(ordinary());var result=reconciliation.reconcile(id(ordinary()));assertEquals(BitcoinDepositReconciliationService.Status.FAILED,result.status());assertEquals("NODE_UNAVAILABLE",result.issues().getFirst().code());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=?",Long.class,result.id()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_reconciliation_issues WHERE expected LIKE ? OR actual LIKE ?",Long.class,"%"+secret+"%","%"+secret+"%"));
    }
    @Test void completedAuditEvidenceAndResultsRejectMutationAndLateEvidence(){
        var result=reconciliation.reconcile(id(ordinary()));
        for(String sql:List.of("UPDATE bitcoin_deposit_reconciliation_runs SET status='FAILED' WHERE id='"+result.id()+"'","DELETE FROM bitcoin_deposit_reconciliation_runs","TRUNCATE bitcoin_deposit_reconciliation_runs CASCADE","UPDATE bitcoin_deposit_reconciliation_evidence SET payload=payload","DELETE FROM bitcoin_deposit_reconciliation_evidence","TRUNCATE bitcoin_deposit_reconciliation_evidence","TRUNCATE bitcoin_deposit_reconciliation_issues"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_deposit_reconciliation_issues(run_id,code,expected,actual) VALUES(?,'LATE','x','y')",result.id()));
    }
    @Test void finalAuditFailureLeavesRunningPlatformEvidenceInsteadOfFalseSuccess(){
        long before=count("bitcoin_deposit_reconciliation_runs");doThrow(new IllegalStateException("격리 감사 장애")).when(audit).record(isNull(),eq("BTC_DEPOSIT_RECONCILIATION_FINISHED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->reconciliation.reconcile(id(ordinary())));assertEquals(before+1,count("bitcoin_deposit_reconciliation_runs"));
        UUID run=jdbc.queryForObject("SELECT id FROM bitcoin_current_deposit_reconciliation WHERE status='RUNNING' ORDER BY start_position DESC LIMIT 1",UUID.class);assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=? AND kind='PLATFORM'",Long.class,run));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=? AND kind='NODE'",Long.class,run));
    }
    @Test void startAuditFailureRollsBackRunAndEvidenceBeforeNodeRead(){
        long before=count("bitcoin_deposit_reconciliation_runs");clearInvocations(reader);doThrow(new IllegalStateException("격리 감사 장애")).when(audit).record(isNull(),eq("BTC_DEPOSIT_RECONCILIATION_STARTED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->reconciliation.reconcile(id(ordinary())));assertEquals(before,count("bitcoin_deposit_reconciliation_runs"));verifyNoInteractions(reader);
    }
    @Test void nullMissingAndAmbientTransactionAreRejectedBeforeNodeRead(){
        clearInvocations(reader);assertThrows(IllegalArgumentException.class,()->reconciliation.reconcile(null));assertEquals(404,assertThrows(ResponseStatusException.class,()->reconciliation.reconcile(UUID.randomUUID())).getStatusCode().value());assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->reconciliation.reconcile(id(ordinary()))));verifyNoInteractions(reader);
    }
    @Test void databaseRejectsWrongSourceScopeAndEvidenceHash(){
        UUID run=UUID.randomUUID();UUID receipt=id(ordinary());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_deposit_reconciliation_runs(id,receipt_id,network,wallet,scope,snapshot_at,status) VALUES(?,?,'bitcoin-mainnet','cex_watch','PENDING_RECEIPT',clock_timestamp(),'RUNNING')",run,receipt));
        new TransactionTemplate(manager).executeWithoutResult(t->{
            jdbc.update("INSERT INTO bitcoin_deposit_reconciliation_runs(id,receipt_id,network,wallet,scope,snapshot_at,status) VALUES(?,?,'bitcoin-regtest','cex_watch','PENDING_RECEIPT',clock_timestamp(),'RUNNING')",run,receipt);
            audit.record(null,"BTC_DEPOSIT_RECONCILIATION_STARTED","bitcoin-reconciliation:"+run,"SUCCESS","RECEIPT:"+receipt);
        });
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_deposit_reconciliation_evidence(run_id,kind,payload_hash,payload) VALUES(?,'PLATFORM',?,'{}')",run,"0".repeat(64)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=?",Long.class,run));
    }

    @Test void missingDuplicateAddressAndScriptMismatchNeverPassAsMatched(){
        var real=reader.inspect(ordinary());var output=real.outputs().getFirst();
        var wrongAddress=new BitcoinDepositReader.Output(output.vout(),"다른 주소",output.scriptPubKey(),output.amount());
        var wrongScript=new BitcoinDepositReader.Output(output.vout(),output.address(),"0014"+"0".repeat(40),output.amount());
        for(List<BitcoinDepositReader.Output> outputs:List.of(List.<BitcoinDepositReader.Output>of(),List.of(output,output),List.of(wrongAddress),List.of(wrongScript))){
            doReturn(changed(real,false,outputs)).when(reader).inspect(ordinary());assertEquals(BitcoinDepositReconciliationService.Status.MISMATCH,reconciliation.reconcile(id(ordinary())).status());
        }
    }
    @Test void differentTransactionIdentityCannotSupplyEvidenceForThisReceipt(){
        var real=reader.inspect(ordinary());var changed=new BitcoinDepositReader.Observation(real.network(),"0".repeat(64),real.status(),real.confirmations(),real.coinbase(),real.walletDebit(),real.blockHash(),real.blockHeight(),real.tipHash(),real.tipHeight(),real.outputs());
        doReturn(changed).when(reader).inspect(ordinary());var result=reconciliation.reconcile(id(ordinary()));assertEquals(BitcoinDepositReconciliationService.Status.FAILED,result.status());assertEquals("NODE_IDENTITY_MISMATCH",result.issues().getFirst().code());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=?",Long.class,result.id()));
    }

}
