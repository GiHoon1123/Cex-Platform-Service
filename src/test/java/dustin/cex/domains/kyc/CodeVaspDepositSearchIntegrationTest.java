package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
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

/** 불변 입금 원본과 영속 요청 ID의 결합만 실제 PG에서 검증한다. 합성 자료를 실제 노드 관찰이나 금융 승인으로 사용하지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+")
class CodeVaspDepositSearchIntegrationTest {
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
        r.add("customer-mail.dispatch-enabled",()->false);r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-code-search-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");
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
    CodeVaspSettings config;CodeVaspDirectoryService directory;CodeVaspDepositSearches searches;BitcoinDepositFixtures.Seed receipt;
    final String key=Base64.getEncoder().encodeToString(new byte[32]);
    CodeVaspDirectoryService directory(CodeVaspSettings settings){return new CodeVaspDirectoryService(settings,client,jdbc,json,audit,manager);}
    @BeforeEach void seed(){
        var user=users.saveAndFlush(dustin.cex.domains.auth.model.entity.User.builder().email(UUID.randomUUID()+"@example.test").passwordHash("격리 미사용 해시").build());
        // 어휘가 맞는 합성 주소이며 체크섬/실제 키 보유를 증명하지 않는다. 기존 입금 원본 fixture 제약을 그대로 사용한다.
        jdbc.update("INSERT INTO bitcoin_deposit_addresses(id,user_id,network,wallet,address,script_pub_key) VALUES(?,?,'bitcoin-regtest','cex_watch',?,?)",UUID.randomUUID(),user.getId(),"bcrt1q"+BitcoinDepositFixtures.hex().substring(0,38),"0014"+BitcoinDepositFixtures.hex().substring(0,40));
        receipt=BitcoinDepositFixtures.seed(jdbc,json,user.getId(),Instant.now());
        config=new CodeVaspSettings("http://127.0.0.1:1","http://127.0.0.1:2","deposit-"+UUID.randomUUID(),key);
        directory=directory(config);searches=new CodeVaspDepositSearches(directory,jdbc,audit,manager);
        doAnswer(i->new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of())).when(client).read();directory.refresh();
    }
    @AfterEach void noFinancialOrExternalSearch(){verifyNoInteractions(transactions,engine,producer);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));}
    long count(){return jdbc.queryForObject("SELECT count(*) FROM codevasp_deposit_searches WHERE receipt_id=?",Long.class,receipt.id());}
    @Test void reservationBindsStableRequestToImmutableReceiptWithoutExternalSubmission(){
        var stored=searches.reserve(receipt.id());assertEquals(receipt.id(),stored.receiptId());assertEquals(stored.id(),stored.request().requestId());assertEquals(receipt.txid(),stored.request().txid());assertEquals(receipt.address(),stored.request().beneficiaryAddress());assertEquals("bitcoin-regtest",stored.network());assertEquals(directory.current().attemptId(),stored.directoryAttemptId());assertEquals(stored,searches.get(stored.id()));assertEquals(1,count());assertFalse(stored.toString().contains(receipt.address()));
    }
    @Test void restartAndLaterDirectoryFailureDoNotReplaceAlreadyReservedIdentity(){
        var stored=searches.reserve(receipt.id());doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"격리 장애")).when(client).read();directory.refresh();
        var restarted=new CodeVaspDepositSearches(directory,jdbc,audit,manager);assertEquals(stored,restarted.reserve(receipt.id()));assertEquals(stored,restarted.get(stored.id()));assertEquals(1,count());
    }
    @Test void concurrentReservationCommitsOneIdentityAndOneMandatoryAudit()throws Exception{
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<CodeVaspDepositSearches.Search> call=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return searches.reserve(receipt.id());};var a=pool.submit(call);var b=pool.submit(call);start.countDown();var one=a.get(10,TimeUnit.SECONDS);assertEquals(one,b.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='CODE_DEPOSIT_SEARCH_RESERVED' AND target=?",Long.class,"codevasp-search:"+one.id()));
        }assertEquals(1,count());
    }
    @Test void newerDirectoryDoesNotRewriteOriginalReservationEvidence(){
        var stored=searches.reserve(receipt.id());directory.refresh();assertNotEquals(stored.directoryAttemptId(),directory.current().attemptId());assertEquals(stored,searches.reserve(receipt.id()));
    }
    @Test void distinctReceiptsHaveDistinctRequestsEvenWhenAddressIsShared(){
        var one=searches.reserve(receipt.id());var second=BitcoinDepositFixtures.seed(jdbc,json,receipt.owner(),Instant.now());var two=searches.reserve(second.id());assertNotEquals(one.id(),two.id());assertEquals(one.request().beneficiaryAddress(),two.request().beneficiaryAddress());assertNotEquals(one.request().txid(),two.request().txid());
    }
    @Test void changedAuthorityCannotReadOldRequestAndNeedsItsOwnFreshDirectory(){
        var old=searches.reserve(receipt.id());var changed=directory(new CodeVaspSettings("http://127.0.0.1:3","http://127.0.0.1:2",configEntity(),key));var other=new CodeVaspDepositSearches(changed,jdbc,audit,manager);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->other.get(old.id())).getStatusCode().value());assertEquals(503,assertThrows(ResponseStatusException.class,()->other.reserve(receipt.id())).getStatusCode().value());changed.refresh();var next=other.reserve(receipt.id());assertNotEquals(old.id(),next.id());assertEquals(2,count());
    }
    String configEntity(){return "changed-"+UUID.randomUUID();}
    @Test void failedCurrentDirectoryAndMissingReceiptCannotCreateReservation(){
        assertEquals(404,assertThrows(ResponseStatusException.class,()->searches.reserve(UUID.randomUUID())).getStatusCode().value());doThrow(new IllegalStateException("격리 외부 장애")).when(client).read();directory.refresh();assertEquals(503,assertThrows(ResponseStatusException.class,()->searches.reserve(receipt.id())).getStatusCode().value());assertEquals(0,count());
    }
    @Test void mandatoryAuditFailureRollsBackReservationBeforeAnySubmission(){
        var failing=mock(AuditService.class);doThrow(new IllegalStateException("격리 예약 감사 장애")).when(failing).record(any(),anyString(),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,()->new CodeVaspDepositSearches(directory,jdbc,failing,manager).reserve(receipt.id()));assertEquals(0,count());assertNotNull(searches.reserve(receipt.id()));assertEquals(1,count());
    }
    @Test void immutableReservationAndCurrentDirectoryTriggerRejectDirectBypass(){
        var stored=searches.reserve(receipt.id());for(String sql:List.of("UPDATE codevasp_deposit_searches SET id=id","DELETE FROM codevasp_deposit_searches","TRUNCATE codevasp_deposit_searches CASCADE"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));
        directory.refresh();var fresh=directory.current();
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_deposit_searches(id,receipt_id,source_id,directory_attempt_id) VALUES(?,?,?,?)",UUID.randomUUID(),receipt.id(),stored.sourceId(),stored.directoryAttemptId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_deposit_searches(id,receipt_id,source_id,directory_attempt_id) VALUES(?,?,?,?)",UUID.randomUUID(),UUID.randomUUID(),fresh.sourceId(),fresh.attemptId()));assertEquals(stored,searches.get(stored.id()));
    }
    @Test void futureRecordedDirectoryCannotBeUsedThroughDirectSqlBypass(){
        var valid=directory.current();
        long attempt=new TransactionTemplate(manager).execute(t->{
            long next=jdbc.queryForObject("INSERT INTO codevasp_directory_attempts(source_id) VALUES(?) RETURNING id",Long.class,valid.sourceId());
            jdbc.update("UPDATE codevasp_directory_heads SET attempt_id=? WHERE source_id=?",next,valid.sourceId());
            jdbc.update("INSERT INTO codevasp_directory_results(attempt_id,source_id,status,content_id,client_started_at,client_finished_at,recorded_at) VALUES(?,?,'SUCCESS',?,clock_timestamp(),clock_timestamp(),clock_timestamp()+interval '1 minute')",next,valid.sourceId(),valid.contentId());return next;
        });
        assertEquals(503,assertThrows(ResponseStatusException.class,()->searches.reserve(receipt.id())).getStatusCode().value());
        var error=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_deposit_searches(id,receipt_id,source_id,directory_attempt_id) VALUES(?,?,?,?)",UUID.randomUUID(),receipt.id(),valid.sourceId(),attempt));assertTrue(error.getMostSpecificCause().getMessage().contains("현재 성공"));assertEquals(0,count());
    }

    @Test void testChainCannotBeReservedAgainstExternalProviderOrigin(){
        var external=directory(new CodeVaspSettings("https://example.invalid","http://127.0.0.1:2","external-"+UUID.randomUUID(),key));external.refresh();var service=new CodeVaspDepositSearches(external,jdbc,audit,manager);
        var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->service.reserve(receipt.id()));assertTrue(failure.getMostSpecificCause().getMessage().contains("시험망"));assertEquals(0,count());
    }
    @Test void ambientTransactionAndDisabledSourceDoNotReserve(){
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->searches.reserve(receipt.id())));
        var disabled=new CodeVaspDepositSearches(directory(new CodeVaspSettings("","","","")),jdbc,audit,manager);assertEquals(503,assertThrows(ResponseStatusException.class,()->disabled.reserve(receipt.id())).getStatusCode().value());assertEquals(0,count());
    }
}
