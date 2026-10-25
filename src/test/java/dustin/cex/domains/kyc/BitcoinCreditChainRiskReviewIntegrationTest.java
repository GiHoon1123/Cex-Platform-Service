package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.shared.http.EngineDepositClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 현재 운영 세션·역할·감사와 위험 원본 집계를 실제 PG/HTTP 경계에서 검증한다. */
class BitcoinCreditChainRiskReviewIntegrationTest extends BitcoinCreditTestSupport {
    @Autowired BitcoinCreditChainRiskReview riskReview;
    @Autowired BitcoinCreditDispatches dispatch;
    @Autowired BitcoinObservationService observations;
    @Autowired BitcoinDepositReviewSource depositReview;
    @BeforeEach void risk(){
        credits.reserve(receipt.id());when(depositEngine.credit(any())).thenReturn(EngineDepositClient.Outcome.OUTCOME_UNKNOWN);dispatch.execute(receipt.id()).orElseThrow();
        observe(BitcoinDepositReader.Status.UNCONFIRMED,0);clearInvocations(depositEngine,node);
    }
    long observe(BitcoinDepositReader.Status status,long confirmations){
        when(node.inspect(receipt.txid())).thenReturn(new BitcoinDepositReader.Observation("bitcoin-regtest",receipt.txid(),status,confirmations,false,false,
            status==BitcoinDepositReader.Status.CONFIRMED?BitcoinDepositFixtures.hex():null,status==BitcoinDepositReader.Status.CONFIRMED?0L:null,
            BitcoinDepositFixtures.hex(),status==BitcoinDepositReader.Status.CONFIRMED?confirmations-1:5,List.of(new BitcoinDepositReader.Output(0,receipt.address(),receipt.script(),new BigDecimal("0.12345678")))));
        var result=observations.refresh(receipt.txid());clearInvocations(node);return result.id();
    }
    @AfterEach void noFinancialOrNodeCommands(){verifyNoInteractions(depositEngine,node);}
    BitcoinCreditChainRiskReview.Page read(){return riskReview.read(maker.user().getId(),maker.sid(),0);}
    @Test void allThreeCurrentOperatorRolesCanReadTheSameMinimalRisk()throws Exception{
        var auditor=account(UserRole.AUDITOR);for(var actor:List.of(maker,checker,auditor)){
            var page=riskReview.read(actor.user().getId(),actor.sid(),0);assertEquals(0,page.page());assertEquals(1,page.items().size());var item=page.items().getFirst();assertEquals(receipt.id(),item.depositId());assertEquals(customer.getId().longValue(),item.customerId());assertEquals("BTC",item.asset());assertEquals("0.12345678",item.amount());assertEquals(1,item.observations());assertEquals("WAITING_CHAIN",item.latestReason());assertEquals(item.firstObservationId(),item.lastObservationId());assertEquals(item.firstRecordedAt(),item.lastRecordedAt());
        }
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_CREDIT_CHAIN_RISK_LIST_READ'",Long.class));
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_CREDIT_CHAIN_RISK_ITEM_READ' AND target=?",Long.class,"bitcoin-deposit:"+receipt.id()));
    }
    @Test void repeatedRiskObservationsAreOneDepositWithAnOrderedScope(){long newest=observe(BitcoinDepositReader.Status.CONFLICTED,-1);var page=read();assertEquals(1,page.items().size());var item=page.items().getFirst();assertEquals(2,item.observations());assertTrue(item.firstObservationId()<item.lastObservationId());assertEquals(newest,item.lastObservationId());assertEquals("CONFLICTED",item.latestReason());}
    @Test void laterHealthyChainDoesNotRemoveTheUnresolvedRisk(){observe(BitcoinDepositReader.Status.CONFIRMED,6);assertEquals(1,read().items().size());assertEquals("WAITING_CHAIN",read().items().getFirst().latestReason());}
    @Test void serializedListAndAuditDoNotCopyIdentityOrAddress(){String body=json.writeValueAsString(read());for(String privateValue:List.of(receipt.address(),receipt.script(),declaration(dustin.cex.domains.cdd.CustomerDeclaration.Pep.NO).fullName(),"1990-01-01"))assertFalse(body.contains(privateValue));for(String text:jdbc.queryForList("SELECT detail FROM security_audit WHERE action='BTC_CREDIT_CHAIN_RISK_LIST_READ'",String.class))for(String privateValue:List.of(receipt.address(),receipt.script(),customer.getEmail()))assertFalse(text.contains(privateValue));}
    @Test void customerAndMismatchedSessionCannotProbeTheList(){for(int page:List.of(-1,0,10001))assertEquals(403,assertThrows(ResponseStatusException.class,()->riskReview.read(customer.getId(),sid,page)).getStatusCode().value());assertEquals(403,assertThrows(ResponseStatusException.class,()->riskReview.read(maker.user().getId(),checker.sid(),0)).getStatusCode().value());}
    @Test void disabledOperatorCannotReuseTheRole(){var actor=users.findById(maker.user().getId()).orElseThrow();actor.setEnabled(false);users.saveAndFlush(actor);assertEquals(403,assertThrows(ResponseStatusException.class,()->read()).getStatusCode().value());}
    @Test void revokedSessionCannotReusePastMfa(){var token=tokens.findBySessionId(maker.sid()).orElseThrow();token.setRevoked(true);tokens.saveAndFlush(token);assertEquals(403,assertThrows(ResponseStatusException.class,()->read()).getStatusCode().value());}
    @Test void expiredOperatorGrantCannotReuseThePreviouslyValidRole(){var actor=users.findById(maker.user().getId()).orElseThrow();actor.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));users.saveAndFlush(actor);assertEquals(403,assertThrows(ResponseStatusException.class,()->read()).getStatusCode().value());}
    @Test void changedRoleVersionInvalidatesAnOldSession()throws Exception{
        // 역할 필드만 직접 바꾸는 대신 실제 독립 승인 경로가 기존 세션을 회수하는지 확인한다.
        var requester=account(UserRole.OPERATOR);var target=users.findById(maker.user().getId()).orElseThrow();
        var request=roles.request(requester.user().getId(),requester.sid(),target.getId(),target.getVersion(),target.getOperatorPersonId(),UserRole.APPROVER,Instant.now().plusSeconds(3600),"체인 위험 조회 담당 직무 변경","IDENTITY_CHAIN_RISK_TEST","NOTICE_CHAIN_RISK_TEST");
        var options=roles.options(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true).authentication();
        var proof=new dustin.cex.domains.security.OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,counter.incrementAndGet(),null));
        roles.decide(checker.user().getId(),checker.sid(),request.payload().id(),request.payloadHash(),true,proof);
        assertEquals(UserRole.APPROVER,users.findById(target.getId()).orElseThrow().getRole());assertTrue(tokens.findBySessionId(maker.sid()).orElseThrow().getRevoked());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->read()).getStatusCode().value());
    }
    @Test void pageBoundsAreCheckedAfterAuthorizationAndLargePageIsEmpty(){for(int page:List.of(-1,10001))assertEquals(400,assertThrows(ResponseStatusException.class,()->riskReview.read(maker.user().getId(),maker.sid(),page)).getStatusCode().value());assertTrue(riskReview.read(maker.user().getId(),maker.sid(),10000).items().isEmpty());}
    @Test void requiredReadAuditFailureDoesNotReturnAView(){long entries=jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class);doThrow(new IllegalStateException("격리 위험 목록 감사 실패")).when(audit).record(anyLong(),eq("BTC_CREDIT_CHAIN_RISK_LIST_READ"),anyString(),anyString(),anyString());assertThrows(RuntimeException.class,()->read());assertEquals(entries,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class));}
    @Test void missingRiskSourceCannotBecomeAnEmptySuccessfulList(){jdbc.execute("DROP VIEW bitcoin_credit_chain_risks");assertEquals(503,assertThrows(ResponseStatusException.class,()->read()).getStatusCode().value());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_CREDIT_CHAIN_RISK_LIST_READ' AND outcome='SUCCESS'",Long.class));}
    @Test void ambientTransactionCannotHoldLocksAcrossAListRead(){assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->read()));}
    @Test void theActualHttpRouteRequiresCurrentOperatorAuthentication()throws Exception{
        String uri="/api/admin/deposits/BTC/chain-risks";mvc.perform(get(uri)).andExpect(status().isUnauthorized());mvc.perform(get(uri).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());var response=mvc.perform(get(uri).header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();assertEquals("0.12345678",json.readTree(response).path("items").get(0).path("amount").asString());
    }
    @Test void existingDepositReviewSeparatesCurrentChainFromExecutionRisk(){long latest=observe(BitcoinDepositReader.Status.CONFIRMED,6);var view=depositReview.read(maker.user().getId(),maker.sid(),receipt.id());assertEquals(latest,view.chain().observationId());assertEquals("CONFIRMED",view.chain().status());assertFalse(view.customer().financiallyEligible());assertEquals(1,view.executionRisk().observations());assertEquals("WAITING_CHAIN",view.executionRisk().latestReason());assertTrue(view.executionRisk().lastObservationId()<latest);}
    @Test void actualHttpDetailReturnsTheRiskScopeWithoutMovingMoney()throws Exception{var response=mvc.perform(get("/api/admin/deposits/BTC/"+receipt.id()).header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();var risk=json.readTree(response).path("executionRisk");assertEquals(1,risk.path("observations").asLong());assertEquals("WAITING_CHAIN",risk.path("latestReason").asString());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));}

    @Test void itemAuditFailureRollsBackTheListAuditAndReturnsNoPartialView(){doThrow(new IllegalStateException("격리 개별 열람 감사 실패")).when(audit).record(anyLong(),eq("BTC_CREDIT_CHAIN_RISK_ITEM_READ"),anyString(),anyString(),anyString());assertThrows(RuntimeException.class,()->read());assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action IN ('BTC_CREDIT_CHAIN_RISK_LIST_READ','BTC_CREDIT_CHAIN_RISK_ITEM_READ')",Long.class));}
    @Test void listAndItemAuditsReferToTheExactReturnedSource(){var item=read().items().getFirst();String listTarget=jdbc.queryForObject("SELECT target FROM security_audit WHERE action='BTC_CREDIT_CHAIN_RISK_LIST_READ'",String.class);UUID id=UUID.fromString(listTarget.substring("bitcoin-chain-risks:".length()));assertEquals("LIST:"+id+":LAST_RISK:"+item.lastObservationId(),jdbc.queryForObject("SELECT detail FROM security_audit WHERE action='BTC_CREDIT_CHAIN_RISK_ITEM_READ' AND target=?",String.class,"bitcoin-deposit:"+receipt.id()));}

}
