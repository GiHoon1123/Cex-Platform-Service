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
    boolean executionExpected;
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
    @AfterEach void noFinancialOrExternalSearch(){if(!executionExpected)verifyNoInteractions(transactions);verifyNoInteractions(engine,producer);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));}
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
    CodeVaspDepositSearchExecution execution(AuditService recorder){
        executionExpected=true;
        return new CodeVaspDepositSearchExecution(searches,transactions,jdbc,recorder,json,manager);
    }
    CodeVaspTransactionClient.Result found(UUID id){return new CodeVaspTransactionClient.Result(id,CodeVaspTransactionClient.Status.FOUND,"origin",List.of(new CodeVaspTransactionClient.Analysis("origin","FOUND")));}
    String hash(String value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    // 중단/지난 시각은 새 원본을 처음부터 그 시각으로 구성한다. 실제 이력 수정이나 불변 트리거 해제로 시간을 이동하지 않는다.
    long pastAttempt(CodeVaspDepositSearches.Search search,String phase,int startedAge,String status,int recordedAge,CodeVaspTransactionClient.Result result){return new TransactionTemplate(manager).execute(t->{
        long attempt=jdbc.queryForObject("INSERT INTO codevasp_search_attempts(search_id,phase,started_at) VALUES(?,?,clock_timestamp()-make_interval(secs=>?)) RETURNING id",Long.class,search.id(),phase,startedAge);
        jdbc.update("INSERT INTO codevasp_search_heads(search_id,attempt_id) VALUES(?,?) ON CONFLICT(search_id) DO UPDATE SET attempt_id=excluded.attempt_id",search.id(),attempt);
        if(status!=null){String payload=result==null?null:json.writeValueAsString(result);jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,failure_code,payload,payload_hash,recorded_at) VALUES(?,?,?,?,?,clock_timestamp()-make_interval(secs=>?))",attempt,status,Set.of("SUBMIT_NOT_SENT","SUBMIT_UNKNOWN","POLL_FAILED").contains(status)?"PROVIDER_UNAVAILABLE":null,payload,payload==null?null:hash(payload),recordedAge);}
        return attempt;
    });}
    AuditService failingAudit(String action){var recorder=mock(AuditService.class);doAnswer(i->{if(i.getArgument(1).equals(action))throw new IllegalStateException("격리 필수 감사 장애");audit.record(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4));return null;}).when(recorder).record(any(),anyString(),anyString(),anyString(),anyString());return recorder;}

    @Test void executionSubmitsOriginalRequestOnceAndPersistsAcknowledgement(){
        var search=searches.reserve(receipt.id());var execution=execution(audit);assertEquals("UNSTARTED",execution.current(search.id()).status());
        doAnswer(i->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertEquals(search.request(),i.getArgument(0));assertEquals("RUNNING",execution.current(search.id()).status());return null;}).when(transactions).submit(any());
        var result=execution.refresh(search.id());assertEquals("SUBMIT_ACKNOWLEDGED",result.status());assertNull(result.result());assertEquals(result,execution.current(search.id()));assertEquals(result,new CodeVaspDepositSearchExecution(searches,transactions,jdbc,audit,json,manager).current(search.id()));verify(transactions).submit(search.request());verify(transactions,never()).result(any());
    }
    @Test void concurrentExecutionCannotSubmitTwiceWhileFirstProviderCallIsPending()throws Exception{
        var search=searches.reserve(receipt.id());var execution=execution(audit);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}).when(transactions).submit(any());
        try(var pool=Executors.newSingleThreadExecutor()){
            var first=pool.submit(()->execution.refresh(search.id()));try{assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals("RUNNING",execution.current(search.id()).status());assertEquals(409,assertThrows(ResponseStatusException.class,()->execution.refresh(search.id())).getStatusCode().value());}finally{release.countDown();}
            assertEquals("SUBMIT_ACKNOWLEDGED",first.get(5,TimeUnit.SECONDS).status());
        }verify(transactions,times(1)).submit(any());
    }
    @Test void executionStartAuditFailureCannotReachProvider(){
        var search=searches.reserve(receipt.id());var execution=execution(failingAudit("CODE_DEPOSIT_SEARCH_STARTED"));assertThrows(IllegalStateException.class,()->execution.refresh(search.id()));assertEquals("UNSTARTED",execution.current(search.id()).status());verifyNoInteractions(transactions);
    }
    @Test void executionFinishAuditFailureRetainsRunningStartWithoutSecondSubmission(){
        var search=searches.reserve(receipt.id());var execution=execution(failingAudit("CODE_DEPOSIT_SEARCH_FINISHED"));assertThrows(IllegalStateException.class,()->execution.refresh(search.id()));assertEquals("RUNNING",execution.current(search.id()).status());assertEquals(409,assertThrows(ResponseStatusException.class,()->execution.refresh(search.id())).getStatusCode().value());verify(transactions,times(1)).submit(any());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM codevasp_search_results r JOIN codevasp_search_attempts a ON a.id=r.attempt_id WHERE a.search_id=?",Long.class,search.id()));
    }
    @Test void orphanedSubmissionIsRecoveredByPollingAndLateAckCannotMoveCurrentHead(){
        var search=searches.reserve(receipt.id());long orphan=pastAttempt(search,"SUBMIT",181,null,0,null);var execution=execution(audit);when(transactions.result(search.id())).thenAnswer(i->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return found(search.id());});
        var result=execution.refresh(search.id());assertEquals("POLL",result.phase());assertEquals("FOUND",result.status());assertEquals(found(search.id()),result.result());jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status) VALUES(?,'SUBMIT_ACKNOWLEDGED')",orphan);assertEquals(result,execution.current(search.id()));verify(transactions,never()).submit(any());verify(transactions).result(search.id());
    }
    @Test void lostSubmissionResponseIsUnknownAndDoesNotTriggerImmediateResubmission(){
        var search=searches.reserve(receipt.id());var execution=execution(audit);doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"격리 공급사 원문")).when(transactions).submit(any());var result=execution.refresh(search.id());assertEquals("SUBMIT_UNKNOWN",result.status());assertEquals("PROVIDER_UNAVAILABLE",result.failureCode());assertFalse(result.toString().contains("공급사 원문"));assertEquals(409,assertThrows(ResponseStatusException.class,()->execution.refresh(search.id())).getStatusCode().value());verify(transactions,times(1)).submit(any());
    }
    @Test void latestRunningAndFailedPollCannotExposeEarlierFound(){
        var search=searches.reserve(receipt.id());pastAttempt(search,"SUBMIT",241,"SUBMIT_ACKNOWLEDGED",181,null);pastAttempt(search,"POLL",121,"FOUND",61,found(search.id()));var execution=execution(audit);assertEquals("FOUND",execution.current(search.id()).status());
        when(transactions.result(search.id())).thenAnswer(i->{assertEquals("RUNNING",execution.current(search.id()).status());throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"격리 공급사 장애");});
        var result=execution.refresh(search.id());assertEquals("POLL_FAILED",result.status());assertNull(result.result());assertEquals(result,execution.current(search.id()));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM codevasp_search_results r JOIN codevasp_search_attempts a ON a.id=r.attempt_id WHERE a.search_id=? AND r.status='FOUND'",Long.class,search.id()));
    }
    @Test void malformedPollReplyKeepsBoundedInvalidSourceFailure(){
        var search=searches.reserve(receipt.id());pastAttempt(search,"SUBMIT",181,null,0,null);var execution=execution(audit);when(transactions.result(search.id())).thenReturn(found(UUID.randomUUID()));var result=execution.refresh(search.id());assertEquals("POLL_FAILED",result.status());assertEquals("INVALID_SOURCE",result.failureCode());assertNull(result.result());
    }
    @Test void completedAttemptEnforcesPollingIntervalAndDirectResubmissionIsRejected(){
        var search=searches.reserve(receipt.id());var execution=execution(audit);execution.refresh(search.id());assertEquals(409,assertThrows(ResponseStatusException.class,()->execution.refresh(search.id())).getStatusCode().value());
        for(String phase:List.of("SUBMIT","POLL"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_search_attempts(search_id,phase,started_at) VALUES(?,?,clock_timestamp()-interval '3 minutes')",search.id(),phase));verify(transactions,times(1)).submit(any());
    }
    @Test void immutableResultsAndPayloadBindingRejectDirectDatabaseBypass(){
        var search=searches.reserve(receipt.id());long original=pastAttempt(search,"SUBMIT",241,"SUBMIT_ACKNOWLEDGED",181,null);long poll=pastAttempt(search,"POLL",61,null,0,null);String wrong=json.writeValueAsString(found(UUID.randomUUID()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,payload,payload_hash) VALUES(?,'FOUND',?,?)",poll,wrong,hash(wrong)));
        String valid=json.writeValueAsString(found(search.id()));assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,payload,payload_hash) VALUES(?,'FOUND',?,?)",poll,valid,"0".repeat(64)));
        for(String table:List.of("codevasp_search_attempts","codevasp_search_results"))for(String sql:List.of("UPDATE "+table+" SET "+(table.endsWith("results")?"status=status":"id=id"),"DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE codevasp_search_heads SET attempt_id=? WHERE search_id=?",original,search.id()));assertEquals("RUNNING",execution(audit).current(search.id()).status());verifyNoInteractions(transactions);
    }
    @Test void resultPhaseCannotPretendSubmissionWasCompletedSearch(){
        var search=searches.reserve(receipt.id());long original=pastAttempt(search,"SUBMIT",181,null,0,null);String payload=json.writeValueAsString(found(search.id()));var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,payload,payload_hash) VALUES(?,'FOUND',?,?)",original,payload,hash(payload)));assertTrue(failure.getMostSpecificCause().getMessage().contains("실행 단계"));
    }
    @Test void executionCannotUseAmbientTransactionForProviderIo(){
        var search=searches.reserve(receipt.id());var execution=execution(audit);assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->execution.refresh(search.id())));assertEquals("UNSTARTED",execution.current(search.id()).status());verifyNoInteractions(transactions);
    }
    @Test void futureExecutionTimesCannotBeInsertedAsFreshHistory(){
        var search=searches.reserve(receipt.id());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_search_attempts(search_id,phase,started_at) VALUES(?,'SUBMIT',clock_timestamp()+interval '1 minute')",search.id()));
        long attempt=pastAttempt(search,"SUBMIT",181,null,0,null);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,recorded_at) VALUES(?,'SUBMIT_ACKNOWLEDGED',clock_timestamp()+interval '1 minute')",attempt));
    }
    @Test void provenNonSendIsStoredSeparatelyWithoutImmediateRetryBurst(){
        var search=searches.reserve(receipt.id());var execution=execution(audit);doThrow(new CodeVaspSubmissionFailure(CodeVaspSubmissionFailure.Admission.NOT_SENT)).when(transactions).submit(any());
        var result=execution.refresh(search.id());assertEquals("SUBMIT_NOT_SENT",result.status());assertEquals(409,assertThrows(ResponseStatusException.class,()->execution.refresh(search.id())).getStatusCode().value());verify(transactions,times(1)).submit(search.request());
    }
    @Test void provenNonSendCanRetrySameRequestAfterIntervalWithoutPollingUnknownIdentity(){
        var search=searches.reserve(receipt.id());pastAttempt(search,"SUBMIT",61,"SUBMIT_NOT_SENT",31,null);var execution=execution(audit);var result=execution.refresh(search.id());assertEquals("SUBMIT",result.phase());assertEquals("SUBMIT_ACKNOWLEDGED",result.status());verify(transactions).submit(search.request());verify(transactions,never()).result(any());
    }
    CodeVaspDepositSearchProcessing processing(){return new CodeVaspDepositSearchProcessing(directory,execution(audit),jdbc);}
    @Test void automaticProcessingUsesReservedIdentityAndSkipsImmediateSecondCall(){
        var search=searches.reserve(receipt.id());var processing=processing();var first=processing.processNext().orElseThrow();assertEquals(search.id(),first.searchId());assertEquals("SUBMIT_ACKNOWLEDGED",first.status());assertTrue(processing.processNext().isEmpty());verify(transactions,times(1)).submit(search.request());
    }
    @Test void automaticOldUnknownSubmissionCanPollWhileDirectoryRefreshHasFailed(){
        var search=searches.reserve(receipt.id());pastAttempt(search,"SUBMIT",181,null,0,null);doThrow(new IllegalStateException("격리 목록 장애")).when(client).read();directory.refresh();when(transactions.result(search.id())).thenReturn(found(search.id()));
        var result=processing().processNext().orElseThrow();assertEquals("FOUND",result.status());assertEquals("POLL",result.phase());verify(transactions,never()).submit(any());
    }
    @Test void automaticProvenNonSendWaitsForFreshDirectoryBeforeSubmittingAgain(){
        var search=searches.reserve(receipt.id());pastAttempt(search,"SUBMIT",61,"SUBMIT_NOT_SENT",31,null);doThrow(new IllegalStateException("격리 목록 장애")).when(client).read();directory.refresh();var processing=processing();assertEquals(503,assertThrows(ResponseStatusException.class,processing::processNext).getStatusCode().value());verifyNoInteractions(transactions);
        doAnswer(i->new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of())).when(client).read();directory.refresh();assertEquals("SUBMIT_ACKNOWLEDGED",processing.processNext().orElseThrow().status());verify(transactions).submit(search.request());
    }
    @Test void automaticProcessingRetriesDueProcessingResultThenRespectsThirtySecondBackoff(){
        var search=searches.reserve(receipt.id());pastAttempt(search,"SUBMIT",181,"SUBMIT_ACKNOWLEDGED",121,null);pastAttempt(search,"POLL",61,"PROCESSING",31,new CodeVaspTransactionClient.Result(search.id(),CodeVaspTransactionClient.Status.PROCESSING,null,List.of()));when(transactions.result(search.id())).thenReturn(new CodeVaspTransactionClient.Result(search.id(),CodeVaspTransactionClient.Status.PROCESSING,null,List.of()));var processing=processing();assertEquals("PROCESSING",processing.processNext().orElseThrow().status());assertTrue(processing.processNext().isEmpty());verify(transactions,times(1)).result(search.id());
    }
    @Test void completedOrUnresolvableTechnicalResultsAreNotPolledForever(){
        for(var status:List.of(CodeVaspTransactionClient.Status.FOUND,CodeVaspTransactionClient.Status.NOT_FOUND,CodeVaspTransactionClient.Status.UNKNOWN_REQUEST,CodeVaspTransactionClient.Status.AMBIGUOUS)){
            var seed=BitcoinDepositFixtures.seed(jdbc,json,receipt.owner(),Instant.now());var search=searches.reserve(seed.id());pastAttempt(search,"SUBMIT",181,"SUBMIT_ACKNOWLEDGED",121,null);pastAttempt(search,"POLL",61,status.name(),31,status==CodeVaspTransactionClient.Status.FOUND?found(search.id()):new CodeVaspTransactionClient.Result(search.id(),status,null,List.of()));assertEquals(status.name(),execution(audit).current(search.id()).status());
        }assertTrue(processing().processNext().isEmpty());verifyNoInteractions(transactions);
    }
    @Test void automaticWindowExhaustionKeepsRunningEvidenceAndAllowsExplicitResolution(){
        var search=searches.reserve(receipt.id());pastAttempt(search,"SUBMIT",901,null,0,null);var processing=processing();assertTrue(processing.processNext().isEmpty());assertEquals("RUNNING",execution(audit).current(search.id()).status());verifyNoInteractions(transactions);
        when(transactions.result(search.id())).thenReturn(found(search.id()));assertEquals("FOUND",execution(audit).refresh(search.id()).status());verify(transactions,never()).submit(any());
    }
    @Test void automaticSelectionDoesNotCrossConfiguredSourceAuthority(){
        var ours=searches.reserve(receipt.id());var otherDirectory=directory(new CodeVaspSettings("http://127.0.0.1:3","http://127.0.0.1:2","other-"+UUID.randomUUID(),key));otherDirectory.refresh();var otherSearches=new CodeVaspDepositSearches(otherDirectory,jdbc,audit,manager);var other=otherSearches.reserve(receipt.id());assertEquals(ours.id(),processing().processNext().orElseThrow().searchId());verify(transactions).submit(ours.request());verify(transactions,never()).submit(other.request());
    }
    @Test void concurrentAutomaticSelectorsHaveOnlyOneExternalSubmission()throws Exception{
        var search=searches.reserve(receipt.id());var processing=processing();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);doAnswer(i->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}).when(transactions).submit(any());
        try(var pool=Executors.newSingleThreadExecutor()){
            var first=pool.submit(processing::processNext);try{assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(processing.processNext().isEmpty());}finally{release.countDown();}assertEquals(search.id(),first.get(5,TimeUnit.SECONDS).orElseThrow().searchId());
        }verify(transactions,times(1)).submit(any());
    }
    @Test void automaticSelectorCannotHoldCallerTransactionOverProviderIo(){
        searches.reserve(receipt.id());var processing=processing();assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->processing.processNext()));verifyNoInteractions(transactions);
    }
    @Test void automaticallyStartedOwnedWorkerPersistsRealDatabaseAttemptAndStops(){
        var search=searches.reserve(receipt.id());var processing=processing();
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.getEnvironment().setActiveProfiles("production");context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("격리 자동 검색",Map.of("codevasp.processing.enabled","true")));
            // 실제 표는 상위 격리 PG 컨텍스트가 준비했다. 자식 컨텍스트는 실행기 수명만 소유한다.
            context.registerBean("codeVaspSchemaInitializer",Object.class,Object::new);context.registerBean(CodeVaspSettings.class,()->config);context.registerBean(CodeVaspDepositSearchProcessing.class,()->processing);context.register(CodeVaspDepositSearchWorker.class);context.refresh();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(()->assertEquals("SUBMIT_ACKNOWLEDGED",execution(audit).current(search.id()).status()));
        }verify(transactions,times(1)).submit(search.request());
    }
    @Test void heldNewSubmissionCannotBlockIndependentDuePollDuringDirectoryOutage(){
        var known=searches.reserve(receipt.id());pastAttempt(known,"SUBMIT",181,"SUBMIT_ACKNOWLEDGED",31,null);
        var another=BitcoinDepositFixtures.seed(jdbc,json,receipt.owner(),Instant.now());var fresh=directory.current();UUID waiting=UUID.randomUUID();
        // 후보 정렬의 앞에 오는 오래된 미착수 원본을 새로 구성한다. 실제 원본의 생성 시각을 UPDATE하지 않는다.
        jdbc.update("INSERT INTO codevasp_deposit_searches(id,receipt_id,source_id,directory_attempt_id,created_at) VALUES(?,?,?,?,clock_timestamp()-interval '61 seconds')",waiting,another.id(),fresh.sourceId(),fresh.attemptId());
        doThrow(new IllegalStateException("격리 목록 장애")).when(client).read();directory.refresh();when(transactions.result(known.id())).thenReturn(found(known.id()));
        assertEquals(known.id(),processing().processNext().orElseThrow().searchId());assertEquals("UNSTARTED",execution(audit).current(waiting).status());verify(transactions,never()).submit(any());
    }
}
