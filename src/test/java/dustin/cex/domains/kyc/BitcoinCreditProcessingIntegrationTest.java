package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.shared.http.EngineDepositClient;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.transaction.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

/** 실제 현재 근거와 PG 임대를 통해 자동 대사/의도/전달을 연결한다. 외부 전송·노드 응답만 대역이다. */
class BitcoinCreditProcessingIntegrationTest extends BitcoinCreditTestSupport {
    @Autowired BitcoinCreditProcessing processing;
    @Autowired BitcoinCreditDispatches dispatch;
    @Autowired dustin.cex.shared.kafka.KafkaOrderEventConsumer consumer;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean BitcoinCreditIntents guardedIntents;
    int nodeCalls,engineCalls;
    @AfterEach void noUnexpectedBoundaryCalls(){org.mockito.Mockito.verify(node,times(nodeCalls)).inspect(anyString());org.mockito.Mockito.verify(depositEngine,times(engineCalls)).credit(any());verifyNoMoreInteractions(node,depositEngine);}
    // 실제 금융 결과가 먼저 도착하는 시험도 포함한다. 외부 호출 금지는 유지하고 원장/조회 잔액의 실제 합계를 검증한다.
    @Override @AfterEach void noExternalOrFinancialRelease(){
        verifyNoInteractions(directoryClient,transactions,transfers,client,engine,producer,priceClient);
        var nativeTotal=jdbc.queryForObject("SELECT coalesce(sum(r.amount),0) FROM bitcoin_pending_deposits p JOIN bitcoin_deposit_receipts r ON r.id=p.receipt_id WHERE r.user_id=?",BigDecimal.class,customer.getId());
        var pending=money("PENDING");var available=money("AVAILABLE");assertEquals(0,nativeTotal.compareTo(pending.add(available)));
        assertEquals(0,available.compareTo(jdbc.queryForObject("SELECT coalesce(sum(available),0) FROM user_balances WHERE user_id=? AND mint_address='BTC'",BigDecimal.class,customer.getId())));
    }
    BigDecimal money(String bucket){return jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id=? AND asset='BTC' AND bucket=?",BigDecimal.class,customer.getId(),bucket);}
    long rows(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void success(){when(depositEngine.credit(any())).thenReturn(EngineDepositClient.Outcome.DURABLY_CREDITED);}
    void due(){
        // 이 표는 재구성 가능한 작업 일정이다. 금융 원본/의도/시도/감사 이력을 바꾸지 않고 시험의 다음 실행 시각만 앞당긴다.
        jdbc.update("UPDATE bitcoin_credit_jobs SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE receipt_id=?",receipt.id());
    }
    void expireLease(){jdbc.update("UPDATE bitcoin_credit_jobs SET lease_until=clock_timestamp()-interval '1 second' WHERE receipt_id=? AND lease_id IS NOT NULL",receipt.id());}
    void seedJob(){jdbc.update("INSERT INTO bitcoin_credit_jobs(receipt_id) VALUES(?)",receipt.id());}
    void receive(){UUID id=UUID.randomUUID();String raw=json.writeValueAsString(Map.of("engine_id",id,"event_id",id+":1:0","event_type","deposit_credited","deposit_id",receipt.id(),"user_id",customer.getId(),"asset","BTC","amount","0.12345678","timestamp",Instant.now().toString()));new TransactionTemplate(manager).executeWithoutResult(t->consumer.consumeOrderEvent(raw));}
    BitcoinCreditProcessing service(dustin.cex.domains.travelrule.CodeVaspDirectoryService source,dustin.cex.domains.cdd.CddCrypto keys,BitcoinSettings target){return new BitcoinCreditProcessing(jdbc,target,source,keys,reconciliation,credits,dispatch,audit,manager);}
    void waitForEvent(){nodeCalls=engineCalls=1;success();assertEquals("WAITING_EVENT",processing.processNext().orElseThrow().state());}

    @Test void readySourceAutomaticallyReconcilesAuthorizesAndWaitsForTheEvent(){
        nodeCalls=engineCalls=1;success();
        var result=processing.processNext().orElseThrow();assertEquals(receipt.id(),result.receiptId());assertEquals("WAITING_EVENT",result.state());assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,result.transportOutcome());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_credit_jobs",Long.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_credit_intents",Long.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_credit_authorizations",Long.class));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_credited_deposits",Long.class));
        assertTrue(processing.processNext().isEmpty());
    }

    @Test void currentCustomerRestrictionsAreSkippedBeforeAnyNodeRead(){jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());assertTrue(processing.processNext().isEmpty());assertEquals(0,rows("bitcoin_credit_jobs"));assertEquals(0,rows("bitcoin_credit_intents"));}
    @Test void staleKycAndChainCannotEnterTheAutomaticCandidates(){jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp()+interval '1 minute' WHERE attempt_id=(SELECT active_attempt FROM kyc_profiles WHERE user_id=?)",customer.getId());assertTrue(processing.processNext().isEmpty());assertEquals(0,rows("bitcoin_credit_jobs"));}
    @Test void oldUnreadyJobCannotStarveThePreparedSource(){var original=receipt;nativeReceipt();UUID unready=receipt.id();monitoring.processNext().orElseThrow();jdbc.update("INSERT INTO bitcoin_credit_jobs(receipt_id,next_attempt_at) VALUES(?,clock_timestamp()-interval '1 day')",unready);receipt=original;waitForEvent();assertEquals(original.id(),jdbc.queryForObject("SELECT receipt_id FROM bitcoin_credit_jobs WHERE state='WAITING_EVENT'",UUID.class));assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM bitcoin_credit_jobs WHERE receipt_id=?",String.class,unready));}
    @Test void unprocessedMonitoringInputMustFinishBeforeAutomation(){var original=receipt;nativeReceipt();receipt=original;assertTrue(processing.processNext().isEmpty());monitoring.processNext().orElseThrow();waitForEvent();}
    @Test void anOpenCaseIsSkippedAndIndependentClosureMakesTheSourceReady()throws Exception{openRiskCase();assertTrue(processing.processNext().isEmpty());assertEquals(0,rows("bitcoin_credit_jobs"));closeRiskCase();waitForEvent();}
    @Test void missingFrozenValuationDoesNotCreateAnAutomaticJob(){when(priceClient.read()).thenThrow(new IllegalStateException("격리 가격 실패"));prices.refresh();clearInvocations(priceClient);nativeReceipt();monitoring.processNext().orElseThrow();assertTrue(processing.processNext().isEmpty());assertEquals(0,rows("bitcoin_credit_jobs"));}
    @Test void currentSourceConfigurationIsRequiredOnlyForNewAuthority(){var missing=mock(dustin.cex.domains.travelrule.CodeVaspDirectoryService.class);when(missing.configuredSourceId()).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));assertTrue(service(missing,crypto,bitcoin()).processNext().isEmpty());assertEquals(0,rows("bitcoin_credit_jobs"));}
    @Test void anotherWalletOrNetworkCannotProcessThisDatabaseReceipt(){for(String network:List.of("REGTEST","MAINNET")){var wrong=new BitcoinSettings("http://127.0.0.1:1","reader","x".repeat(32),network.equals("REGTEST")?"another_wallet":"cex_watch",network);assertTrue(service(directory,crypto,wrong).processNext().isEmpty());}assertEquals(0,rows("bitcoin_credit_jobs"));}
    @Test void freshIndependentNodeFailureDoesNotBorrowThePreviousMatchedRun(){nodeCalls=1;when(node.inspect(receipt.txid())).thenThrow(new IllegalStateException("격리 현재 노드 실패"));var result=processing.processNext().orElseThrow();assertEquals("HELD",result.state());assertEquals("RECON_FAILED",result.reason());assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM bitcoin_current_deposit_reconciliation WHERE receipt_id=?",String.class,receipt.id()));assertEquals(0,rows("bitcoin_credit_intents"));assertTrue(processing.processNext().isEmpty());}
    @Test void changedCustomerDuringNodeReadCannotObtainFirstExecutionAuthority(){nodeCalls=1;var original=node.inspect(receipt.txid());clearInvocations(node);when(node.inspect(receipt.txid())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());return original;});var result=processing.processNext().orElseThrow();assertEquals("HELD",result.state());assertEquals("HELD_CUSTOMER_NOT_ELIGIBLE",result.reason());assertEquals(0,rows("bitcoin_credit_authorizations"));assertEquals(0,rows("bitcoin_credit_intents"));}
    @Test void prerequisiteFilterCannotOverrideTheActualAdmissionPolicy(){nodeCalls=1;doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(guardedIntents).reserve(receipt.id());assertEquals("HELD",processing.processNext().orElseThrow().state());assertEquals(0,rows("bitcoin_credit_authorizations"));assertEquals(0,rows("bitcoin_credit_intents"));}
    @Test void nodeAndEngineWaitsRunOutsideTheClaimTransaction(){nodeCalls=engineCalls=1;var original=node.inspect(receipt.txid());clearInvocations(node);when(node.inspect(receipt.txid())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertNotNull(jdbc.queryForObject("SELECT lease_id FROM bitcoin_credit_jobs WHERE receipt_id=?",UUID.class,receipt.id()));return original;});when(depositEngine.credit(any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(1,rows("bitcoin_credit_authorizations"));return EngineDepositClient.Outcome.DURABLY_CREDITED;});assertEquals("WAITING_EVENT",processing.processNext().orElseThrow().state());}
    @Test void unknownOutcomePersistsRetryScheduleAndDoesNotRepeatTheNodeRead(){nodeCalls=engineCalls=1;when(depositEngine.credit(any())).thenReturn(EngineDepositClient.Outcome.OUTCOME_UNKNOWN);assertEquals("RETRY",processing.processNext().orElseThrow().state());assertEquals(1,jdbc.queryForObject("SELECT failures FROM bitcoin_credit_jobs",Integer.class));double first=jdbc.queryForObject("SELECT extract(epoch FROM next_attempt_at-updated_at) FROM bitcoin_credit_jobs",Double.class);assertTrue(first>=4.9&&first<=5.1);assertTrue(processing.processNext().isEmpty());due();assertEquals("RETRY",processing.processNext().orElseThrow().state());assertEquals(1,rows("bitcoin_credit_dispatch_attempts"));assertEquals(2,jdbc.queryForObject("SELECT failures FROM bitcoin_credit_jobs",Integer.class));double second=jdbc.queryForObject("SELECT extract(epoch FROM next_attempt_at-updated_at) FROM bitcoin_credit_jobs",Double.class);assertTrue(second>=9.9&&second<=10.1);}
    @Test void alreadyAuthorizedUnknownWorkResumesWithoutSourceKeysOrCustomerPermission()throws Exception{
        engineCalls=2;credits.reserve(receipt.id());when(depositEngine.credit(any())).thenReturn(EngineDepositClient.Outcome.OUTCOME_UNKNOWN);dispatch.execute(receipt.id()).orElseThrow();jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());Thread.sleep(5100);success();var missing=mock(dustin.cex.domains.travelrule.CodeVaspDirectoryService.class);when(missing.configuredSourceId()).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));var result=service(missing,new dustin.cex.domains.cdd.CddCrypto("","",""),bitcoin()).processNext().orElseThrow();assertEquals("WAITING_EVENT",result.state());assertEquals(1,rows("bitcoin_credit_authorizations"));assertEquals(2,rows("bitcoin_credit_dispatch_attempts"));
    }
    @Test void anExistingHttpAcknowledgementIsRecoveredWithoutAnotherTransport(){engineCalls=1;credits.reserve(receipt.id());success();dispatch.execute(receipt.id()).orElseThrow();assertEquals("WAITING_EVENT",processing.processNext().orElseThrow().state());assertEquals(0,rows("bitcoin_credited_deposits"));due();assertEquals("WAITING_EVENT",processing.processNext().orElseThrow().state());assertEquals(1,rows("bitcoin_credit_dispatch_attempts"));}
    @Test void durableRejectionEndsOnlyTheJobAndPreservesPendingMoney(){nodeCalls=engineCalls=1;when(depositEngine.credit(any())).thenReturn(EngineDepositClient.Outcome.DURABLY_REJECTED);assertEquals("ENGINE_REJECTED",processing.processNext().orElseThrow().state());assertNull(jdbc.queryForObject("SELECT next_attempt_at FROM bitcoin_credit_jobs",java.sql.Timestamp.class));assertTrue(processing.processNext().isEmpty());assertEquals(0,rows("bitcoin_credited_deposits"));assertEquals(0,BigDecimal.ZERO.compareTo(money("AVAILABLE")));}
    @Test void actualFinancialEventEndsTheJobWithoutRecontactingEngine(){waitForEvent();receive();due();assertEquals("SETTLED",processing.processNext().orElseThrow().state());assertTrue(processing.processNext().isEmpty());assertEquals(1,rows("bitcoin_credited_deposits"));assertEquals(0,new BigDecimal("0.12345678").compareTo(money("AVAILABLE")));}
    @Test void aFinancialEventBeforeHttpResponseCanBeSettledInTheSameProcessingPass(){nodeCalls=engineCalls=1;when(depositEngine.credit(any())).thenAnswer(call->{receive();return EngineDepositClient.Outcome.OUTCOME_UNKNOWN;});assertEquals("SETTLED",processing.processNext().orElseThrow().state());assertTrue(processing.processNext().isEmpty());assertEquals(1,rows("bitcoin_credited_deposits"));}
    @Test void anExpiredOperationalLeaseIsRecoveredFromTheActualSource(){nodeCalls=engineCalls=1;UUID old=UUID.randomUUID();jdbc.update("INSERT INTO bitcoin_credit_jobs(receipt_id,lease_id,lease_until) VALUES(?,?,clock_timestamp()-interval '1 second')",receipt.id(),old);success();assertEquals("WAITING_EVENT",processing.processNext().orElseThrow().state());assertNull(jdbc.queryForObject("SELECT lease_id FROM bitcoin_credit_jobs",UUID.class));assertEquals(1,rows("bitcoin_credit_authorizations"));}
    @Test void anActiveLeaseCannotBeClaimedByAnotherExecution(){jdbc.update("INSERT INTO bitcoin_credit_jobs(receipt_id,lease_id,lease_until) VALUES(?,?,clock_timestamp()+interval '2 minutes')",receipt.id(),UUID.randomUUID());assertTrue(processing.processNext().isEmpty());assertEquals(0,rows("bitcoin_credit_authorizations"));}
    @Test void seedAuditFailureCannotCreateOrExecuteWork(){doThrow(new IllegalStateException("격리 생성 감사 실패")).when(audit).record(isNull(),eq("BTC_CREDIT_JOB_CREATED"),anyString(),anyString(),anyString());assertThrows(RuntimeException.class,()->processing.processNext());assertEquals(0,rows("bitcoin_credit_jobs"));assertEquals(0,rows("bitcoin_credit_intents"));}
    @Test void claimAuditFailureCannotRetainALeaseOrExecuteWork(){doThrow(new IllegalStateException("격리 임대 감사 실패")).when(audit).record(isNull(),eq("BTC_CREDIT_JOB_CLAIMED"),anyString(),anyString(),anyString());assertThrows(RuntimeException.class,()->processing.processNext());assertEquals(0,rows("bitcoin_credit_jobs"));assertEquals(0,rows("bitcoin_credit_intents"));}
    @Test void finishAuditFailureRecoversTheConfirmedFactAfterLeaseExpiry(){nodeCalls=engineCalls=1;success();doThrow(new IllegalStateException("격리 완료 감사 실패")).when(audit).record(isNull(),eq("BTC_CREDIT_JOB_FINISHED"),anyString(),anyString(),anyString());assertThrows(RuntimeException.class,()->processing.processNext());assertEquals(1,rows("bitcoin_credit_dispatch_results"));assertNotNull(jdbc.queryForObject("SELECT lease_id FROM bitcoin_credit_jobs",UUID.class));doCallRealMethod().when(audit).record(isNull(),eq("BTC_CREDIT_JOB_FINISHED"),anyString(),anyString(),anyString());expireLease();assertEquals("WAITING_EVENT",processing.processNext().orElseThrow().state());assertEquals(1,rows("bitcoin_credit_dispatch_attempts"));}
    @Test void anOldCompletionCannotOverwriteTheNewLeaseAndItsState()throws Exception{
        nodeCalls=engineCalls=1;var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(depositEngine.credit(any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return EngineDepositClient.Outcome.DURABLY_CREDITED;});
        try(var pool=Executors.newSingleThreadExecutor()){var old=pool.submit(()->processing.processNext());assertTrue(entered.await(5,TimeUnit.SECONDS));try{expireLease();assertEquals("RETRY",processing.processNext().orElseThrow().state());}finally{release.countDown();}assertTrue(old.get(5,TimeUnit.SECONDS).isEmpty());}
        assertEquals("RETRY",jdbc.queryForObject("SELECT state FROM bitcoin_credit_jobs",String.class));due();assertEquals("WAITING_EVENT",processing.processNext().orElseThrow().state());assertEquals(1,rows("bitcoin_credit_dispatch_attempts"));
    }
    @Test void workStateCannotPretendToBeAFinancialResult(){seedJob();for(String state:List.of("SETTLED","WAITING_EVENT","ENGINE_REJECTED")){assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE bitcoin_credit_jobs SET state=?,next_attempt_at=CASE WHEN ?='WAITING_EVENT' THEN clock_timestamp() ELSE NULL END WHERE receipt_id=?",state,state,receipt.id()));}assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM bitcoin_credit_jobs",String.class));}
    @Test void operationalIdentityAndHistoryCannotBeDeletedOrReassigned(){seedJob();for(String sql:List.of("DELETE FROM bitcoin_credit_jobs","TRUNCATE bitcoin_credit_jobs","UPDATE bitcoin_credit_jobs SET created_at=created_at-interval '1 day'"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));assertEquals(1,rows("bitcoin_credit_jobs"));}
    @Test void ambientTransactionCannotStartAutomaticProcessing(){assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->processing.processNext()));assertEquals(0,rows("bitcoin_credit_jobs"));}

    @Test void anExplicitlyOwnedWorkerRunsTheActualDatabasePipeline()throws Exception{
        nodeCalls=engineCalls=1;success();var owned=new BitcoinCreditWorker(bitcoin(),processing,true);
        try{
            // test 프로필의 자동 실행 차단을 바꾸지 않는다. 이 시험이 직접 생성/종료하는 실행기만 실제 PG 서비스와 연결한다.
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(owned,"start");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);boolean confirmed=false;
            while(System.nanoTime()<deadline){var states=jdbc.queryForList("SELECT state FROM bitcoin_credit_jobs WHERE receipt_id=?",String.class,receipt.id());if(!states.isEmpty()&&states.getFirst().equals("WAITING_EVENT")){confirmed=true;break;}Thread.sleep(10);}
            assertTrue(confirmed);assertEquals(1,rows("bitcoin_credit_authorizations"));assertEquals(0,rows("bitcoin_credited_deposits"));
        }finally{org.springframework.test.util.ReflectionTestUtils.invokeMethod(owned,"stop");}
    }
}
