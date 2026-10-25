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

/** 실제 원장·출처·탐지·대사를 준비하는 공통 시험 원본이다. 자체 테스트를 상속 복제하지 않는다. */
abstract class BitcoinCreditTestSupport extends CodeVaspTransferTestSupport {
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
    void openRiskCase(){publishPrice("1700000.00000000");for(int i=0;i<5;i++){var r=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,r,false);pending.recognize(r.id());monitoring.processNext().orElseThrow();}}
    void closeRiskCase()throws Exception{
        UUID caseId=jdbc.queryForObject("SELECT case_id FROM deposit_monitoring_case_heads WHERE user_id=?",UUID.class,customer.getId());var members=cases.members(maker.user().getId(),maker.sid(),caseId,null);var last=members.items().getFirst();var view=investigations.inspect(maker.user().getId(),maker.sid(),last.receiptId(),last.assessmentId());investigations.record(maker.user().getId(),maker.sid(),last.receiptId(),last.assessmentId(),new DepositMonitoringInvestigations.Write(view.viewId(),UUID.randomUUID(),DepositMonitoringInvestigations.Finding.EXPLAINED_ACTIVITY,"확인한 반복 입금의 설명 자료"));
        var scope=reviews.context(maker.user().getId(),maker.sid(),caseId,null);
        for(var actor:List.of(maker,checker))for(var member:members.items()){investigations.inspect(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId());for(var note:investigations.observations(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId(),0))investigations.inspectObservation(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId(),note.id());}
        var review=reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),scope,new DepositMonitoringCaseReviews.Facts(DepositMonitoringCaseReviews.Finding.EXPLAINED_ACTIVITY,"기록한 자료 범위의 설명된 활동"));reviews.inspect(checker.user().getId(),checker.sid(),review.payload().id());var options=reviews.options(checker.user().getId(),checker.sid(),review.payload().id(),review.payloadHash(),true).authentication();var proof=new dustin.cex.domains.security.OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));reviews.decide(checker.user().getId(),checker.sid(),review.payload().id(),review.payloadHash(),true,proof);
        reviews.inspect(maker.user().getId(),maker.sid(),review.payload().id());var request=actions.propose(maker.user().getId(),maker.sid(),review.payload().id(),DepositMonitoringCaseDispositions.Action.CLOSE_EXPLAINED,UUID.randomUUID());options=actions.options(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true).authentication();proof=new dustin.cex.domains.security.OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));actions.decide(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true,proof);
    }
}
