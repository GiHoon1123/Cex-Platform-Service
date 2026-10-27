package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.order.service.*;
import dustin.cex.domains.order.repository.*;
import dustin.cex.shared.http.EngineDepositClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.*;

/** 실제 적립·예약·위험 관찰을 준비하고 시스템 근거와 취소 큐의 원자적 결합을 검증한다. 외부 엔진/노드만 합성이다. */
class BitcoinChainRiskCancellationIntegrationTest extends BitcoinCreditTestSupport {
    @Autowired BitcoinChainRiskOrderCancellations planner;
    @Autowired OrderCancellationService output;
    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRows;
    @Autowired OrderCancellationRepository queued;
    @Autowired BitcoinCreditDispatches dispatch;
    @Autowired BitcoinObservationService observations;
    @Autowired dustin.cex.shared.kafka.KafkaOrderEventConsumer consumer;
    UUID source=UUID.randomUUID();long first,second,incident;String depositEvent;
    @BeforeEach void activeOrdersAndRisk(){
        credits.reserve(receipt.id());when(depositEngine.credit(any())).thenReturn(EngineDepositClient.Outcome.DURABLY_CREDITED);dispatch.execute(receipt.id()).orElseThrow();
        depositEvent=json.writeValueAsString(Map.of("event_id",source+":1:0","engine_id",source,"event_type","deposit_credited","deposit_id",receipt.id(),"user_id",customer.getId(),"asset","BTC","amount","0.12345678","timestamp",Instant.now().toString()));
        consumer.consumeOrderEvent(depositEvent);when(fees.getFeeConfig("BTC","USDT")).thenReturn(dustin.cex.domains.fee.model.entity.FeeConfig.builder().feeRate(BigDecimal.ZERO).feeType("both").isActive(true).build());
        when(engine.submitOrder(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString(),any(),any(),any())).thenReturn(true);
        var command=dustin.cex.domains.order.model.dto.CreateOrderRequest.builder().baseMint("BTC").quoteMint("USDT").orderType("sell").orderSide("limit").price(new BigDecimal("100000")).amount(new BigDecimal("0.01")).build();
        first=Long.parseLong(orderService.createOrder(customer.getId(),UUID.randomUUID(),command).response().getOrder().getId());second=Long.parseLong(orderService.createOrder(customer.getId(),UUID.randomUUID(),command).response().getOrder().getId());
        org.mockito.Mockito.verify(producer,times(2)).publishOrderCreated(anyString());
        clearInvocations(engine,depositEngine,producer,node);
        when(engine.cancelOrder(anyLong(),eq(customer.getId()),eq("BTC/USDT"))).thenReturn(true);
    }
    BitcoinDepositReader.Observation metadata(BitcoinDepositReader.Status status,long confirmations){return new BitcoinDepositReader.Observation("bitcoin-regtest",receipt.txid(),status,confirmations,false,false,status==BitcoinDepositReader.Status.CONFIRMED?"a".repeat(64):null,status==BitcoinDepositReader.Status.CONFIRMED?0L:null,"b".repeat(64),status==BitcoinDepositReader.Status.CONFIRMED?confirmations-1:5,List.of(new BitcoinDepositReader.Output(0,receipt.address(),receipt.script(),new BigDecimal("0.12345678"))));}
    long risk(){when(node.inspect(receipt.txid())).thenReturn(metadata(BitcoinDepositReader.Status.UNCONFIRMED,0));incident=observations.refresh(receipt.txid()).id();clearInvocations(node);return incident;}
    String cause(long order){return jdbc.queryForObject("INSERT INTO bitcoin_chain_risk_order_cancellations(order_id,user_id,receipt_id,observation_id,policy) VALUES(?,?,?,?,?) RETURNING audit_detail",String.class,order,customer.getId(),receipt.id(),incident,BitcoinChainRiskOrderCancellations.POLICY);}
    void directPlan(Long actor,String override){new TransactionTemplate(manager).executeWithoutResult(t->{String detail=cause(first);assertTrue(output.stage(customer.getId(),first));audit.record(actor,"BTC_CHAIN_RISK_CANCEL_PLANNED","order:"+first,"SUCCESS",override==null?detail:override);});}
    String cancelled(long order,int sequence){return json.writeValueAsString(Map.of("event_id",source+":"+sequence+":0","engine_id",source,"event_type","order_cancelled","order_id",Long.toString(order),"user_id",customer.getId(),"base_mint","BTC","quote_mint","USDT","timestamp",java.time.Instant.now().toString()));}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    boolean eligible(){return new TransactionTemplate(manager).execute(t->eligibility.permitted(users.lockById(customer.getId()).orElseThrow()));}
    @Override @AfterEach void noExternalOrFinancialRelease(){
        verifyNoInteractions(directoryClient,transactions,transfers,client,producer,priceClient,depositEngine);
        var amounts=jdbc.queryForMap("SELECT available,locked FROM user_balances WHERE user_id=? AND mint_address='BTC'",customer.getId());assertEquals(0,new BigDecimal("0.12345678").compareTo(((BigDecimal)amounts.get("available")).add((BigDecimal)amounts.get("locked"))));
    }
    @Test void currentRiskStagesOneExactSystemCauseWithoutCallingTheEngine(){risk();var plan=planner.stageNext().orElseThrow();assertEquals(first,plan.orderId());assertEquals(customer.getId(),plan.customerId());assertEquals(receipt.id(),plan.receiptId());assertEquals(1,count("order_cancellations"));assertEquals(1,count("bitcoin_chain_risk_order_cancellations"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id IS NULL AND action='BTC_CHAIN_RISK_CANCEL_PLANNED' AND target=?",Long.class,"order:"+first));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='ORDER_CANCEL_REQUESTED'",Long.class));assertFalse(eligible());verifyNoInteractions(engine,node);}
    @Test void sequentialPlanningDoesNotCreateDuplicateRequestsOrMoveReservations(){risk();long entries=count("ledger_entries");assertEquals(first,planner.stageNext().orElseThrow().orderId());assertEquals(second,planner.stageNext().orElseThrow().orderId());assertTrue(planner.stageNext().isEmpty());assertEquals(2,count("order_cancellations"));assertEquals(entries,count("ledger_entries"));assertEquals("pending",orderRows.findById(first).orElseThrow().getStatus());assertEquals(0,new BigDecimal("0.02").compareTo(jdbc.queryForObject("SELECT locked FROM user_balances WHERE user_id=? AND mint_address='BTC'",BigDecimal.class,customer.getId())));verifyNoInteractions(engine,node);}
    @Test void theExistingOutputWorkerConfirmsOnlyTransportAndFinancialEventsReleaseTheFunds(){
        risk();planner.stageNext().orElseThrow();planner.stageNext().orElseThrow();assertTrue(output.retryNext());assertTrue(output.retryNext());assertFalse(output.retryNext());assertEquals(0,new BigDecimal("0.02").compareTo(jdbc.queryForObject("SELECT locked FROM user_balances WHERE user_id=? AND mint_address='BTC'",BigDecimal.class,customer.getId())));
        for(long id:List.of(first,second)){String raw=json.writeValueAsString(Map.of("event_id",source+":"+(id==first?2:3)+":0","engine_id",source,"event_type","order_cancelled","order_id",Long.toString(id),"user_id",customer.getId(),"base_mint","BTC","quote_mint","USDT","timestamp",java.time.Instant.now().toString()));consumer.consumeOrderEvent(raw);consumer.consumeOrderEvent(raw);}
        assertEquals(0,BigDecimal.ZERO.compareTo(jdbc.queryForObject("SELECT locked FROM user_balances WHERE user_id=? AND mint_address='BTC'",BigDecimal.class,customer.getId())));assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE kind='CANCELLED'",Long.class));assertFalse(eligible());assertTrue(planner.stageNext().isEmpty());
    }
    @Test void healthyCustomersDoNotAcquireAutomaticCancellationWork(){assertTrue(eligible());assertTrue(planner.stageNext().isEmpty());assertEquals(0,count("order_cancellations"));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));verifyNoInteractions(engine,node);}
    @Test void laterHealthyObservationDoesNotEraseTheUnresolvedRisk(){long original=risk();when(node.inspect(receipt.txid())).thenReturn(metadata(BitcoinDepositReader.Status.CONFIRMED,6));observations.refresh(receipt.txid());assertEquals(original,planner.stageNext().orElseThrow().observationId());assertFalse(eligible());}
    @Test void repeatedRiskObservationsKeepTheOldestUnresolvedCause(){long original=risk();long latest=risk();assertTrue(latest>original);assertEquals(original,planner.stageNext().orElseThrow().observationId());assertEquals(1,count("bitcoin_chain_risk_order_cancellations"));}
    @Test void systemAuditFailureRollsBackTheCauseAndQueueButPreservesTheRisk(){risk();doThrow(new IllegalStateException("격리 위험 취소 감사 실패")).when(audit).record(isNull(),eq("BTC_CHAIN_RISK_CANCEL_PLANNED"),anyString(),anyString(),anyString());assertThrows(RuntimeException.class,()->planner.stageNext());assertEquals(0,count("order_cancellations"));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));assertEquals(1,count("bitcoin_credit_chain_risks"));verifyNoInteractions(engine,node);}
    @Test void silentlyOmittedSystemAuditCannotCommitEitherRecord(){risk();doNothing().when(audit).record(isNull(),eq("BTC_CHAIN_RISK_CANCEL_PLANNED"),anyString(),anyString(),anyString());assertThrows(org.springframework.dao.DataAccessException.class,()->planner.stageNext());assertEquals(0,count("order_cancellations"));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));verifyNoInteractions(engine,node);}
    @Test void anIncorrectAuditDetailCannotBorrowTheSameIncident(){risk();assertThrows(org.springframework.dao.DataAccessException.class,()->directPlan(null,"다른 관찰을 확인한 것으로 바꾼 감사"));assertEquals(0,count("order_cancellations"));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));}
    @Test void systemActionCannotBeMisattributedToTheCustomer(){risk();assertThrows(org.springframework.dao.DataAccessException.class,()->directPlan(customer.getId(),null));assertEquals(0,count("order_cancellations"));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));}
    @Test void customerIntentAuditAloneCannotCoverTheSystemCause(){risk();assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{cause(first);assertTrue(output.stage(customer.getId(),first));audit.record(customer.getId(),"ORDER_CANCEL_REQUESTED","order:"+first,"SUCCESS","INTENT_ONLY_ENGINE_RESULT_FOLLOWS");}));assertEquals(0,count("order_cancellations"));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));}
    @Test void aCauseWithoutItsSameTransactionQueueCannotCommit(){risk();assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{String detail=cause(first);audit.record(null,"BTC_CHAIN_RISK_CANCEL_PLANNED","order:"+first,"SUCCESS",detail);}));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));}
    @Test void systemAuditWithoutARealCauseCannotAuthorizeTheNeutralQueue(){risk();assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->{assertTrue(output.stage(customer.getId(),first));audit.record(null,"BTC_CHAIN_RISK_CANCEL_PLANNED","order:"+first,"SUCCESS","만들지 않은 위험 근거");}));assertEquals(0,count("order_cancellations"));}
    @Test void aNormalObservationCannotBeSubstitutedForTheRiskCause(){risk();long normal=jdbc.queryForObject("SELECT(basis::jsonb->'chain'->>'observationId')::bigint FROM bitcoin_credit_authorizations WHERE receipt_id=?",Long.class,receipt.id());assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("INSERT INTO bitcoin_chain_risk_order_cancellations(order_id,user_id,receipt_id,observation_id,policy) VALUES(?,?,?,?,?)",first,customer.getId(),receipt.id(),normal,BitcoinChainRiskOrderCancellations.POLICY)));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));}
    @Test void anotherCustomerCannotBorrowTheIncident(){risk();assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("INSERT INTO bitcoin_chain_risk_order_cancellations(order_id,user_id,receipt_id,observation_id,policy) VALUES(?,?,?,?,?)",first,maker.user().getId(),receipt.id(),incident,BitcoinChainRiskOrderCancellations.POLICY)));assertEquals(0,count("order_cancellations"));}
    @Test void existingCustomerCancellationIsNeitherDuplicatedNorRelabeled(){risk();orderService.cancelOrder(customer.getId(),first);assertEquals(second,planner.stageNext().orElseThrow().orderId());assertEquals(2,count("order_cancellations"));assertEquals(1,count("bitcoin_chain_risk_order_cancellations"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM bitcoin_chain_risk_order_cancellations WHERE order_id=?",Long.class,first));org.mockito.Mockito.verify(engine,times(1)).cancelOrder(first,customer.getId(),"BTC/USDT");}
    @Test void aRetrospectiveSystemCauseCannotBeAttachedToAnExistingCustomerRequest(){risk();orderService.cancelOrder(customer.getId(),first);assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->cause(first)));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));}
    @Test void disabledCustomerStillReceivesRiskReducingCancellation(){risk();var u=users.findById(customer.getId()).orElseThrow();u.setEnabled(false);users.saveAndFlush(u);assertEquals(first,planner.stageNext().orElseThrow().orderId());assertFalse(users.findById(customer.getId()).orElseThrow().isEnabled());assertFalse(eligible());verifyNoInteractions(engine,node);}
    @Test void anotherTradingRestrictionIsNeverRemovedByPlanning(){risk();var u=users.findById(customer.getId()).orElseThrow();u.setTradingRestricted(true);users.saveAndFlush(u);planner.stageNext().orElseThrow();assertTrue(users.findById(customer.getId()).orElseThrow().isTradingRestricted());assertFalse(eligible());}
    @Test void sourceAbsenceCannotBecomeAnEmptySuccessfulPlanningPass(){risk();jdbc.execute("DROP VIEW bitcoin_credit_chain_risks CASCADE");assertThrows(org.springframework.dao.DataAccessException.class,()->planner.stageNext());assertEquals(0,count("order_cancellations"));verifyNoInteractions(engine,node);}
    @Test void ambientTransactionsAreRejectedBeforePlanning(){risk();assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->planner.stageNext()));assertEquals(0,count("order_cancellations"));verifyNoInteractions(engine,node);}
    @Test void neutralStagingRequiresTheCallersActualTransaction(){risk();assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->output.stage(customer.getId(),first));assertEquals(0,count("order_cancellations"));}
    @Test void canonicalCauseCannotBeEditedDeletedOrTruncated(){risk();planner.stageNext().orElseThrow();for(String sql:List.of("UPDATE bitcoin_chain_risk_order_cancellations SET observation_id=observation_id+1","DELETE FROM bitcoin_chain_risk_order_cancellations","TRUNCATE bitcoin_chain_risk_order_cancellations CASCADE"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));assertEquals(1,count("bitcoin_chain_risk_order_cancellations"));}
    @Test void sourceCannotBeBackdatedIntoAnotherTransaction(){risk();assertThrows(org.springframework.dao.DataAccessException.class,()->new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("INSERT INTO bitcoin_chain_risk_order_cancellations(order_id,user_id,receipt_id,observation_id,policy,created_at) VALUES(?,?,?,?,?,clock_timestamp()-interval '1 day')",first,customer.getId(),receipt.id(),incident,BitcoinChainRiskOrderCancellations.POLICY)));assertEquals(0,count("order_cancellations"));}
    @Test void parallelPlannersDoNotCreateTwoCausesForTheSameCandidate()throws Exception{
        risk();var ready=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);var database=spy(jdbc);
        doAnswer(call->{var result=jdbc.queryForList(call.getArgument(0,String.class));ready.countDown();assertTrue(release.await(5,java.util.concurrent.TimeUnit.SECONDS));return result;}).when(database).queryForList(anyString());
        var concurrent=new BitcoinChainRiskOrderCancellations(database,users,orderRows,queued,output,audit,manager);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var a=pool.submit(concurrent::stageNext);var b=pool.submit(concurrent::stageNext);assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));release.countDown();var results=List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,results.stream().filter(Optional::isPresent).count());
        }finally{release.countDown();}assertEquals(1,count("order_cancellations"));assertEquals(1,count("bitcoin_chain_risk_order_cancellations"));verifyNoInteractions(engine,node);
    }
    @Test void aFinancialCompletionBetweenCandidateReadAndLockIsNotCanceledAgain(){
        risk();var database=spy(jdbc);doAnswer(call->{var result=jdbc.queryForList(call.getArgument(0,String.class));consumer.consumeOrderEvent(cancelled(first,2));return result;}).when(database).queryForList(anyString());
        var racing=new BitcoinChainRiskOrderCancellations(database,users,orderRows,queued,output,audit,manager);assertTrue(racing.stageNext().isEmpty());assertEquals(0,count("order_cancellations"));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));assertEquals("cancelled",orderRows.findById(first).orElseThrow().getStatus());verifyNoInteractions(engine,node);
    }
    @Test void aCustomerRequestBetweenCandidateReadAndLockKeepsItsRealOrigin(){
        risk();var database=spy(jdbc);doAnswer(call->{var result=jdbc.queryForList(call.getArgument(0,String.class));orderService.cancelOrder(customer.getId(),first);return result;}).when(database).queryForList(anyString());
        var racing=new BitcoinChainRiskOrderCancellations(database,users,orderRows,queued,output,audit,manager);assertTrue(racing.stageNext().isEmpty());assertEquals(1,count("order_cancellations"));assertEquals(0,count("bitcoin_chain_risk_order_cancellations"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='ORDER_CANCEL_REQUESTED'",Long.class,customer.getId()));org.mockito.Mockito.verify(engine,times(1)).cancelOrder(first,customer.getId(),"BTC/USDT");
    }
    @Test void theOwnedProductionWorkerStagesReadyOrdersAndStops()throws Exception{
        risk();try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
            context.getEnvironment().setActiveProfiles("production");context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("격리 위험 취소 예약",Map.of("order.dispatch.enabled","true")));context.getBeanFactory().registerSingleton("bitcoinChainRiskOrderCancellations",planner);context.register(BitcoinChainRiskCancellationWorker.class);context.refresh();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(4)).untilAsserted(()->assertEquals(2,count("order_cancellations")));
        }assertEquals(2,count("bitcoin_chain_risk_order_cancellations"));assertFalse(eligible());verifyNoInteractions(engine,node);
    }
}
