package dustin.cex.domains.travelrule;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.security.AuditService;
import java.time.*;
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
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 실제 PostgreSQL에서 불변 원본·현재 위치·감사 원자성과 동시 갱신을 검증한다. 공급사는 모의 클라이언트로 격리한다. */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches="jdbc:postgresql:.+")
class CodeVaspDirectoryIntegrationTest {
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("CEX_TEST_DATABASE_URL"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.datasource.username",()->"root");r.add("spring.datasource.password",()->"1234");r.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.PostgreSQLDialect");r.add("spring.jpa.hibernate.ddl-auto",()->"create-drop");
        r.add("customer-mail.dispatch-enabled",()->false);for(String key:List.of("rpc-url","rpc-user","rpc-password","wallet"))r.add("bitcoin."+key,()->"");
    }
    @MockitoBean dustin.cex.shared.http.EngineHttpClient engine;
    @MockitoBean dustin.cex.shared.kafka.KafkaEventProducer producer;
    @MockitoBean dustin.cex.domains.fee.service.FeeConfigService fees;
    @MockitoBean dustin.cex.domains.kyc.DiditClient didit;
    @MockitoBean CodeVaspDirectoryClient client;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper json;
    @Autowired AuditService audit;
    CodeVaspSettings config;
    CodeVaspDirectoryService service;
    final String key=Base64.getEncoder().encodeToString(new byte[32]);
    @BeforeEach void isolate(){
        config=new CodeVaspSettings("http://127.0.0.1:1","http://127.0.0.1:2","source-"+UUID.randomUUID(),key);
        service=service(config,audit);
        doAnswer(invocation->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return directory();}).when(client).read();
    }
    @AfterEach void noFinancialDelivery(){verifyNoInteractions(engine,producer);}
    CodeVaspDirectoryService service(CodeVaspSettings settings,AuditService recorder){return new CodeVaspDirectoryService(settings,client,jdbc,json,recorder,manager);}
    CodeVaspDirectoryClient.Directory directory(){return new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of(new CodeVaspDirectoryClient.Vasp("other","격리 사업자","격리 법인","KR","CODE",false,List.of(new CodeVaspDirectoryClient.Key(key,Instant.parse("2099-01-01T00:00:00Z"))))));}
    void unavailable(Runnable call){assertEquals(503,assertThrows(ResponseStatusException.class,call::run).getStatusCode().value());}
    long count(String table,String source){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE source_id=?",Long.class,source);}
    AuditService failedAudit(String action){var failed=mock(AuditService.class);doAnswer(i->{if(i.getArgument(1).equals(action))throw new IllegalStateException("격리 감사 장애");audit.record(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4));return null;}).when(failed).record(any(),anyString(),anyString(),anyString(),anyString());return failed;}

    @Test void startIsDurableBeforeIoAndPendingHeadCannotExposeOldSuccess(){
        var old=service.refresh();
        doAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());unavailable(service::current);assertEquals(2,count("codevasp_directory_attempts",old.sourceId()));return directory();}).when(client).read();
        var latest=service.refresh();var snapshot=service.current();assertEquals(latest.attemptId(),snapshot.attemptId());assertEquals("SUCCESS",latest.status());assertFalse(snapshot.directory().vasps().getFirst().reachable());
        assertEquals(2,count("codevasp_directory_results",old.sourceId()));
    }
    @Test void equalPayloadSharesContentButEachReadKeepsItsOwnAttemptAndAudit(){
        var a=service.refresh();var first=service.current();var b=service.refresh();var second=service.current();
        assertNotEquals(a.attemptId(),b.attemptId());assertEquals(first.contentId(),second.contentId());assertEquals(1,count("codevasp_directory_contents",a.sourceId()));
        assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE target=?",Long.class,"codevasp-directory:"+a.sourceId()));
    }
    @Test void latestProviderFailureBlocksEarlierSuccessAndDoesNotStoreException(){
        var a=service.refresh();doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"격리 비밀 원문")).when(client).read();
        var b=service.refresh();assertEquals("PROVIDER_UNAVAILABLE",b.failureCode());unavailable(service::current);
        assertEquals("SUCCESS",jdbc.queryForObject("SELECT status FROM codevasp_directory_results WHERE attempt_id=?",String.class,a.attemptId()));
        assertEquals(1,count("codevasp_directory_contents",a.sourceId()));
    }
    @Test void failedStartAuditRollsBackEverythingBeforeProviderIo(){
        long before=jdbc.queryForObject("SELECT count(*) FROM codevasp_directory_sources",Long.class);
        assertThrows(IllegalStateException.class,()->service(config,failedAudit("CODE_DIRECTORY_READ_STARTED")).refresh());
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM codevasp_directory_sources",Long.class));verifyNoInteractions(client);
    }
    @Test void failedFinishAuditLeavesPendingHeadAndRollsBackResultAndNewContent(){
        var a=service.refresh();doAnswer(i->new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of())).when(client).read();
        assertThrows(IllegalStateException.class,()->service(config,failedAudit("CODE_DIRECTORY_READ_FINISHED")).refresh());
        assertEquals(2,count("codevasp_directory_attempts",a.sourceId()));assertEquals(1,count("codevasp_directory_results",a.sourceId()));assertEquals(1,count("codevasp_directory_contents",a.sourceId()));unavailable(service::current);
        assertEquals(409,assertThrows(ResponseStatusException.class,service::refresh).getStatusCode().value());verify(client,times(2)).read();
    }
    @Test void concurrentRefreshHasOneClaimAndNoSecondNetworkCall()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return directory();}).when(client).read();
        try(var pool=Executors.newSingleThreadExecutor()){
            var future=pool.submit(service::refresh);try{assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(409,assertThrows(ResponseStatusException.class,service::refresh).getStatusCode().value());}finally{release.countDown();}
            assertEquals("SUCCESS",future.get(5,TimeUnit.SECONDS).status());
        }verify(client,times(1)).read();
    }
    // 갱신·삭제 대신 별도 원본을 처음부터 과거 시각으로 삽입한다. 불변 트리거를 끄지 않는 장애 상태 구성이다.
    long historicalHead(String source,long age){return new TransactionTemplate(manager).execute(t->{
        long id=jdbc.queryForObject("INSERT INTO codevasp_directory_attempts(source_id,started_at) VALUES(?,clock_timestamp()-make_interval(secs=>?)) RETURNING id",Long.class,source,age);
        jdbc.update("UPDATE codevasp_directory_heads SET attempt_id=? WHERE source_id=?",id,source);return id;
    });}
    void successFor(long attempt,String source,long content){jdbc.update("INSERT INTO codevasp_directory_results(attempt_id,source_id,status,content_id,client_started_at,client_finished_at) SELECT id,source_id,'SUCCESS',?,started_at,started_at FROM codevasp_directory_attempts WHERE id=? AND source_id=?",content,attempt,source);}
    @Test void abandonedAttemptCanBeReplacedAndLateResultCannotMoveCurrentHead(){
        var a=service.refresh();long content=service.current().contentId();long orphan=historicalHead(a.sourceId(),181);
        var latest=service.refresh();successFor(orphan,a.sourceId(),content);assertEquals(latest.attemptId(),service.current().attemptId());assertEquals(3,count("codevasp_directory_results",a.sourceId()));
    }
    @Test void staleSuccessIsUnavailableWithoutModifyingHistory(){
        var a=service.refresh();long content=service.current().contentId();long old=historicalHead(a.sourceId(),361);successFor(old,a.sourceId(),content);unavailable(service::current);verify(client,times(1)).read();
    }
    @Test void callerTransactionPinsHeadUntilItFinishes()throws Exception{
        service.refresh();var held=new CountDownLatch(1);var release=new CountDownLatch(1);var called=new CountDownLatch(1);
        doAnswer(i->{called.countDown();return directory();}).when(client).read();
        try(var pool=Executors.newFixedThreadPool(2)){
            var reader=pool.submit(()->new TransactionTemplate(manager).execute(t->{service.current();held.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}return null;}));
            assertTrue(held.await(5,TimeUnit.SECONDS));var writer=pool.submit(service::refresh);
            try{assertFalse(called.await(200,TimeUnit.MILLISECONDS));assertFalse(writer.isDone());}finally{release.countDown();}
            reader.get(5,TimeUnit.SECONDS);assertEquals("SUCCESS",writer.get(5,TimeUnit.SECONDS).status());assertTrue(called.await(1,TimeUnit.SECONDS));
        }
    }
    @Test void sourceChangesAndNewServiceInstanceDoNotReuseDifferentAuthority(){
        var a=service.refresh();assertEquals(a.attemptId(),service(config,audit).current().attemptId());
        for(var changed:List.of(new CodeVaspSettings("http://127.0.0.1:3","http://127.0.0.1:2",config.entity,key),new CodeVaspSettings("http://127.0.0.1:1","http://127.0.0.1:2","other-entity",key),new CodeVaspSettings("http://127.0.0.1:1","http://127.0.0.1:2",config.entity,Base64.getEncoder().encodeToString("1".repeat(32).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))))unavailable(()->service(changed,audit).current());
        verify(client,times(1)).read();
    }
    @Test void immutableRowsAndHeadRewindAndBadHashCannotBypassSourceContract(){
        var a=service.refresh();var b=service.refresh();
        for(String table:List.of("codevasp_directory_sources","codevasp_directory_attempts","codevasp_directory_contents","codevasp_directory_results"))for(String sql:List.of("UPDATE "+table+" SET "+(table.endsWith("results")?"status=status":table.endsWith("sources")?"id=id":"id=id"),"DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE codevasp_directory_heads SET attempt_id=? WHERE source_id=?",a.attemptId(),a.sourceId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_directory_contents(source_id,payload_hash,payload) VALUES(?,?,'[]')",a.sourceId(),"0".repeat(64)));
        assertEquals(b.attemptId(),service.current().attemptId());
    }
    @Test void malformedReadAndUnexpectedFailureKeepBoundedFailureCodes(){
        doReturn(null).when(client).read();assertEquals("INVALID_SOURCE",service.refresh().failureCode());
        doReturn(new CodeVaspDirectoryClient.Directory(Instant.now().plusSeconds(60),Instant.now().plusSeconds(61),List.of())).when(client).read();assertEquals("INVALID_SOURCE",service.refresh().failureCode());
        doThrow(new IllegalStateException("격리 내부 원문")).when(client).read();assertEquals("INTERNAL_FAILURE",service.refresh().failureCode());unavailable(service::current);
    }
    @Test void automaticWorkerPersistsFailureAndRecoversWithNewAttempt()throws Exception{
        var calls=new java.util.concurrent.atomic.AtomicInteger();var second=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());if(calls.incrementAndGet()==1)throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"격리 공급사 장애");second.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return directory();}).when(client).read();
        var worker=new CodeVaspDirectoryWorker(config,service,true,Duration.ofMillis(200));
        try{worker.start();assertTrue(second.await(5,TimeUnit.SECONDS));unavailable(service::current);release.countDown();org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).ignoreException(ResponseStatusException.class).untilAsserted(()->assertEquals(2,count("codevasp_directory_results",service.current().sourceId())));}
        finally{release.countDown();worker.stop();}
        var current=service.current();assertEquals(2,count("codevasp_directory_results",current.sourceId()));assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM codevasp_directory_results WHERE source_id=? ORDER BY attempt_id LIMIT 1",String.class,current.sourceId()));
    }

    @Test void unconfiguredAndAmbientTransactionCannotStartIo(){
        var disabled=service(new CodeVaspSettings("","","",""),audit);unavailable(disabled::refresh);unavailable(disabled::current);
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->service.refresh()));verifyNoInteractions(client);
    }
}
