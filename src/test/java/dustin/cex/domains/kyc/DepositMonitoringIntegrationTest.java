package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.monitoring.*;
import dustin.cex.domains.valuation.*;
import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 메서드별 새 PG에서 실제 수탁 원장·평가·작업·탐지의 결합을 검증한다. 합성 노드/가격은 실제 공급사 자료가 아니다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+")
class DepositMonitoringIntegrationTest {
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");r.add("customer-mail.dispatch-enabled",()->false);r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-monitoring-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");r.add("bitcoin.processing.enabled",()->false);r.add("valuation.upbit.enabled",()->true);}
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    @MockitoBean DiditClient didit;
    @MockitoBean BitcoinDepositReader reader;
    @MockitoBean BitcoinKrwPriceClient client;
    @MockitoSpyBean AuditService audit;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired BitcoinDepositValuationService valuations;
    @Autowired BitcoinSettings bitcoin;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinKrwPriceService prices;
    @Autowired DepositMonitoringQueue queue;
    @Autowired DepositMonitoringService monitoring;
    @Autowired DepositMonitoringReview review;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    long owner;
    @BeforeEach void original(){owner=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@test.invalid").passwordHash("isolated-monitoring-hash").build()).getId();ledger.initializeOpening();publish();}
    void publish(){doAnswer(call->{Instant now=Instant.now(),provider=Instant.ofEpochMilli(now.toEpochMilli());var price=new BigDecimal("1700000.00000000");String payload=json.writeValueAsString(List.of(Map.of("market","KRW-BTC","trade_price",price,"trade_timestamp",provider.toEpochMilli(),"timestamp",provider.toEpochMilli())));return new BitcoinKrwPriceClient.Quote(price,provider,provider,now,now,payload,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))));}).when(client).read();assertEquals("SUCCESS",prices.refresh().status());clearInvocations(client);}
    BitcoinDepositFixtures.Seed receipt(){var r=BitcoinDepositFixtures.seed(jdbc,json,owner,Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,r,false);return r;}
    BitcoinDepositFixtures.Seed deposit(){var r=receipt();assertTrue(pending.recognize(r.id()).created());return r;}
    Map<String,Object> job(){return jdbc.queryForMap("SELECT * FROM deposit_monitoring_jobs WHERE user_id=?",owner);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    DepositMonitoringReview.Summary summary(){return new org.springframework.transaction.support.TransactionTemplate(manager).execute(t->review.read(owner));}
    @AfterEach void noFinancialOrExternalMutation(){verifyNoInteractions(engine,producer,reader,client);assertEquals(0,count("user_balances"));assertEquals(0,jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE asset='BTC'",BigDecimal.class).signum());}
    @Test void oneRecognizedDepositHasDurableSourceAndCurrentMonitoringPosition(){deposit();var a=monitoring.processNext().orElseThrow();assertEquals("NO_SIGNAL",a.result().outcome());assertEquals(1,a.result().smallTransfers());assertEquals(0,new BigDecimal("209876.5260000000000000").compareTo(a.result().smallTotalKrw()));assertEquals(a.id(),summary().assessmentId());assertEquals(a.inputCursor(),((Number)job().get("observed_cursor")).longValue());assertNull(job().get("lease_id"));assertFalse(summary().newerInputsPending());assertEquals("ONE_RULE_REFERENCE_ONLY_NOT_FINANCIAL_PERMISSION",summary().assurance());assertEquals(1,count("deposit_monitoring_assessments"));}
    @Test void fiveDistinctSmallTransactionsCreateReviewSignalWithoutFinancialEffect(){for(int i=0;i<5;i++)deposit();var a=monitoring.processNext().orElseThrow();assertEquals("REVIEW_REQUIRED",a.result().outcome());assertEquals(5,a.result().smallTransfers());assertEquals(0,new BigDecimal("1049382.6300000000000000").compareTo(a.result().smallTotalKrw()));assertEquals(1,summary().historicalSignals());assertEquals("REPEATED_SMALL_REFERENCE_INFLOW",summary().reason());}
    @Test void oldMissingValuationIsNotRepairedWithCurrentPrice(){doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"격리 가격 실패")).when(client).read();assertEquals("FAILED",prices.refresh().status());clearInvocations(client);deposit();publish();var a=monitoring.processNext().orElseThrow();assertEquals("INCOMPLETE",a.result().outcome());assertEquals("VALUATION_UNAVAILABLE",a.result().reason());assertEquals(1,a.result().unknownTransfers());}
    @Test void unchangedInputDoesNotCreateDuplicateAssessmentsOrAudit(){deposit();var first=monitoring.processNext().orElseThrow();assertTrue(monitoring.processNext().isEmpty());assertEquals(1,count("deposit_monitoring_assessments"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='DEPOSIT_MONITORING_ASSESSED'",Long.class));assertEquals(first.id(),summary().assessmentId());}
    @Test void assessmentAuditFailurePreservesInputAndRetriesAfterLeaseExpiry(){deposit();doThrow(new IllegalStateException("격리 탐지 감사 실패")).when(audit).record(isNull(),eq("DEPOSIT_MONITORING_ASSESSED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,monitoring::processNext);assertEquals(0,count("deposit_monitoring_assessments"));assertEquals(0,count("deposit_monitoring_heads"));assertNotNull(job().get("lease_id"));assertTrue(monitoring.processNext().isEmpty());jdbc.update("UPDATE deposit_monitoring_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE user_id=?",owner);doCallRealMethod().when(audit).record(isNull(),eq("DEPOSIT_MONITORING_ASSESSED"),anyString(),anyString(),anyString());assertEquals("NO_SIGNAL",monitoring.processNext().orElseThrow().result().outcome());assertEquals(1,count("bitcoin_pending_deposits"));}
    @Test void concurrentProcessorsKeepOneAssessmentAndOneCurrentHead()throws Exception{deposit();var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){Callable<Optional<DepositMonitoringService.Assessment>> call=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return monitoring.processNext();};var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS).isPresent(),b.get(10,TimeUnit.SECONDS).isPresent());}assertEquals(1,count("deposit_monitoring_assessments"));assertEquals(1,count("deposit_monitoring_heads"));}
    @Test void incomingDepositDuringAssessmentIsNotLostFromQueue()throws Exception{
        deposit();var another=receipt();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return call.callRealMethod();}).when(audit).record(isNull(),eq("DEPOSIT_MONITORING_ASSESSED"),anyString(),anyString(),anyString());
        try(var pool=Executors.newFixedThreadPool(2)){var assessing=pool.submit(monitoring::processNext);assertTrue(entered.await(5,TimeUnit.SECONDS));var recognizing=pool.submit(()->pending.recognize(another.id()));org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%users%')",Boolean.class));release.countDown();var first=assessing.get(10,TimeUnit.SECONDS).orElseThrow();assertTrue(recognizing.get(10,TimeUnit.SECONDS).created());assertTrue(((Number)job().get("target_cursor")).longValue()>first.inputCursor());assertTrue(summary().newerInputsPending());doCallRealMethod().when(audit).record(isNull(),eq("DEPOSIT_MONITORING_ASSESSED"),anyString(),anyString(),anyString());assertTrue(monitoring.processNext().orElseThrow().inputCursor()>first.inputCursor());assertFalse(summary().newerInputsPending());}finally{release.countDown();}
    }
    @Test void assessmentAndFinancialPositionsCannotBeRewrittenOrTruncated(){deposit();monitoring.processNext();for(String sql:List.of("UPDATE deposit_monitoring_assessments SET reason='VALUATION_UNAVAILABLE'","DELETE FROM deposit_monitoring_assessments","TRUNCATE deposit_monitoring_assessments","DELETE FROM deposit_monitoring_heads","TRUNCATE deposit_monitoring_jobs"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));assertEquals(1,count("deposit_monitoring_assessments"));}
    @Test void sqlCannotSubstituteDifferentFinancialSourceOrDropPartOfWindow(){
        deposit();deposit();var a=monitoring.processNext().orElseThrow();String original=jdbc.queryForObject("SELECT source_document FROM deposit_monitoring_assessments WHERE id=?",String.class,a.id());var nodes=json.readTree(original);((tools.jackson.databind.node.ObjectNode)nodes.get(0)).put("krwValue",1);String forged=json.writeValueAsString(nodes);
        var wrong=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO deposit_monitoring_assessments(id,user_id,input_cursor,policy_id,reference_at,outcome,reason,small_transfers,small_total_krw,unknown_transfers,source_count,source_document,source_hash) SELECT ?,user_id,input_cursor,policy_id,reference_at,outcome,reason,small_transfers,small_total_krw,unknown_transfers,source_count,?,encode(sha256(convert_to(?,'UTF8')),'hex') FROM deposit_monitoring_assessments WHERE id=?",UUID.randomUUID(),forged,forged,a.id()));
        assertTrue(wrong.getMostSpecificCause().getMessage().contains("실제 불변 원본이 다릅니다"));
        var subset=(tools.jackson.databind.node.ArrayNode)json.readTree(original);subset.remove(0);String incomplete=json.writeValueAsString(subset);
        var missing=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO deposit_monitoring_assessments(id,user_id,input_cursor,policy_id,reference_at,outcome,reason,small_transfers,small_total_krw,unknown_transfers,source_count,source_document,source_hash) SELECT ?,user_id,input_cursor,policy_id,reference_at,outcome,reason,small_transfers,small_total_krw,unknown_transfers,source_count-1,?,encode(sha256(convert_to(?,'UTF8')),'hex') FROM deposit_monitoring_assessments WHERE id=?",UUID.randomUUID(),incomplete,incomplete,a.id()));
        assertTrue(missing.getMostSpecificCause().getMessage().contains("입력을 누락하거나 초과했습니다"));
    }
    @Test void noCustodyInputDoesNotInventMonitoringOrFinancialPermission(){assertTrue(monitoring.processNext().isEmpty());assertEquals("NOT_ASSESSED",summary().outcome());assertEquals("NO_CUSTODY_INPUT",summary().reason());assertEquals(0,count("deposit_monitoring_assessments"));}
    @Test void queueNeedsBusinessTransactionAndRejectsForeignOperation(){assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->queue.enqueue(owner,"missing"));assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->review.read(owner));assertThrows(RuntimeException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->queue.enqueue(owner,"missing")));assertEquals(0,count("deposit_monitoring_jobs"));}
    @Test void queueFailureRollsBackRecognitionButPreservesObservedReceipt(){var r=receipt();var broken=mock(DepositMonitoringQueue.class);doThrow(new IllegalStateException("격리 탐지 큐 실패")).when(broken).enqueue(anyLong(),anyString());var recognizer=new BitcoinPendingDepositService(jdbc,ledger,users,audit,bitcoin,valuations,broken,manager);assertThrows(IllegalStateException.class,()->recognizer.recognize(r.id()));assertEquals(0,count("bitcoin_pending_deposits"));assertEquals(0,count("bitcoin_deposit_valuations"));assertEquals(1,count("bitcoin_deposit_receipts"));assertEquals(0,count("deposit_monitoring_jobs"));}
    @Test void overLimitSnapshotRemainsIncompleteInsteadOfBecomingPartialNoSignal(){for(int i=0;i<1001;i++){if(i>0&&i%200==0)publish();deposit();}var a=monitoring.processNext().orElseThrow();assertEquals("INCOMPLETE",a.result().outcome());assertEquals("INPUT_LIMIT_EXCEEDED",a.result().reason());assertEquals(1001,jdbc.queryForObject("SELECT source_count FROM deposit_monitoring_assessments WHERE id=?",Integer.class,a.id()));}
    @Test void unqueuedSourceStaysExplicitlyUnassessedUntilRequested(){
        var r=receipt();var omitted=mock(DepositMonitoringQueue.class);var oldWriter=new BitcoinPendingDepositService(jdbc,ledger,users,audit,bitcoin,valuations,omitted,manager);oldWriter.recognize(r.id());
        assertEquals(0,count("deposit_monitoring_jobs"));assertTrue(monitoring.processNext().isEmpty());assertEquals("NOT_ASSESSED",summary().outcome());assertTrue(summary().newerInputsPending());
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->queue.enqueue(owner,"btc:pending:"+r.id()));assertEquals("NO_SIGNAL",monitoring.processNext().orElseThrow().result().outcome());assertEquals(1,count("bitcoin_pending_deposits"));
    }
}
