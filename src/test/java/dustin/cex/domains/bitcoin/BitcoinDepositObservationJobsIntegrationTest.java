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

/** 실제 입금 원본/노드/PG로 임대 재확인·만료 인수·늦은 확정·감사 원자성을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariables({
    @EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+"),
    @EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
})
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class BitcoinDepositObservationJobsIntegrationTest {
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
    @MockitoSpyBean BitcoinObservationService observations;
    @MockitoSpyBean AuditService audit;
    @Autowired BitcoinDepositObservationJobs jobs;
    @Autowired BitcoinScanService scans;
    @Autowired BitcoinDepositReceiptService receipts;
    @Autowired BitcoinSettings config;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    String txid(){return System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX");}
    Map<String,Object> state(){return jdbc.queryForMap("SELECT * FROM bitcoin_deposit_observation_jobs WHERE txid=?",txid());}
    void due(){jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE txid=?",txid());}
    @org.junit.jupiter.api.BeforeAll void sources(){
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email("observation-jobs@example.test").passwordHash("격리 시험의 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),System.getenv("CEX_BITCOIN_TEST_ADDRESS"),System.getenv("CEX_BITCOIN_TEST_SCRIPT"));
        int n=0;while(scans.scanNext().isPresent())assertTrue(++n<200);
        // 이전에 이미 확정된 입금과 처리 완료만 있고 관찰 작업은 없는 재시작 상태를 구성한다. 금융 원본을 삭제하지 않는다.
        new TransactionTemplate(manager).executeWithoutResult(t->{
            long source=jdbc.queryForObject("SELECT id FROM bitcoin_block_scans WHERE received @> jsonb_build_array(jsonb_build_object('txid',?::text))",Long.class,txid());
            jdbc.update("""
                INSERT INTO bitcoin_deposit_receipts(id,network,txid,vout,address_id,user_id,asset,amount,first_scan_id)
                 SELECT ?,s.network,o->>'txid',(o->>'vout')::integer,a.id,a.user_id,'BTC',(o->>'amount')::numeric,s.id
                  FROM bitcoin_block_scans s CROSS JOIN LATERAL jsonb_array_elements(s.received) o JOIN bitcoin_deposit_addresses a ON a.id=(o->>'addressId')::uuid
                  WHERE s.id=? AND o->>'txid'=?
                """,UUID.randomUUID(),source,txid());
            jdbc.update("INSERT INTO bitcoin_scan_receipt_projections(scan_id,output_count,new_count) VALUES(?,1,1)",source);
        });
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_observation_jobs",Long.class));jobs.seedKnownReceipts();
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_observation_jobs",Long.class));
        int completed=0;while(receipts.projectNext().isPresent())assertTrue(++completed<200);
        assertEquals(jdbc.queryForObject("SELECT count(DISTINCT txid) FROM bitcoin_deposit_receipts",Long.class),jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_observation_jobs",Long.class));
    }
    @org.junit.jupiter.api.BeforeEach void isolateDueWork(){
        // 운영 대기 상태만 바꿔 각 시험의 기한을 격리한다. 입금/관찰/감사 원본은 보존한다.
        jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()+interval '1 day',lease_id=NULL,lease_until=NULL");clearInvocations(observations);
    }
    @Test void idleAndStartupReseedDoNotRepeatNodeOrFinancialRecords(){
        long before=jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_observation_jobs",Long.class);jobs.seedKnownReceipts();assertFalse(jobs.refreshDue());
        verifyNoInteractions(observations);assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_observation_jobs",Long.class));
    }
    @Test void actualRefreshCommitsObservationJobPointerAuditAndCooldownWithoutCredit(){
        due();assertTrue(jobs.refreshDue());var saved=state();assertEquals("OBSERVED",saved.get("last_outcome"));assertNull(saved.get("lease_id"));assertEquals(0L,((Number)saved.get("failures")).longValue());
        assertEquals(saved.get("latest_observation_id"),jdbc.queryForObject("SELECT observation_id FROM bitcoin_observation_heads WHERE txid=?",Long.class,txid()));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT next_attempt_at>clock_timestamp()+interval '25 seconds' FROM bitcoin_deposit_observation_jobs WHERE txid=?",Boolean.class,txid())));
        assertFalse(jobs.refreshDue());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions",Long.class));
    }
    @Test void nodeFailureKeepsPreviousObservationAndDelaysOnlyThisJob(){
        due();assertTrue(jobs.refreshDue());Object original=state().get("latest_observation_id");due();long failures=((Number)state().get("failures")).longValue();
        doThrow(BitcoinRpcClient.unavailable()).when(observations).refresh(txid());assertTrue(jobs.refreshDue());
        var saved=state();assertEquals(original,saved.get("latest_observation_id"));assertEquals("NODE_UNAVAILABLE",saved.get("last_outcome"));assertEquals(failures+1,((Number)saved.get("failures")).longValue());assertNull(saved.get("lease_id"));assertFalse(jobs.refreshDue());
    }
    @Test void simultaneousClaimDoesNotHoldDatabaseTransactionDuringNodeRead()throws Exception{
        due();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return call.callRealMethod();}).when(observations).refresh(txid());
        try(var pool=Executors.newSingleThreadExecutor()){
            var first=pool.submit(jobs::refreshDue);assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{assertFalse(jobs.refreshDue());}finally{release.countDown();}assertTrue(first.get(10,TimeUnit.SECONDS));
        }
        verify(observations,times(1)).refresh(txid());
    }
    @Test void expiredLeaseCannotOverwriteNewerCompletedJob()throws Exception{
        due();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new AtomicBoolean(true);
        doAnswer(call->{var result=call.callRealMethod();if(first.getAndSet(false)){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}return result;}).when(observations).refresh(txid());
        try(var pool=Executors.newSingleThreadExecutor()){
            var old=pool.submit(jobs::refreshDue);assertTrue(entered.await(5,TimeUnit.SECONDS));Object oldLease=state().get("lease_id");
            try{
                jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE txid=?",txid());assertTrue(jobs.refreshDue());Object latest=state().get("latest_observation_id");
                release.countDown();assertTrue(old.get(10,TimeUnit.SECONDS));assertEquals(latest,state().get("latest_observation_id"));assertNull(state().get("lease_id"));
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE target=? AND outcome='DENIED' AND detail='LEASE_LOST'",Long.class,"bitcoin-observation-lease:"+oldLease));
            }finally{release.countDown();}
        }
    }
    @Test void claimedButAbandonedWorkCanBeResumedByFreshServiceAfterExpiry(){
        due();doThrow(new AssertionError("격리된 실행 중단")).when(observations).refresh(txid());assertThrows(AssertionError.class,jobs::refreshDue);assertNotNull(state().get("lease_id"));
        assertFalse(jobs.refreshDue());reset(observations);jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE txid=?",txid());
        var fresh=new BitcoinDepositObservationJobs(jdbc,config,observations,audit,manager,qualification);assertTrue(fresh.refreshDue());assertEquals("OBSERVED",state().get("last_outcome"));assertNull(state().get("lease_id"));
    }
    @Test void claimAuditFailureRollsBackLeaseBeforeNode(){
        due();doThrow(new IllegalStateException("격리 임대 감사 장애")).when(audit).record(isNull(),eq("BTC_DEPOSIT_OBSERVATION_CLAIMED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,jobs::refreshDue);assertNull(state().get("lease_id"));verifyNoInteractions(observations);
    }
    @Test void finishAuditFailureKeepsCommittedObservationAndLeaseForRecovery(){
        due();long before=jdbc.queryForObject("SELECT count(*) FROM bitcoin_transaction_observations",Long.class);
        doThrow(new IllegalStateException("격리 완료 감사 장애")).when(audit).record(isNull(),eq("BTC_DEPOSIT_OBSERVATION_FINISHED"),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,jobs::refreshDue);assertNotNull(state().get("lease_id"));assertEquals(before+1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_transaction_observations",Long.class));
        reset(audit);jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE txid=?",txid());assertTrue(jobs.refreshDue());assertNull(state().get("lease_id"));
    }
    @Test void ambientTransactionAndForgedOrDeletedJobsAreRejected(){
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->jobs.refreshDue()));verifyNoInteractions(observations);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_deposit_observation_jobs(network,wallet,txid) VALUES('bitcoin-regtest','cex_watch',?)","ee".repeat(32)));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("DELETE FROM bitcoin_deposit_observation_jobs"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute("TRUNCATE bitcoin_deposit_observation_jobs"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET wallet='other' WHERE txid=?",txid()));
    }
    @Test void existingObservationPointerCannotBeClearedOrMovedBackward(){
        due();assertTrue(jobs.refreshDue());long old=((Number)state().get("latest_observation_id")).longValue();due();assertTrue(jobs.refreshDue());long current=((Number)state().get("latest_observation_id")).longValue();assertTrue(current>old);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET latest_observation_id=? WHERE txid=?",old,txid()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET latest_observation_id=NULL WHERE txid=?",txid()));assertEquals(current,state().get("latest_observation_id"));
    }
    @Test void conflictAndUnexpectedFailureUseBoundedCodesAndDoNotStarveAnotherTransaction(){
        due();doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT)).when(observations).refresh(txid());assertTrue(jobs.refreshDue());assertEquals("OBSERVATION_CONFLICT",state().get("last_outcome"));
        due();doThrow(new IllegalStateException("기록하면 안 되는 원문")).when(observations).refresh(txid());assertTrue(jobs.refreshDue());assertEquals("INTERNAL_FAILURE",state().get("last_outcome"));
        String other=jdbc.queryForObject("SELECT txid FROM bitcoin_deposit_observation_jobs WHERE txid<>? LIMIT 1",String.class,txid());
        jdbc.update("UPDATE bitcoin_deposit_observation_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE txid=?",other);assertTrue(jobs.refreshDue());
        assertEquals("OBSERVED",jdbc.queryForObject("SELECT last_outcome FROM bitcoin_deposit_observation_jobs WHERE txid=?",String.class,other));
    }
}
