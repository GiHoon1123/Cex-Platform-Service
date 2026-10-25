package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.travelrule.*;
import dustin.cex.domains.security.AuditService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 각 메서드를 별도 DB에서 실행해 불변 금융 이력을 삭제하지 않는다. 합성 체인 근거에서 보류 원장→예약→실행을 확인한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+")
class CodeVaspPendingReservationIntegrationTest {
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
        r.add("customer-mail.dispatch-enabled",()->false);r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-pending-search-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");
    }
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    @MockitoBean DiditClient didit;
    @MockitoBean CodeVaspDirectoryClient client;
    @MockitoBean CodeVaspTransactionClient transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @Autowired AuditService audit;
    @Autowired dustin.cex.domains.auth.repository.UserRepository users;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinPendingDepositService pending;
    CodeVaspSettings config;CodeVaspDirectoryService directory;CodeVaspDepositSearches searches;CodeVaspDepositSearchExecution execution;CodeVaspDepositSearchProcessing processing;BitcoinDepositFixtures.Seed receipt;
    final String key=Base64.getEncoder().encodeToString(new byte[32]);
    @BeforeEach void seed(){
        // 실행기가 별도 DB를 제공했는지 먼저 검사한다. 이전 시험의 이력을 지우고 빈 상태로 가장하지 않는다.
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_deposit_receipts",Long.class));
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@example.test").passwordHash("격리 미사용 해시").build());
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),"bcrt1q"+BitcoinDepositFixtures.hex().substring(0,38),"0014"+BitcoinDepositFixtures.hex().substring(0,40));
        receipt=BitcoinDepositFixtures.seed(jdbc,json,user.getId(),Instant.now());
        config=new CodeVaspSettings("http://127.0.0.1:1","http://127.0.0.1:2","pending-"+UUID.randomUUID(),key);
        directory=directory(config);searches=new CodeVaspDepositSearches(directory,jdbc,audit,manager);execution=new CodeVaspDepositSearchExecution(searches,transactions,jdbc,audit,json,manager);processing=new CodeVaspDepositSearchProcessing(directory,execution,jdbc,searches);
        doAnswer(i->new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of())).when(client).read();directory.refresh();ledger.initializeOpening();
    }
    CodeVaspDirectoryService directory(CodeVaspSettings settings){return new CodeVaspDirectoryService(settings,client,jdbc,json,audit,manager);}
    void hold(BitcoinDepositFixtures.Seed source){BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,source,false);assertTrue(pending.recognize(source.id()).created());}
    @AfterEach void noSpendableCredit(){verifyNoInteractions(engine,producer);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));}
    long searches(){return jdbc.queryForObject("SELECT count(*) FROM codevasp_deposit_searches",Long.class);}
    void intactPending(int n){assertEquals(n,jdbc.queryForObject("SELECT count(*) FROM bitcoin_pending_deposits",Long.class));assertEquals(n*2,jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE bucket IN ('PENDING','CUSTODY')",Long.class));assertEquals(0,jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE asset='BTC'",java.math.BigDecimal.class).signum());}

    @Test void unrecognizedReceiptIsNotAutomaticallyReservedButRecognizedPendingIs(){
        assertTrue(searches.reserveNextPending().isEmpty());hold(receipt);var result=searches.reserveNextPending().orElseThrow();assertEquals(receipt.id(),result.receiptId());assertEquals(receipt.txid(),result.request().txid());assertTrue(searches.reserveNextPending().isEmpty());assertEquals(1,searches());intactPending(1);verifyNoInteractions(transactions);
    }
    @Test void coinbaseIsPreservedForReviewWithoutOriginatingVaspLookup(){
        new TransactionTemplate(manager).executeWithoutResult(t->{
            String outputs=json.writeValueAsString(List.of(new BitcoinDepositReader.Output(0,receipt.address(),receipt.script(),new java.math.BigDecimal("0.12345678"))));
            long observation=jdbc.queryForObject("INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at) VALUES('bitcoin-regtest','cex_watch',?,'CONFIRMED',101,true,?,0,?,100,?::jsonb,clock_timestamp()) RETURNING id",Long.class,receipt.txid(),BitcoinDepositFixtures.hex(),BitcoinDepositFixtures.hex(),outputs);
            jdbc.update("INSERT INTO bitcoin_observation_wallet_debits(observation_id,wallet_debit) VALUES(?,false)",observation);jdbc.update("INSERT INTO bitcoin_observation_heads(network,txid,observation_id) VALUES('bitcoin-regtest',?,?)",receipt.txid(),observation);qualification.evaluate(observation);
        });pending.recognize(receipt.id());assertTrue(searches.reserveNextPending().isEmpty());assertTrue(processing.processNext().isEmpty());assertEquals(0,searches());intactPending(1);verifyNoInteractions(transactions);
    }
    @Test void twoPendingReceiptsAreReservedOnceInDurableArrivalOrder(){
        hold(receipt);var second=BitcoinDepositFixtures.seed(jdbc,json,receipt.owner(),Instant.now());hold(second);var one=searches.reserveNextPending().orElseThrow();var two=searches.reserveNextPending().orElseThrow();assertEquals(receipt.id(),one.receiptId());assertEquals(second.id(),two.receiptId());assertNotEquals(one.id(),two.id());assertTrue(searches.reserveNextPending().isEmpty());intactPending(2);verifyNoInteractions(transactions);
    }
    @Test void concurrentAutomaticReservationCreatesOneStableRequestAndAudit()throws Exception{
        hold(receipt);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Optional<CodeVaspDepositSearches.Search>> call=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return searches.reserveNextPending();};var a=pool.submit(call);var b=pool.submit(call);start.countDown();var ids=new HashSet<UUID>();a.get(10,TimeUnit.SECONDS).ifPresent(v->ids.add(v.id()));b.get(10,TimeUnit.SECONDS).ifPresent(v->ids.add(v.id()));assertEquals(1,ids.size());
        }assertEquals(1,searches());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='CODE_DEPOSIT_SEARCH_RESERVED'",Long.class));intactPending(1);verifyNoInteractions(transactions);
    }
    @Test void reservationAuditFailureKeepsPendingAndHasNoExternalSubmission(){
        hold(receipt);var failing=mock(AuditService.class);doThrow(new IllegalStateException("격리 예약 감사 실패")).when(failing).record(any(),anyString(),anyString(),anyString(),anyString());var failed=new CodeVaspDepositSearches(directory,jdbc,failing,manager);assertThrows(IllegalStateException.class,failed::reserveNextPending);assertEquals(0,searches());intactPending(1);assertTrue(searches.reserveNextPending().isPresent());verifyNoInteractions(transactions);
    }
    @Test void failedCurrentDirectoryCannotReserveOrDiscardPending(){
        hold(receipt);doThrow(new IllegalStateException("격리 목록 장애")).when(client).read();directory.refresh();assertEquals(503,assertThrows(ResponseStatusException.class,searches::reserveNextPending).getStatusCode().value());assertEquals(503,assertThrows(ResponseStatusException.class,processing::processNext).getStatusCode().value());assertEquals(0,searches());intactPending(1);verifyNoInteractions(transactions);
    }
    @Test void newPendingReservationFailureCannotBlockExistingIndependentPoll(){
        var known=searches.reserve(receipt.id());new TransactionTemplate(manager).executeWithoutResult(t->{long attempt=jdbc.queryForObject("INSERT INTO codevasp_search_attempts(search_id,phase,started_at) VALUES(?,'SUBMIT',clock_timestamp()-interval '181 seconds') RETURNING id",Long.class,known.id());jdbc.update("INSERT INTO codevasp_search_heads(search_id,attempt_id) VALUES(?,?)",known.id(),attempt);});
        var another=BitcoinDepositFixtures.seed(jdbc,json,receipt.owner(),Instant.now());hold(another);doThrow(new IllegalStateException("격리 목록 장애")).when(client).read();directory.refresh();when(transactions.result(known.id())).thenReturn(new CodeVaspTransactionClient.Result(known.id(),CodeVaspTransactionClient.Status.FOUND,"origin",List.of(new CodeVaspTransactionClient.Analysis("origin","FOUND"))));
        assertEquals(known.id(),processing.processNext().orElseThrow().searchId());assertEquals(1,searches());intactPending(1);verify(transactions,never()).submit(any());
    }
    @Test void ownedWorkerAutomaticallyReservesPendingAndPersistsAcknowledgement(){
        hold(receipt);
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.getEnvironment().setActiveProfiles("production");context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("격리 자동 보류 연결",Map.of("codevasp.processing.enabled","true")));
            context.registerBean("codeVaspSchemaInitializer",Object.class,Object::new);context.registerBean(CodeVaspSettings.class,()->config);context.registerBean(CodeVaspDepositSearchProcessing.class,()->processing);context.register(CodeVaspDepositSearchWorker.class);context.refresh();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(()->{assertEquals(1,searches());UUID id=jdbc.queryForObject("SELECT id FROM codevasp_deposit_searches",UUID.class);assertEquals("SUBMIT_ACKNOWLEDGED",execution.current(id).status());});
        }intactPending(1);verify(transactions,times(1)).submit(any());verify(transactions,never()).result(any());
    }
    @Test void existingReservationSurvivesNewServiceInstanceWithoutAnotherAutomaticIdentity(){
        hold(receipt);var known=searches.reserve(receipt.id());var restarted=new CodeVaspDepositSearches(directory,jdbc,audit,manager);assertTrue(restarted.reserveNextPending().isEmpty());assertEquals(known,restarted.get(known.id()));assertEquals(known.id(),processing.processNext().orElseThrow().searchId());assertEquals(1,searches());intactPending(1);
    }
    @Test void ambientTransactionCannotAutomaticallyReserveBeforeProviderIo(){
        hold(receipt);assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->searches.reserveNextPending()));assertEquals(0,searches());intactPending(1);verifyNoInteractions(transactions);
    }
    @Test void testChainPendingCannotBeAutomaticallySentToExternalProviderOrigin(){
        hold(receipt);var external=directory(new CodeVaspSettings("https://example.invalid","http://127.0.0.1:2","external",key));external.refresh();var different=new CodeVaspDepositSearches(external,jdbc,audit,manager);assertTrue(different.reserveNextPending().isEmpty());assertEquals(0,searches());intactPending(1);verifyNoInteractions(transactions);
    }
    @Test void technicalLookupDoesNotDeclareLaterStaleChainEvidenceReadyForSpending(){
        hold(receipt);BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,true);assertNotEquals("CHAIN_READY",jdbc.queryForObject("SELECT d.reason FROM bitcoin_deposit_chain_heads h JOIN bitcoin_deposit_chain_decisions d ON d.id=h.decision_id WHERE h.receipt_id=?",String.class,receipt.id()));assertEquals("SUBMIT_ACKNOWLEDGED",processing.processNext().orElseThrow().status());assertEquals(1,searches());intactPending(1);verify(transactions).submit(any());
    }
}
