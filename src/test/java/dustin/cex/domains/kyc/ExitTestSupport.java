package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.edd.*;
import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 실제 회부·별도 업무 서명·현재 근거·역할 및 인물 배정·감사 원자성을 격리 PostgreSQL에서 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_TEST_DATABASE_URL",matches=".+")
abstract class ExitTestSupport extends EddTestSupport {
    @Autowired dustin.cex.domains.order.service.OrderService orders;
    @Autowired dustin.cex.domains.balance.repository.UserBalanceRepository balances;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @Autowired dustin.cex.domains.fee.service.FeeConfigService feePolicies;
    @Autowired ComplianceReferralBasis referralBasis;
    @Autowired EvidenceCrypto reportingCrypto;@Autowired RelationshipExitService reporting;@Autowired EvidenceService evidence;
    EddService.Report referral;
    RelationshipExitService.Facts facts() {return new RelationshipExitService.Facts(true,"요청한 확인 자료로 고객확인을 완료할 수 없어 관계 종료 절차를 개시할 필요가 있음");}
    void referred() throws Exception {reviewed();referral=propose(EddService.Finding.COMPLIANCE_ESCALATION);decide(referral,true,proof(checker,referral,true));assertEquals("REFERRED",service.status(customer.getId()).state());}
    RelationshipExitService.Review request(UUID key,RelationshipExitService.Facts f) {return reporting.propose(maker.user().getId(),maker.sid(),referral.payload().id(),checker.user().getId(),key,f);}
    RelationshipExitService.Review prepared() throws Exception {referred();return request(UUID.randomUUID(),facts());}
    void inspectAll(Account actor,RelationshipExitService.Review r) {for(int page=0;page<r.payload().dossier().pageCount();page++)reporting.inspect(actor.user().getId(),actor.sid(),r.payload().id(),page);}
    OperatorMfaService.AuthorizationProof reportProof(Account actor,RelationshipExitService.Review r,boolean approve) throws Exception {
        inspectAll(actor,r);var o=reporting.options(actor.user().getId(),actor.sid(),r.payload().id(),r.payloadHash(),approve).authentication();return new OperatorMfaService.AuthorizationProof(o.challengeId(),actor.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    RelationshipExitService.Review reportDecide(RelationshipExitService.Review r,boolean approve,OperatorMfaService.AuthorizationProof p) {return reporting.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve,p);}
    long count(String table) {return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+(table.equals("relationship_exit_decisions")?"review_id IN (SELECT id FROM relationship_exit_requests WHERE user_id=?)":"user_id=?"),Long.class,customer.getId());}
    @Autowired ExitRescissionService rescissions;
    RelationshipExitService.Review original;Account independent;
    ExitRescissionService.Facts withdrawalFacts() {return new ExitRescissionService.Facts(true,"원래 종료 개시의 잘못된 판단을 재검토하고 별도 효과만 철회할 필요가 있음");}
    @BeforeEach void rescissionSetup() throws Exception {independent=account(UserRole.APPROVER);}
    void initiated() throws Exception {original=prepared();reportDecide(original,true,reportProof(checker,original,true));}
    ExitRescissionService.Request withdraw(UUID key) {return rescissions.propose(maker.user().getId(),maker.sid(),original.payload().id(),independent.user().getId(),key,withdrawalFacts());}
    void withdrawalViews(Account actor,ExitRescissionService.Request r) {
        for(int page=0;page<r.payload().current().dossier().pageCount();page++)rescissions.inspect(actor.user().getId(),actor.sid(),r.payload().id(),ExitRescissionService.Basis.CURRENT,page);
        if(!r.payload().originalDossier().digest().equals(r.payload().current().dossier().digest()))for(int page=0;page<r.payload().originalDossier().pageCount();page++)rescissions.inspect(actor.user().getId(),actor.sid(),r.payload().id(),ExitRescissionService.Basis.ORIGINAL,page);
    }
    OperatorMfaService.AuthorizationProof withdrawalProof(Account actor,ExitRescissionService.Request r,boolean approve) throws Exception {
        withdrawalViews(actor,r);var o=rescissions.options(actor.user().getId(),actor.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return new OperatorMfaService.AuthorizationProof(o.challengeId(),actor.key().assertion(o,"http://localhost:3003",true,counter.incrementAndGet(),null));
    }
    ExitRescissionService.Request withdrawDecide(ExitRescissionService.Request r,boolean approve,OperatorMfaService.AuthorizationProof proof) {return rescissions.decide(independent.user().getId(),independent.sid(),r.payload().id(),r.payloadHash(),approve,proof);}
    void approveWithdrawal(ExitRescissionService.Request r) throws Exception {withdrawDecide(r,true,withdrawalProof(independent,r,true));}
    void clearedAfterExit() throws Exception {corrected();var correction=propose(EddService.Finding.CORRECTED_SIGNED_IDENTITY);decide(correction,true,proof(checker,correction,true));assertEquals(Boolean.TRUE,new TransactionTemplate(manager).execute(t->cdd.permitted(customer.getId())));blocked();}
    long active() {return jdbc.queryForObject("SELECT count(*) FROM relationship_active_exits WHERE user_id=?",Long.class,customer.getId());}
}
