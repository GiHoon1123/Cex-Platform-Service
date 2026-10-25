package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.auth.model.entity.UserRole;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 현재 운영 인증과 합성 금융 원본을 실제 HTTP/PG에서 비교한다. 조회를 외부 이전거래 심사로 사용하지 않는다. */
class BitcoinDepositReviewSourceIntegrationTest extends EddTestSupport {
    @DynamicPropertySource static void bitcoin(DynamicPropertyRegistry r){
        r.add("bitcoin.rpc-url",()->System.getenv("CEX_BITCOIN_TEST_URL"));r.add("bitcoin.rpc-user",()->System.getenv("CEX_BITCOIN_TEST_USER"));r.add("bitcoin.rpc-password",()->System.getenv("CEX_BITCOIN_TEST_PASSWORD"));r.add("bitcoin.wallet",()->"cex_watch");r.add("bitcoin.network",()->"REGTEST");
    }
    @Autowired BitcoinDepositReviewSource source;
    @Autowired dustin.cex.domains.travelrule.CodeVaspDepositSearchReview originSearch;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired BitcoinSettings settings;
    @Autowired dustin.cex.domains.security.OperatorSessionPolicy operatorSessions;
    @Autowired dustin.cex.domains.security.CustomerFinancialEligibility eligibility;
    @Autowired dustin.cex.domains.ledger.LedgerService ledger;
    @MockitoSpyBean BitcoinDepositReader reader;
    BitcoinDepositFixtures.Seed seed(){return BitcoinDepositFixtures.seed(jdbc,json,customer.getId(),Instant.now().minusSeconds(1));}
    long observe(BitcoinDepositFixtures.Seed value,boolean stale){return BitcoinDepositFixtures.observe(jdbc,json,manager,qualification,value,stale);}
    void opening(){
        // 같은 최초 원장을 삭제/재생성하지 않는다. 시험 묶음이 이미 반입한 원본을 보존한다.
        if(jdbc.queryForObject("SELECT count(*) FROM ledger_transactions WHERE operation_key='opening'",Long.class)==0)ledger.initializeOpening();
    }
    BitcoinDepositReader.Observation observed(BitcoinDepositFixtures.Seed seed){return new BitcoinDepositReader.Observation("bitcoin-regtest",seed.txid(),BitcoinDepositReader.Status.CONFIRMED,6,false,false,"0".repeat(64),0L,"1".repeat(64),5L,List.of(new BitcoinDepositReader.Output(0,seed.address(),seed.script(),new java.math.BigDecimal("0.12345678"))));}
    @org.junit.jupiter.api.AfterEach void noExternalOrAvailableMutation(){verifyNoInteractions(reader,engine);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM user_balances",Long.class));}
    @Test void operatorApproverAndAuditorCanReadOnlyCurrentFinancialReferences()throws Exception{
        var seed=seed();var auditor=account(UserRole.AUDITOR);
        for(var actor:List.of(maker,checker,auditor)){
            var view=source.read(actor.user().getId(),actor.sid(),seed.id());assertEquals(seed.id(),view.depositId());assertEquals(customer.getId(),view.customer().id());assertEquals("0.12345678",view.amount());assertNotNull(view.readAt());assertFalse(view.customer().financiallyEligible());
            mvc.perform(get("/api/admin/deposits/BTC/"+seed.id()).header("Authorization","Bearer "+actor.access())).andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        }
    }
    @Test void customerAnonymousAndForeignSessionCannotUseReviewEndpoint()throws Exception{
        var seed=seed();mvc.perform(get("/api/admin/deposits/BTC/"+seed.id())).andExpect(status().isUnauthorized());mvc.perform(get("/api/admin/deposits/BTC/"+seed.id()).header("Authorization","Bearer "+customerAccess)).andExpect(status().isForbidden());
        for(UUID id:List.of(seed.id(),UUID.randomUUID()))assertEquals(403,assertThrows(ResponseStatusException.class,()->source.read(customer.getId(),sid,id)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->source.read(maker.user().getId(),checker.sid(),seed.id())).getStatusCode().value());
    }
    @Test void staleMfaOrRevokedSessionCannotReadStoredEvidence(){
        var seed=seed();var token=tokens.findBySessionId(maker.sid()).orElseThrow();token.setMfaVerifiedAt(LocalDateTime.now().minusMinutes(6));tokens.saveAndFlush(token);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->source.read(maker.user().getId(),maker.sid(),seed.id())).getStatusCode().value());
        token=tokens.findBySessionId(checker.sid()).orElseThrow();token.setRevoked(true);tokens.saveAndFlush(token);assertEquals(403,assertThrows(ResponseStatusException.class,()->source.read(checker.user().getId(),checker.sid(),seed.id())).getStatusCode().value());
    }
    @Test void expiredOperatorGrantCannotReusePreviouslyValidRole(){var seed=seed();var operator=users.findById(maker.user().getId()).orElseThrow();operator.setOperatorAccessExpiresAt(Instant.now().minusSeconds(1));users.saveAndFlush(operator);assertEquals(403,assertThrows(ResponseStatusException.class,()->source.read(maker.user().getId(),maker.sid(),seed.id())).getStatusCode().value());}
    @Test void absentObservationAndWalletDebitProofStayUnknown(){
        var view=source.read(maker.user().getId(),maker.sid(),seed().id());assertEquals("UNKNOWN",view.chain().status());assertNull(view.chain().observationId());assertNull(view.chain().walletDebit());assertFalse(view.chain().fresh());assertFalse(view.chain().decisionCurrent());assertFalse(view.pending().recognized());assertNull(view.reconciliation());
    }
    @Test void customerEligibilityReflectsCurrentIdentityRestrictionAndExpiry()throws Exception{
        var seed=seed();verify(false);allowed();var first=source.read(maker.user().getId(),maker.sid(),seed.id());assertTrue(first.customer().financiallyEligible());assertNotNull(first.customer().cddAssessmentId());assertNotNull(first.customer().kycAttemptId());
        var user=users.findById(customer.getId()).orElseThrow();user.setTradingRestricted(true);users.saveAndFlush(user);var restricted=source.read(maker.user().getId(),maker.sid(),seed.id());assertFalse(restricted.customer().financiallyEligible());assertTrue(restricted.customer().version()>first.customer().version());
        user=users.findById(customer.getId()).orElseThrow();user.setTradingRestricted(false);users.saveAndFlush(user);jdbc.update("UPDATE kyc_profiles SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?",customer.getId());assertFalse(source.read(maker.user().getId(),maker.sid(),seed.id()).customer().financiallyEligible());
    }
    @Test void currentChainAndPendingLedgerAreReportedWithoutClaimingRelease(){
        var seed=seed();long observation=observe(seed,false);opening();pending.recognize(seed.id());long entries=jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class);
        var view=source.read(maker.user().getId(),maker.sid(),seed.id());assertEquals(observation,view.chain().observationId());assertEquals("CONFIRMED",view.chain().status());assertTrue(view.chain().fresh());assertEquals(Boolean.FALSE,view.chain().walletDebit());assertEquals("CHAIN_READY",view.chain().decisionReason());assertTrue(view.chain().decisionCurrent());assertTrue(view.pending().recognized());assertEquals("btc:pending:"+seed.id(),view.pending().operationKey());assertEquals(entries,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class));assertFalse(view.customer().financiallyEligible());
        String detail=jdbc.queryForObject("SELECT detail FROM security_audit WHERE action='BTC_DEPOSIT_REVIEW_SOURCE_READ' AND actor_id=? AND target=?",String.class,maker.user().getId(),"bitcoin-deposit:"+seed.id());assertTrue(detail.contains("OBSERVATION:"+observation));assertTrue(detail.contains("DECISION:"+view.chain().decisionId()));assertTrue(detail.contains("ELIGIBLE:false"));
    }
    @Test void staleObservationIsVisibleWithFreshnessFalse(){var seed=seed();observe(seed,true);var view=source.read(maker.user().getId(),maker.sid(),seed.id());assertFalse(view.chain().fresh());assertEquals("STALE_OBSERVATION",view.chain().decisionReason());assertTrue(view.chain().decisionCurrent());}
    @Test void newlyObservedHeadCannotBorrowOldChainDecisionOrMissingWalletProof(){
        var seed=seed();long old=observe(seed,false);new TransactionTemplate(manager).executeWithoutResult(t->{
            long next=jdbc.queryForObject("""
                INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,read_started_at)
                 SELECT network,wallet,txid,status,confirmations,coinbase,block_hash,block_height,tip_hash,tip_height,outputs,clock_timestamp() FROM bitcoin_transaction_observations WHERE id=? RETURNING id
                """,Long.class,old);
            jdbc.update("UPDATE bitcoin_observation_heads SET observation_id=? WHERE txid=?",next,seed.txid());
        });
        var view=source.read(maker.user().getId(),maker.sid(),seed.id());assertNotEquals(old,view.chain().observationId());assertEquals("CHAIN_READY",view.chain().decisionReason());assertFalse(view.chain().decisionCurrent());assertNull(view.chain().walletDebit());
    }
    @Test void newestRunningReconciliationDoesNotFallBackToOlderMatchedResult(){
        var seed=seed();observe(seed,false);opening();pending.recognize(seed.id());var fake=mock(BitcoinDepositReader.class);when(fake.inspect(seed.txid())).thenReturn(observed(seed));
        var reconciliation=new BitcoinDepositReconciliationService(jdbc,fake,settings,json,audit,manager);var first=reconciliation.reconcile(seed.id());assertEquals(BitcoinDepositReconciliationService.Status.MATCHED,first.status());assertEquals(first.id(),source.read(maker.user().getId(),maker.sid(),seed.id()).reconciliation().id());
        doThrow(new IllegalStateException("격리 완료 감사 장애")).when(audit).record(isNull(),eq("BTC_DEPOSIT_RECONCILIATION_FINISHED"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->reconciliation.reconcile(seed.id()));
        var view=source.read(maker.user().getId(),maker.sid(),seed.id());assertEquals("RUNNING",view.reconciliation().status());assertNull(view.reconciliation().completedAt());assertNotEquals(first.id(),view.reconciliation().id());
    }
    @Test void responseOmitsIdentityDocumentsWalletSettingsAndSecrets()throws Exception{
        var seed=seed();String body=mvc.perform(get("/api/admin/deposits/BTC/"+seed.id()).header("Authorization","Bearer "+maker.access())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for(String hidden:List.of(customer.getEmail(),seed.script(),seed.address(),maker.sid().toString(),"password","ciphertext","fullName","dateOfBirth","scriptPubKey","rpc","wallet\"","releaseReady","approved"))assertFalse(body.contains(hidden));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_DEPOSIT_REVIEW_SOURCE_READ' AND actor_id=? AND target=?",Long.class,maker.user().getId(),"bitcoin-deposit:"+seed.id()));
    }
    @Test void auditFailurePreventsSuccessfulReadWithoutChangingFinance(){
        var seed=seed();long entries=jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class);doThrow(new IllegalStateException("격리 심사 근거 감사 장애")).when(audit).record(any(),eq("BTC_DEPOSIT_REVIEW_SOURCE_READ"),anyString(),anyString(),anyString());assertThrows(IllegalStateException.class,()->source.read(maker.user().getId(),maker.sid(),seed.id()));assertEquals(entries,jdbc.queryForObject("SELECT count(*) FROM ledger_entries",Long.class));
    }
    @Test void invalidMissingAndUnsupportedInputsAreRejectedAndAmbientTransactionIsForbidden()throws Exception{
        assertEquals(400,assertThrows(ResponseStatusException.class,()->source.read(maker.user().getId(),maker.sid(),null)).getStatusCode().value());assertEquals(404,assertThrows(ResponseStatusException.class,()->source.read(maker.user().getId(),maker.sid(),UUID.randomUUID())).getStatusCode().value());
        var existing=seed();assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->source.read(maker.user().getId(),maker.sid(),existing.id())));
        mvc.perform(get("/api/admin/deposits/BTC/not-a-uuid").header("Authorization","Bearer "+maker.access())).andExpect(status().isBadRequest());mvc.perform(get("/api/admin/deposits/ETH/"+UUID.randomUUID()).header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());mvc.perform(post("/api/admin/deposits/BTC/"+UUID.randomUUID()).header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());
    }
    @Test void differentConfiguredNetworkCannotReadReceiptThroughOperatorRole(){
        var seed=seed();var other=new BitcoinDepositReviewSource(users,operatorSessions,eligibility,new BitcoinSettings("","","","","MAINNET"),jdbc,audit,manager,originSearch);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->other.read(maker.user().getId(),maker.sid(),seed.id())).getStatusCode().value());
    }

}
