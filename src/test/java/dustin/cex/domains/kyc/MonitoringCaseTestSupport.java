package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.edd.EvidenceCrypto;
import dustin.cex.domains.monitoring.*;
import dustin.cex.domains.security.*;
import dustin.cex.domains.valuation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 수탁 입력·원본/의견 열람·소프트웨어 보안키를 사용하는 사건 업무의 공통 시험 준비다. */
abstract class MonitoringCaseTestSupport extends EddTestSupport {
    @DynamicPropertySource static void bitcoin(DynamicPropertyRegistry r){r.add("bitcoin.rpc-url",()->"http://127.0.0.1:1");r.add("bitcoin.rpc-user",()->"isolated");r.add("bitcoin.rpc-password",()->"isolated-case-review-password-at-least-32-bytes");r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");r.add("bitcoin.processing.enabled",()->false);r.add("valuation.upbit.enabled",()->true);}
    @Autowired DepositMonitoringCaseReviews reviews;
    @Autowired DepositMonitoringCases cases;
    @Autowired DepositMonitoringService monitoring;
    @Autowired DepositMonitoringInvestigations investigations;
    @Autowired CaseReviewEvidence evidence;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired BitcoinKrwPriceService prices;
    @Autowired EvidenceCrypto evidenceCrypto;
    @Autowired CddCrypto declarationCrypto;
    @MockitoBean BitcoinKrwPriceClient price;
    @MockitoBean BitcoinDepositReader reader;
    @MockitoBean(name="monitoringReviewClock") Clock reviewClock;
    final AtomicReference<Instant> time=new AtomicReference<>();
    UUID caseId,assessment;BitcoinDepositFixtures.Seed receipt;
    @BeforeEach void input(){
        time.set(Instant.now());when(reviewClock.instant()).thenAnswer(call->time.get());ledger.initializeOpening();
        when(price.read()).thenAnswer(call->{Instant now=Instant.ofEpochMilli(Instant.now().toEpochMilli());String payload=json.writeValueAsString(List.of(Map.of("market","KRW-BTC","trade_price",1700000,"trade_timestamp",now.toEpochMilli(),"timestamp",now.toEpochMilli())));return new BitcoinKrwPriceClient.Quote(new BigDecimal("1700000"),now,now,now,now,payload,sha(payload));});assertEquals("SUCCESS",prices.refresh().status());clearInvocations(price);
        for(int i=0;i<5;i++)add();caseId=jdbc.queryForObject("SELECT case_id FROM deposit_monitoring_case_heads WHERE user_id=?",UUID.class,customer.getId());note(assessment,receipt.id(),"분할 송금의 소명 자료 검토");
    }
    void add(){receipt=BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now());BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,receipt,false);pending.recognize(receipt.id());assessment=monitoring.processNext().orElseThrow().id();}
    void note(UUID id,UUID deposit,String text){var view=investigations.inspect(maker.user().getId(),maker.sid(),deposit,id);investigations.record(maker.user().getId(),maker.sid(),deposit,id,new DepositMonitoringInvestigations.Write(view.viewId(),UUID.randomUUID(),DepositMonitoringInvestigations.Finding.EXPLAINED_ACTIVITY,text));}
    CaseReviewEvidence.Position scope(){return reviews.context(maker.user().getId(),maker.sid(),caseId,null);}
    DepositMonitoringCaseReviews.Facts facts(){return new DepositMonitoringCaseReviews.Facts(DepositMonitoringCaseReviews.Finding.EXPLAINED_ACTIVITY,"확인한 거래 구간과 송금 자료의 설명을 검토했다");}
    void viewEvidence(Account actor,CaseReviewEvidence.Position scope){
        Long before=Long.parseLong(scope.throughCursor())+1;
        do{var page=cases.members(actor.user().getId(),actor.sid(),caseId,before);for(var member:page.items()){
            investigations.inspect(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId());
            for(var note:investigations.observations(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId(),0))investigations.inspectObservation(actor.user().getId(),actor.sid(),member.receiptId(),member.assessmentId(),note.id());
        }before=page.nextBefore()==null?null:Long.parseLong(page.nextBefore());}while(before!=null);
    }
    DepositMonitoringCaseReviews.Review propose(){var scope=scope();viewEvidence(maker,scope);return reviews.propose(maker.user().getId(),maker.sid(),caseId,checker.user().getId(),UUID.randomUUID(),scope,facts());}
    OperatorMfaService.AuthorizationProof proof(DepositMonitoringCaseReviews.Review r,boolean approve)throws Exception{
        viewEvidence(checker,r.payload().scope());reviews.inspect(checker.user().getId(),checker.sid(),r.payload().id());var options=reviews.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return new OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    DepositMonitoringCaseReviews.Review decide(DepositMonitoringCaseReviews.Review r,boolean approve,OperatorMfaService.AuthorizationProof proof){return reviews.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve,proof);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void rejects(int status,Runnable work){assertEquals(status,assertThrows(ResponseStatusException.class,work::run).getStatusCode().value());}
    void cause(String text,Runnable work){Throwable failed=assertThrows(RuntimeException.class,work::run);while(failed.getCause()!=null)failed=failed.getCause();assertTrue(failed.getMessage().contains(text),failed.toString());}
    DepositMonitoringCaseReviews service(JdbcTemplate database,EvidenceCrypto crypto){return new DepositMonitoringCaseReviews(database,users,operatorSessions(),evidence,crypto,declarationCrypto,json,mfa,audit,reviewClock,manager);}
    @Autowired OperatorSessionPolicy sessions;
    OperatorSessionPolicy operatorSessions(){return sessions;}
}
