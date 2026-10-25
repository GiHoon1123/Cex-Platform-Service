package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.travelrule.*;
import dustin.cex.domains.monitoring.*;
import dustin.cex.shared.http.EngineDepositClient;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 정상 VASP 증빙·현재 KYC/CDD·실제 원장/대사/탐지 위에 반입 의도만 남긴다. 외부 공급사·엔진을 호출하지 않는다. */
class BitcoinCreditIntentIntegrationTest extends CodeVaspTransferTestSupport {
    static final String APPROVAL="""
        [{"entityId":"origin","legalName":"가상 송신 사업자","reviewReference":"LOCAL-ONLY","evidenceSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","validFrom":"2026-01-01T00:00:00Z","validUntil":"2030-01-01T00:00:00Z"}]
        """;
    @DynamicPropertySource static void admission(DynamicPropertyRegistry r){r.add("codevasp.approved-counterparties",()->APPROVAL);r.add("codevasp.processing.enabled",()->false);r.add("codevasp.private-transfer.enabled",()->false);}
    @Override CodeVaspCounterpartyPolicy policy(){return new CodeVaspCounterpartyPolicy(APPROVAL);}
    @Autowired BitcoinCreditIntents credits;
    @Autowired BitcoinCreditBasis creditBasis;
    @Autowired DepositMonitoringService monitoring;
    @Autowired BitcoinDepositReconciliationService reconciliation;
    @MockitoBean BitcoinDepositReader node;
    @MockitoBean EngineDepositClient depositEngine;
    CodeVaspTransferExecution.State received;
    @BeforeEach void prepared(){
        var intent=intents.reserve(search.id());var person=new CodeVaspTransferDataClient.Person(declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO).validated().fullName(),null,LocalDate.of(1990,1,1),List.of(receipt.address()));
        when(transfers.exchange(any())).thenReturn(new CodeVaspTransferDataClient.Result("NORMAL",null,new CodeVaspTransferDataClient.Data(search.id(),"origin",new BigDecimal("0.12345678"),person,person,"b".repeat(64))));
        received=new CodeVaspTransferExecution(intents,transfers,crypto,jdbc,json,audit,manager,assessments).execute(intent.id());
        assertEquals("NO_SIGNAL",monitoring.processNext().orElseThrow().result().outcome());
        var o=jdbc.queryForMap("SELECT o.* FROM bitcoin_transaction_observations o JOIN bitcoin_observation_heads h ON h.observation_id=o.id WHERE o.txid=?",receipt.txid());
        when(node.inspect(receipt.txid())).thenReturn(new BitcoinDepositReader.Observation("bitcoin-regtest",receipt.txid(),BitcoinDepositReader.Status.CONFIRMED,6,false,false,(String)o.get("block_hash"),((Number)o.get("block_height")).longValue(),(String)o.get("tip_hash"),((Number)o.get("tip_height")).longValue(),List.of(new BitcoinDepositReader.Output(0,receipt.address(),receipt.script(),new BigDecimal("0.12345678")))));
        assertEquals(BitcoinDepositReconciliationService.Status.MATCHED,reconciliation.reconcile(receipt.id()).status());clearInvocations(node,transfers,priceClient);
    }
    @AfterEach void noPublication(){verifyNoInteractions(node,depositEngine,priceClient);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE owner_id=? AND bucket='AVAILABLE'",Long.class,customer.getId()));}
    @Test void verifiedNormalRouteRecordsOneIntentWithoutPublishingFunds(){
        var result=credits.reserve(receipt.id());assertTrue(result.created());assertEquals(receipt.id(),result.intent().depositId());assertEquals(customer.getId().toString(),result.intent().customerId());assertEquals("0.12345678",result.intent().amount());assertEquals(BitcoinCreditBasis.POLICY,result.intent().policy());assertEquals(BitcoinCreditIntents.ASSURANCE,result.intent().assurance());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM bitcoin_credit_intents",Long.class));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_CREDIT_INTENT_AUTHORIZED'",Long.class));
    }
    @Autowired CodeVaspDepositSearchReview searchView;
    @Autowired CodeVaspCounterpartyPolicy configuredPeers;
    @Autowired CodeVaspTransferEvidence evidence;
    @Autowired DepositMonitoringReview monitoringView;
    @Autowired DepositMonitoringCases cases;
    @Autowired DepositMonitoringInvestigations investigations;
    @Autowired DepositMonitoringCaseReviews reviews;
    @Autowired DepositMonitoringCaseDispositions actions;
    long creditCount(){return jdbc.queryForObject("SELECT count(*) FROM bitcoin_credit_intents",Long.class);}
    void held(BitcoinCreditBasis.Reason reason){var failed=assertThrows(BitcoinCreditBasis.Held.class,()->credits.reserve(receipt.id()));assertEquals(reason,failed.reason());assertEquals(0,creditCount());}
    BitcoinCreditBasis gate(org.springframework.jdbc.core.JdbcTemplate database,dustin.cex.domains.cdd.CddCrypto key,CodeVaspCounterpartyPolicy peers){return new BitcoinCreditBasis(database,bitcoin(),eligibility,monitoringView,searchView,directory,peers,evidence,key,json);}
    @Autowired BitcoinSettings bitcoinSettings;
    BitcoinSettings bitcoin(){return bitcoinSettings;}
    BitcoinCreditIntents withGate(BitcoinCreditBasis gate){return new BitcoinCreditIntents(jdbc,users,gate,json,audit,manager);}
    BitcoinCreditBasis.Basis capture(){return new org.springframework.transaction.support.TransactionTemplate(manager).execute(t->creditBasis.require(receipt.id(),users.lockById(customer.getId()).orElseThrow()));}
    org.springframework.jdbc.core.JdbcTemplate corrupt(String matching,java.util.function.Consumer<Map<String,Object>> change){
        var guarded=spy(jdbc);doAnswer(call->{String sql=call.getArgument(0,String.class);Object[] args=Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length);var rows=jdbc.queryForList(sql,args);
            if(!sql.contains(matching))return rows;return rows.stream().map(row->{var copy=new HashMap<String,Object>(row);change.accept(copy);return (Map<String,Object>)copy;}).toList();
        }).when(guarded).queryForList(anyString(),any(Object[].class));return guarded;
    }
    void unavailable(BitcoinCreditIntents target){var failed=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->target.reserve(receipt.id()));assertEquals(503,failed.getStatusCode().value());assertEquals(0,creditCount());}
    void nativeReceipt(){receipt=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);pending.recognize(receipt.id());}
    void nodeProof(){
        var o=jdbc.queryForMap("SELECT o.* FROM bitcoin_transaction_observations o JOIN bitcoin_observation_heads h ON h.observation_id=o.id WHERE o.txid=?",receipt.txid());
        when(node.inspect(receipt.txid())).thenReturn(new BitcoinDepositReader.Observation("bitcoin-regtest",receipt.txid(),BitcoinDepositReader.Status.CONFIRMED,6,false,false,(String)o.get("block_hash"),((Number)o.get("block_height")).longValue(),(String)o.get("tip_hash"),((Number)o.get("tip_height")).longValue(),List.of(new BitcoinDepositReader.Output(0,receipt.address(),receipt.script(),new BigDecimal("0.12345678")))));
        assertEquals(BitcoinDepositReconciliationService.Status.MATCHED,reconciliation.reconcile(receipt.id()).status());clearInvocations(node);
    }
    @Test void sameReceiptKeepsOneIntentAndOriginalBasis(){var first=credits.reserve(receipt.id());var replay=credits.reserve(receipt.id());assertFalse(replay.created());assertEquals(first.intent(),replay.intent());assertEquals(1,creditCount());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_CREDIT_INTENT_AUTHORIZED'",Long.class));}
    @Test void concurrentReservationsHaveOneCreator()throws Exception{
        var start=new java.util.concurrent.CountDownLatch(1);try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<BitcoinCreditIntents.Reserved> call=()->{assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));return credits.reserve(receipt.id());};var a=pool.submit(call);var b=pool.submit(call);start.countDown();var one=a.get(10,java.util.concurrent.TimeUnit.SECONDS);var two=b.get(10,java.util.concurrent.TimeUnit.SECONDS);assertNotEquals(one.created(),two.created());assertEquals(one.intent(),two.intent());assertEquals(1,creditCount());
        }
    }
    @Test void basisAndAuditDoNotCopyPrivateIdentityOrAddress(){var intent=credits.reserve(receipt.id()).intent();String raw=jdbc.queryForObject("SELECT basis FROM bitcoin_credit_intents WHERE receipt_id=?",String.class,receipt.id());String detail=jdbc.queryForObject("SELECT detail FROM security_audit WHERE action='BTC_CREDIT_INTENT_AUTHORIZED'",String.class);for(String privateValue:List.of(customer.getEmail(),declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO).fullName(),"1990-01-01",receipt.address(),receipt.script())){assertFalse(raw.contains(privateValue));assertFalse(detail.contains(privateValue));}assertFalse(capture().toString().contains(receipt.txid()));assertEquals(sha(raw),intent.basisHash());}
    @Test void disabledCustomerCannotCreateAnIntent(){jdbc.update("UPDATE users SET enabled=false WHERE id=?",customer.getId());held(BitcoinCreditBasis.Reason.CUSTOMER_NOT_ELIGIBLE);}
    @Test void restrictedCustomerCannotCreateAnIntent(){jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());held(BitcoinCreditBasis.Reason.CUSTOMER_NOT_ELIGIBLE);}
    @Test void expiredKycFreshnessCannotAuthorizeMoney(){jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp()-interval '16 minutes' WHERE attempt_id=(SELECT active_attempt FROM kyc_profiles WHERE user_id=?)",customer.getId());held(BitcoinCreditBasis.Reason.CUSTOMER_NOT_ELIGIBLE);}
    @Test void futureKycFreshnessCannotAuthorizeMoneyOrOrders(){jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp()+interval '1 minute' WHERE attempt_id=(SELECT active_attempt FROM kyc_profiles WHERE user_id=?)",customer.getId());held(BitcoinCreditBasis.Reason.CUSTOMER_NOT_ELIGIBLE);assertEquals(403,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->trading.requireNewOrder(customer.getId()))).getStatusCode().value());}
    @Test void newUnverifiedDeclarationCannotReuseTheOldCustomerPermission(){cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO));held(BitcoinCreditBasis.Reason.CUSTOMER_NOT_ELIGIBLE);}
    @Test void newlyVerifiedDeclarationCannotBorrowOldOriginData()throws Exception{cdd.submit(customer.getId(),sid,UUID.randomUUID(),declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO));verify(false);allowed();clearInvocations(client);held(BitcoinCreditBasis.Reason.ORIGIN_NOT_VERIFIED);}
    @Test void staleChainObservationCannotBorrowOldConfirmation(){BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,true);held(BitcoinCreditBasis.Reason.CHAIN_NOT_READY);}
    @Test void refreshedObservationOfTheSameBlockDoesNotRequireNewPrivateData(){
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->{long observation=jdbc.queryForObject("""
            INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at)
             SELECT network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,clock_timestamp() FROM bitcoin_transaction_observations WHERE id=(SELECT observation_id FROM bitcoin_observation_heads WHERE network='bitcoin-regtest' AND txid=?) RETURNING id
            """,Long.class,receipt.txid());jdbc.update("INSERT INTO bitcoin_observation_wallet_debits(observation_id,wallet_debit) VALUES(?,false)",observation);jdbc.update("UPDATE bitcoin_observation_heads SET observation_id=? WHERE txid=?",observation,receipt.txid());qualification.evaluate(observation);});
        assertTrue(credits.reserve(receipt.id()).created());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM codevasp_transfer_attempts",Long.class));
    }
    @Test void changedBlockAnchorRequiresNewIndependentReconciliation(){BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);held(BitcoinCreditBasis.Reason.RECONCILIATION_REQUIRED);nodeProof();assertTrue(credits.reserve(receipt.id()).created());}
    @Test void latestRunningReconciliationMasksEarlierSuccess(){
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(t->{UUID run=UUID.randomUUID();jdbc.update("INSERT INTO bitcoin_deposit_reconciliation_runs(id,receipt_id,network,wallet,scope,snapshot_at,status) VALUES(?,?,'bitcoin-regtest','cex_watch','PENDING_RECEIPT',clock_timestamp(),'RUNNING')",run,receipt.id());audit.record(null,"BTC_DEPOSIT_RECONCILIATION_STARTED","bitcoin-reconciliation:"+run,"SUCCESS","RECEIPT:"+receipt.id());});held(BitcoinCreditBasis.Reason.RECONCILIATION_REQUIRED);
    }
    @Test void latestFailedReconciliationMasksEarlierSuccess(){when(node.inspect(receipt.txid())).thenThrow(new IllegalStateException("격리 최신 노드 실패"));assertEquals(BitcoinDepositReconciliationService.Status.FAILED,reconciliation.reconcile(receipt.id()).status());clearInvocations(node);held(BitcoinCreditBasis.Reason.RECONCILIATION_REQUIRED);}
    @Test void pendingMonitoringInputCannotBorrowEarlierNoSignal(){var original=receipt;nativeReceipt();receipt=original;held(BitcoinCreditBasis.Reason.MONITORING_PENDING);}
    @Test void missingOriginDataIsNotWaivedForASmallReceipt(){publishPrice("1.00000000");nativeReceipt();monitoring.processNext().orElseThrow();nodeProof();held(BitcoinCreditBasis.Reason.ORIGIN_NOT_VERIFIED);}
    @Test void unavailableFrozenValuationDoesNotUseAnOldPrice(){when(priceClient.read()).thenThrow(new IllegalStateException("격리 현재 가격 실패"));assertEquals("FAILED",prices.refresh().status());clearInvocations(priceClient);nativeReceipt();monitoring.processNext().orElseThrow();nodeProof();held(BitcoinCreditBasis.Reason.VALUATION_UNAVAILABLE);}
    @Test void anotherUnvaluedInputKeepsAccountMonitoringIncomplete(){var original=receipt;when(priceClient.read()).thenThrow(new IllegalStateException("격리 현재 가격 실패"));prices.refresh();clearInvocations(priceClient);nativeReceipt();monitoring.processNext().orElseThrow();receipt=original;held(BitcoinCreditBasis.Reason.MONITORING_INCOMPLETE);}
    void openRiskCase(){publishPrice("1700000.00000000");for(int i=0;i<5;i++){var r=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,r,false);pending.recognize(r.id());monitoring.processNext().orElseThrow();}}
    void closeRiskCase()throws Exception{
        UUID caseId=jdbc.queryForObject("SELECT case_id FROM deposit_monitoring_case_heads WHERE user_id=?",UUID.class,customer.getId());var members=cases.members(maker.user().getId(),maker.sid(),caseId,null);var last=members.items().getFirst();var view=investigations.inspect(maker.user().getId(),maker.sid(),last.receiptId(),last.assessmentId());investigations.record(maker.user().getId(),maker.sid(),last.receiptId(),last.assessmentId(),new DepositMonitoringInvestigations.Write(view.viewId(),UUID.randomUUID(),DepositMonitoringInvestigations.Finding.EXPLAINED_ACTIVITY,"확인한 반복 입금의 설명 자료"));
        var scope=reviews.context(maker.user().getId(),maker.sid(),caseId,null);
        for(var actor:List.of(maker,checker))for(var member:members.items()){investigations.inspect(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId());for(var note:investigations.observations(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId(),0))investigations.inspectObservation(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId(),note.id());}
        var review=reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),scope,new DepositMonitoringCaseReviews.Facts(DepositMonitoringCaseReviews.Finding.EXPLAINED_ACTIVITY,"기록한 자료 범위의 설명된 활동"));reviews.inspect(checker.user().getId(),checker.sid(),review.payload().id());var options=reviews.options(checker.user().getId(),checker.sid(),review.payload().id(),review.payloadHash(),true).authentication();var proof=new dustin.cex.domains.security.OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));reviews.decide(checker.user().getId(),checker.sid(),review.payload().id(),review.payloadHash(),true,proof);
        reviews.inspect(maker.user().getId(),maker.sid(),review.payload().id());var request=actions.propose(maker.user().getId(),maker.sid(),review.payload().id(),DepositMonitoringCaseDispositions.Action.CLOSE_EXPLAINED,UUID.randomUUID());options=actions.options(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true).authentication();proof=new dustin.cex.domains.security.OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));actions.decide(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true,proof);
    }
    @Test void openHistoricalMonitoringCaseBlocksNormalOrigin(){openRiskCase();held(BitcoinCreditBasis.Reason.MONITORING_REVIEW_REQUIRED);}
    @Test void independentlyClosedExplainedCaseAllowsFreshPolicyEvaluation()throws Exception{openRiskCase();closeRiskCase();assertTrue(credits.reserve(receipt.id()).created());}
    @Test void closingCaseDoesNotOverrideCurrentCounterpartyPolicy()throws Exception{openRiskCase();closeRiskCase();unavailable(withGate(gate(jdbc,crypto,new CodeVaspCounterpartyPolicy("[]"))));}
    @Test void removedCounterpartyCannotUseOldReceivedData(){unavailable(withGate(gate(jdbc,crypto,new CodeVaspCounterpartyPolicy("[]"))));}
    @Test void changedLegalCounterpartyCannotUseOldData(){unavailable(withGate(gate(jdbc,crypto,new CodeVaspCounterpartyPolicy(APPROVAL.replace("가상 송신 사업자","다른 법인")))));}
    @Test void failedCurrentDirectoryCannotFallBackToOldList(){when(directoryClient.read()).thenThrow(new IllegalStateException("격리 현재 사업자 목록 실패"));assertEquals("FAILED",directory.refresh().status());clearInvocations(directoryClient);unavailable(credits);}
    @Test void modifiedDeclarationMacIsRejectedWithoutTouchingOriginal(){var guarded=corrupt("cd.content_mac declaration_mac",row->row.put("declaration_mac","0".repeat(64)));unavailable(withGate(gate(guarded,crypto,configuredPeers)));assertTrue(credits.reserve(receipt.id()).created());}
    @Test void unsupportedKeyEpochCannotUseAnotherKey(){var key=new dustin.cex.domains.cdd.CddCrypto(Base64.getEncoder().encodeToString(new byte[32]),Base64.getEncoder().encodeToString(new byte[]{1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}),"");unavailable(withGate(gate(jdbc,key,configuredPeers)));}
    @Test void changedReconciliationHashCannotBeTrusted(){var guarded=corrupt("SELECT kind,payload,payload_hash FROM bitcoin_deposit_reconciliation_evidence",row->row.put("payload_hash","0".repeat(64)));unavailable(withGate(gate(guarded,crypto,configuredPeers)));}
    @Test void newPublicHashCannotHideAnotherNodeOutput(){var guarded=corrupt("SELECT kind,payload,payload_hash FROM bitcoin_deposit_reconciliation_evidence",row->{if("NODE".equals(row.get("kind"))){var tree=(tools.jackson.databind.node.ObjectNode)json.readTree((String)row.get("payload"));((tools.jackson.databind.node.ObjectNode)tree.path("outputs").get(0)).put("vout",1);String raw=json.writeValueAsString(tree);row.put("payload",raw);row.put("payload_hash",sha(raw));}});unavailable(withGate(gate(guarded,crypto,configuredPeers)));}
    @Test void intentAuditExceptionRollsBackOnlyTheNewIntent(){doThrow(new IllegalStateException("격리 반입 의도 감사 장애")).when(audit).record(isNull(),eq("BTC_CREDIT_INTENT_AUTHORIZED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->credits.reserve(receipt.id()));assertEquals(0,creditCount());assertEquals("DATA_RECEIVED",jdbc.queryForObject("SELECT status FROM codevasp_transfer_results WHERE attempt_id=?",String.class,received.attemptId()));}
    @Test void silentlyOmittedIntentAuditCannotCommit(){doNothing().when(audit).record(isNull(),eq("BTC_CREDIT_INTENT_AUTHORIZED"),anyString(),anyString(),anyString());var failure=assertThrows(RuntimeException.class,()->credits.reserve(receipt.id()));assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure).getMessage().contains("입금 반입 의도에는 같은 근거 지문의 필수 감사가 필요합니다"));assertEquals(0,creditCount());}
    @Test void forgedAmountAndAssessmentCannotPassWithANewHash(){var basis=json.valueToTree(capture());for(boolean changeAmount:List.of(true,false)){var changed=(tools.jackson.databind.node.ObjectNode)json.readTree(json.writeValueAsString(basis));if(changeAmount)((tools.jackson.databind.node.ObjectNode)changed.path("funds")).put("amount","1.00000000");else ((tools.jackson.databind.node.ObjectNode)changed.path("identity")).put("assessmentId",UUID.randomUUID().toString());String raw=json.writeValueAsString(changed);var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_credit_intents(receipt_id,user_id,asset,amount,policy_id,basis,basis_hash) VALUES(?,?,'BTC',?,?,?,?)",receipt.id(),customer.getId(),changeAmount?BigDecimal.ONE:new BigDecimal("0.12345678"),BitcoinCreditBasis.POLICY,raw,sha(raw)));assertTrue(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure).getMessage().contains("입금 의도와 현재 고객"));}assertEquals(0,creditCount());}
    @Test void immutableIntentCannotBeRewrittenDeletedOrCleared(){credits.reserve(receipt.id());for(String sql:List.of("UPDATE bitcoin_credit_intents SET amount=amount","DELETE FROM bitcoin_credit_intents","TRUNCATE bitcoin_credit_intents"))assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.execute(sql));assertEquals(1,creditCount());}
    @Test void historicalIntentIsNotFreshFinancialPermission(){var old=credits.reserve(receipt.id()).intent();jdbc.update("UPDATE users SET enabled=false WHERE id=?",customer.getId());var replay=credits.reserve(receipt.id());assertFalse(replay.created());assertEquals(old,replay.intent());assertThrows(BitcoinCreditBasis.Held.class,this::capture);assertEquals(1,creditCount());}
    @Test void nullMissingAndAmbientTransactionsNeverCreateAnIntent(){assertThrows(IllegalArgumentException.class,()->credits.reserve(null));assertEquals(404,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->credits.reserve(UUID.randomUUID())).getStatusCode().value());assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(t->credits.reserve(receipt.id())));assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->creditBasis.require(receipt.id(),customer));assertEquals(0,creditCount());}

}
