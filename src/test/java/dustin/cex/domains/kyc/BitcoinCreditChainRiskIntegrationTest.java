package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.shared.http.EngineDepositClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/** 이미 권한을 준 입금의 체인 근거 상실과 신규 권한 차단을 실제 PG 이력으로 검증한다. */
class BitcoinCreditChainRiskIntegrationTest extends BitcoinCreditTestSupport {
    @Autowired BitcoinCreditDispatches dispatch;
    @Autowired BitcoinObservationService observations;
    @Autowired BitcoinDepositObservationJobs jobs;
    @Autowired dustin.cex.shared.kafka.KafkaOrderEventConsumer consumer;
    final UUID engineIdentity=UUID.randomUUID();
    boolean permitted(){return new TransactionTemplate(manager).execute(t->eligibility.permitted(users.lockById(customer.getId()).orElseThrow()));}
    long risks(){return jdbc.queryForObject("SELECT count(*) FROM bitcoin_credit_chain_risks WHERE user_id=?",Long.class,customer.getId());}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    BigDecimal money(String bucket){return jdbc.queryForObject("SELECT coalesce(sum(delta),0) FROM ledger_entries WHERE owner_id=? AND asset='BTC' AND bucket=?",BigDecimal.class,customer.getId(),bucket);}
    void authorized(EngineDepositClient.Outcome outcome){credits.reserve(receipt.id());when(depositEngine.credit(any())).thenReturn(outcome);assertEquals(outcome,dispatch.execute(receipt.id()).orElseThrow().outcome());assertTrue(permitted());}
    void receive(){String raw=json.writeValueAsString(Map.of("event_id",engineIdentity+":1:0","engine_id",engineIdentity,"event_type","deposit_credited","deposit_id",receipt.id(),"user_id",customer.getId(),"asset","BTC","amount","0.12345678","timestamp",Instant.now().toString()));new TransactionTemplate(manager).executeWithoutResult(t->consumer.consumeOrderEvent(raw));}
    BitcoinDepositReader.Observation changed(BitcoinDepositReader.Status status,long confirmations,boolean coinbase,boolean mismatch){
        return new BitcoinDepositReader.Observation("bitcoin-regtest",receipt.txid(),status,confirmations,coinbase,false,status==BitcoinDepositReader.Status.CONFIRMED?BitcoinDepositFixtures.hex():null,status==BitcoinDepositReader.Status.CONFIRMED?0L:null,BitcoinDepositFixtures.hex(),status==BitcoinDepositReader.Status.CONFIRMED?confirmations-1:5L,List.of(new BitcoinDepositReader.Output(0,receipt.address(),receipt.script(),new BigDecimal(mismatch?"0.10000000":"0.12345678"))));
    }
    long inspect(BitcoinDepositReader.Observation next){when(node.inspect(receipt.txid())).thenReturn(next);try{return observations.refresh(receipt.txid()).id();}finally{clearInvocations(node);}}
    void evaluate(long id){new TransactionTemplate(manager).executeWithoutResult(t->qualification.evaluate(id));}
    void unconfirmed(){evaluate(inspect(changed(BitcoinDepositReader.Status.UNCONFIRMED,0,false,false)));}
    @Override @AfterEach void noExternalOrFinancialRelease(){
        verifyNoInteractions(directoryClient,transactions,transfers,client,engine,producer,node,priceClient);
        var total=jdbc.queryForObject("SELECT sum(r.amount) FROM bitcoin_pending_deposits p JOIN bitcoin_deposit_receipts r ON r.id=p.receipt_id WHERE r.user_id=?",BigDecimal.class,customer.getId());assertEquals(0,total.compareTo(money("PENDING").add(money("AVAILABLE"))));
        var available=jdbc.queryForObject("SELECT coalesce(sum(available),0) FROM user_balances WHERE user_id=? AND mint_address='BTC'",BigDecimal.class,customer.getId());assertEquals(0,available.compareTo(money("AVAILABLE")));
    }
    @Test void unconfirmedAfterActualCreditBlocksNewOrdersWithoutReversingTheLedger(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);receive();unconfirmed();assertEquals(1,risks());assertFalse(permitted());assertEquals(0,new BigDecimal("0.12345678").compareTo(money("AVAILABLE")));assertEquals(1,count("bitcoin_credited_deposits"));assertEquals(403,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->trading.requireNewOrder(customer.getId(),"BTC","USDT"))).getStatusCode().value());}
    @Test void lostConfirmationsAfterAuthorizationAreNotOnlyAPendingDepositStatus(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);evaluate(inspect(changed(BitcoinDepositReader.Status.CONFIRMED,5,false,false)));assertEquals(1,risks());assertFalse(permitted());assertEquals(0,count("bitcoin_credited_deposits"));}
    @Test void conflictingTransactionBlocksTheAlreadyAuthorizedCustomer(){authorized(EngineDepositClient.Outcome.OUTCOME_UNKNOWN);evaluate(inspect(changed(BitcoinDepositReader.Status.CONFLICTED,-1,false,false)));assertFalse(permitted());assertEquals("CONFLICTED",jdbc.queryForObject("SELECT reason FROM bitcoin_credit_chain_risks",String.class));}
    @Test void outputMismatchIsAnExecutionRiskWithoutChangingTheOriginalAmount(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);evaluate(inspect(changed(BitcoinDepositReader.Status.CONFIRMED,6,false,true)));assertFalse(permitted());assertEquals("OUTPUT_MISMATCH",jdbc.queryForObject("SELECT reason FROM bitcoin_credit_chain_risks",String.class));assertEquals(0,new BigDecimal("0.12345678").compareTo(jdbc.queryForObject("SELECT amount FROM bitcoin_credit_authorizations",BigDecimal.class)));}
    @Test void immatureCoinbaseMetadataCannotPreserveOldExecutionPermission(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);evaluate(inspect(changed(BitcoinDepositReader.Status.CONFIRMED,6,true,false)));assertEquals("IMMATURE_COINBASE",jdbc.queryForObject("SELECT reason FROM bitcoin_credit_chain_risks",String.class));assertFalse(permitted());}
    @Test void aStaleObservationIsNotEvidenceOfAReorganization(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,true);assertEquals(0,risks());assertTrue(permitted());}
    @Test void aFreshConfirmedObservationDoesNotCreateAnIncident(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);assertEquals(0,risks());assertTrue(permitted());}
    @Test void anUnexecutedDepositCannotFreezeTheCustomersOtherAssets(){unconfirmed();assertEquals(0,risks());assertTrue(permitted());assertThrows(BitcoinCreditBasis.Held.class,()->credits.reserve(receipt.id()));}
    @Test void anIntentWithoutExecutionAuthorityDoesNotFreezeTheCustomer(){credits.reserve(receipt.id());unconfirmed();assertEquals(0,risks());assertTrue(permitted());assertThrows(BitcoinCreditBasis.Held.class,()->dispatch.execute(receipt.id()));assertEquals(0,count("bitcoin_credit_authorizations"));}
    @Test void olderUnconfirmedHistoryDoesNotInvalidateANewVerifiedAuthorization(){unconfirmed();BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);nodeProof();authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);assertEquals(0,risks());assertTrue(permitted());}
    @Test void laterChainRecoveryDoesNotSilentlyEraseTheRiskHistory(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);unconfirmed();BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);assertEquals("CHAIN_READY",jdbc.queryForObject("SELECT d.reason FROM bitcoin_deposit_chain_heads h JOIN bitcoin_deposit_chain_decisions d ON d.id=h.decision_id WHERE h.receipt_id=?",String.class,receipt.id()));assertEquals(1,risks());assertFalse(permitted());}
    @Test void alreadyExecutedFinancialFactStillCompletesAfterTheRiskWasDiscovered(){authorized(EngineDepositClient.Outcome.OUTCOME_UNKNOWN);unconfirmed();assertFalse(permitted());receive();assertEquals(1,count("bitcoin_credited_deposits"));assertEquals(0,new BigDecimal("0.12345678").compareTo(money("AVAILABLE")));assertFalse(permitted());}
    @Test void alreadyAuthorizedUnknownRequestCanBeReconfirmedWithoutNewAuthority()throws Exception{authorized(EngineDepositClient.Outcome.OUTCOME_UNKNOWN);unconfirmed();Thread.sleep(5100);when(depositEngine.credit(any())).thenReturn(EngineDepositClient.Outcome.DURABLY_CREDITED);assertEquals(EngineDepositClient.Outcome.DURABLY_CREDITED,dispatch.execute(receipt.id()).orElseThrow().outcome());assertEquals(1,count("bitcoin_credit_authorizations"));assertEquals(1,count("bitcoin_credit_intents"));assertFalse(permitted());}
    @Test void missingRequiredObservationAuditRollsBackTheNewRiskFact(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);long before=count("bitcoin_transaction_observations");
        doNothing().when(audit).record(isNull(),eq("BTC_TRANSACTION_OBSERVED"),anyString(),anyString(),anyString());assertThrows(DataAccessException.class,()->inspect(changed(BitcoinDepositReader.Status.UNCONFIRMED,0,false,false)));assertEquals(before,count("bitcoin_transaction_observations"));assertEquals(0,risks());
        doCallRealMethod().when(audit).record(isNull(),eq("BTC_TRANSACTION_OBSERVED"),anyString(),anyString(),anyString());unconfirmed();assertEquals(1,risks());assertFalse(permitted());}
    @Test void committedObservationBlocksNewPermissionBeforeQualificationRuns(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);long before=count("bitcoin_deposit_chain_decisions");inspect(changed(BitcoinDepositReader.Status.UNCONFIRMED,0,false,false));assertEquals(before,count("bitcoin_deposit_chain_decisions"));assertEquals(1,risks());assertFalse(permitted());}
    @Test void qualificationAuditFailureCannotUndoTheAlreadyCommittedRiskObservation(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);long id=inspect(changed(BitcoinDepositReader.Status.UNCONFIRMED,0,false,false));long before=count("bitcoin_deposit_chain_decisions");doThrow(new IllegalStateException("격리 후속 판단 감사 실패")).when(audit).record(isNull(),eq("BTC_DEPOSIT_CHAIN_EVALUATED"),anyString(),anyString(),anyString());assertThrows(RuntimeException.class,()->evaluate(id));assertEquals(before,count("bitcoin_deposit_chain_decisions"));assertEquals(1,risks());assertFalse(permitted());}
    @Test void aRiskFactCannotBeInsertedWithoutItsPublishedHeadAndAudit(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);assertThrows(DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at) SELECT network,wallet,txid,'UNCONFIRMED',0,coinbase,NULL,NULL,tip_hash,tip_height,outputs,clock_timestamp() FROM bitcoin_transaction_observations ORDER BY id DESC LIMIT 1")));assertEquals(0,risks());assertTrue(permitted());}
    @Test void aMissingRiskViewCannotBeInterpretedAsNoRisk(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);jdbc.execute("DROP VIEW bitcoin_credit_chain_risks");assertThrows(DataAccessException.class,()->permitted());}
    @Test void userLockSerializesRiskPublicationWithFinancialPermission()throws Exception{
        authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);
        try(var pool=Executors.newSingleThreadExecutor()){
            // 고객 잠금을 보유할 때 별도 연결의 관찰 공개가 대기하다 시간 초과되는지 확인한다.
            new TransactionTemplate(manager).executeWithoutResult(t->{users.lockById(customer.getId()).orElseThrow();var task=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(x->{jdbc.execute("SET LOCAL lock_timeout='200ms'");jdbc.update("INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at) SELECT network,wallet,txid,'UNCONFIRMED',0,coinbase,NULL,NULL,tip_hash,tip_height,outputs,clock_timestamp() FROM bitcoin_transaction_observations ORDER BY id DESC LIMIT 1");}));try{assertThrows(ExecutionException.class,()->task.get(3,TimeUnit.SECONDS));}finally{assertTrue(task.isDone());}assertEquals(0,risks());});
        }
        unconfirmed();assertEquals(1,risks());assertFalse(permitted());
    }
    @Test void theActualObservationQueueRefreshesCreditedDepositsAndPublishesTheRisk(){authorized(EngineDepositClient.Outcome.DURABLY_CREDITED);receive();org.springframework.test.util.ReflectionTestUtils.invokeMethod(jobs,"seedKnownReceipts");when(node.inspect(receipt.txid())).thenReturn(changed(BitcoinDepositReader.Status.UNCONFIRMED,0,false,false));assertTrue(jobs.refreshDue());clearInvocations(node);assertEquals(1,risks());assertFalse(permitted());assertEquals(1,count("bitcoin_credited_deposits"));}
    @Test void directSqlTakesTheUserLockBeforeValidatingCurrentFinancialFacts()throws Exception{
        var basis=capture();String raw=json.writeValueAsString(basis),digest=sha(raw);
        var pending=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        try(var pool=Executors.newSingleThreadExecutor()){
            new TransactionTemplate(manager).executeWithoutResult(t->{
                users.lockById(customer.getId()).orElseThrow();
                pending.set(pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(x->{
                    jdbc.update("INSERT INTO bitcoin_credit_intents(receipt_id,user_id,asset,amount,policy_id,basis,basis_hash) VALUES(?,?,?,?,?,?,?)",receipt.id(),customer.getId(),"BTC",new BigDecimal("0.12345678"),basis.policy(),raw,digest);
                    audit.record(null,"BTC_CREDIT_INTENT_AUTHORIZED","bitcoin-credit:"+receipt.id(),"SUCCESS","POLICY:"+basis.policy()+":BASIS:"+digest);
                })));
                // 실제 다른 연결이 잠금에서 기다리는지 확인한 뒤 권한 근거를 바꾼다.
                // 외래키가 마지막에만 기다린다면 옛 판단을 통과한 INSERT가 커밋할 수 있어 이 시험은 실패한다.
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(true){
                    jdbc.query("SELECT pg_stat_clear_snapshot()",row->{});
                    if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query LIKE 'INSERT INTO bitcoin_credit_intents%')",Boolean.class)))break;
                    if(pending.get().isDone()){try{pending.get().get();}catch(Exception failure){throw new IllegalStateException("직접 SQL이 잠금 전에 실패했다",failure);}fail("고객 잠금 전에 직접 SQL이 확정됐다");}
                    if(System.nanoTime()>=end)fail("직접 SQL의 고객 잠금 대기를 확인하지 못했다");
                    try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                }
                jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());
            });
            assertThrows(ExecutionException.class,()->pending.get().get(5,TimeUnit.SECONDS));
        }
        assertEquals(0,count("bitcoin_credit_intents"));assertFalse(permitted());
    }

}
