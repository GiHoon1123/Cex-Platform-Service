package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.security.OperatorMfaService;
import dustin.cex.domains.travelrule.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

/** 실제 고객/운영 서명·원본·PG 잠금·암호/감사와 취소/실행 확정 경합을 검증한다. 키 사용·체인 지급은 하지 않는다. */
class BitcoinWithdrawalPayoutReviewIntegrationTest extends BitcoinWithdrawalPayoutTestSupport {
    @Test void independentSignatureApprovesExactFundsAndCommitDoesNotPay()throws Exception {
        var r=proposal();assertEquals("PENDING",r.status());var f=r.payload().basis().funds();assertEquals(withdrawal,f.withdrawalId());assertEquals("0.01000000",f.amount());assertEquals("0.00000100",f.fee());assertEquals("0.01000100",f.total());assertEquals(ADDRESS,f.address());
        assertEquals("APPROVED",decide(r,true).status());assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));
        assertEquals("APPROVED",payouts.commit(r.payload().id()).status());assertEquals("APPROVED",payouts.commit(r.payload().id()).status());
        assertEquals(1,count("bitcoin_withdrawal_payout_commitments"));assertEquals("PAYOUT_COMMITTED",withdrawals.status(customer.getId(),sid,withdrawal).state());
        assertEquals(409,assertThrows(ResponseStatusException.class,this::cancel).getStatusCode().value());
    }
    @Test void approvalRemainsCancellableUntilExecutionCommit()throws Exception {
        var r=proposal();decide(r,true);cancel();assertEquals("CANCELLATION_PENDING",withdrawals.status(customer.getId(),sid,withdrawal).state());
        assertThrows(ResponseStatusException.class,()->payouts.commit(r.payload().id()));assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));assertEquals(1,count("bitcoin_withdrawal_payout_decisions"));
    }
    @Test void databaseAlsoRejectsCancellationAfterExecutionCommit()throws Exception {
        var r=proposal();decide(r,true);payouts.commit(r.payload().id());
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_withdrawal_cancellations(withdrawal_id,user_id,session_id) VALUES(?,?,?)",withdrawal,customer.getId(),sid));
        assertEquals(0,count("bitcoin_withdrawal_cancellations"));
    }
    @Test void simultaneousCancellationAndExecutionCommitHaveExactlyOneWinner()throws Exception {
        var r=proposal();decide(r,true);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));try{payouts.commit(r.payload().id());return true;}catch(ResponseStatusException held){return false;}});
            var b=pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));try{cancellations.cancel(customer.getId(),sid,withdrawal);return true;}catch(ResponseStatusException held){return false;}});
            start.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }clearInvocations(engineWithdrawal);assertEquals(1,count("bitcoin_withdrawal_cancellations")+count("bitcoin_withdrawal_payout_commitments"));
    }
    @Test void aNewCustomerRestrictionBlocksPreviouslyApprovedExecution()throws Exception {
        var r=proposal();decide(r,true);jdbc.update("UPDATE users SET trading_restricted=true WHERE id=?",customer.getId());
        assertThrows(ResponseStatusException.class,()->payouts.commit(r.payload().id()));assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));
    }
    @Test void changedCustomerVersionInvalidatesTheUnsignedReviewBeforeSignatureConsumption()throws Exception {
        var r=proposal();payouts.inspect(checker.user().getId(),checker.sid(),r.payload().id());var proof=signature(r,true);jdbc.update("UPDATE users SET version=version+1 WHERE id=?",customer.getId());
        assertThrows(ResponseStatusException.class,()->payouts.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true,proof));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));assertEquals(0,count("bitcoin_withdrawal_payout_decisions"));
    }
    @Test void checkerMustInspectTheExactPayloadInItsCurrentSession() {
        var r=proposal();assertEquals(409,assertThrows(ResponseStatusException.class,()->payouts.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true)).getStatusCode().value());
        payouts.inspect(maker.user().getId(),maker.sid(),r.payload().id());assertThrows(ResponseStatusException.class,()->payouts.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true));
    }
    @Test void anotherOperatorCannotInspectTheEncryptedOpinion()throws Exception {
        var r=proposal();var other=account(UserRole.OPERATOR);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->payouts.inspect(other.user().getId(),other.sid(),r.payload().id())).getStatusCode().value());assertEquals(0,count("bitcoin_withdrawal_payout_views"));
    }
    @Test void separateAccountsForTheSamePersonCannotSelfApprove() {
        jdbc.update("UPDATE users SET operator_person_id=? WHERE id=?",maker.user().getOperatorPersonId(),checker.user().getId());
        assertEquals(403,assertThrows(ResponseStatusException.class,this::proposal).getStatusCode().value());assertEquals(0,count("bitcoin_withdrawal_payout_reviews"));
    }
    @Test void missingProofCannotApproveAnInspectedReview() {
        var r=proposal();payouts.inspect(checker.user().getId(),checker.sid(),r.payload().id());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->payouts.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true,null)).getStatusCode().value());assertEquals(0,count("bitcoin_withdrawal_payout_decisions"));
    }
    @Test void rejectionSignatureCannotBeUsedAsPayoutApproval()throws Exception {
        var r=proposal();payouts.inspect(checker.user().getId(),checker.sid(),r.payload().id());var proof=signature(r,false);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->payouts.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true,proof)).getStatusCode().value());assertEquals(0,count("bitcoin_withdrawal_payout_decisions"));
    }
    @Test void changedHashCannotConsumeTheOriginalSignature()throws Exception {
        var r=proposal();payouts.inspect(checker.user().getId(),checker.sid(),r.payload().id());var proof=signature(r,true);
        assertThrows(ResponseStatusException.class,()->payouts.decide(checker.user().getId(),checker.sid(),r.payload().id(),"f".repeat(64),true,proof));
        assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));
    }
    @Test void sameRequestKeyReplaysTheSameOpinionButCannotChangeIt() {
        UUID key=UUID.randomUUID();var r=payouts.propose(maker.user().getId(),maker.sid(),transfer,checker.user().getId(),key,NOTE);
        assertEquals(r,payouts.propose(maker.user().getId(),maker.sid(),transfer,checker.user().getId(),key,NOTE));
        assertThrows(ResponseStatusException.class,()->payouts.propose(maker.user().getId(),maker.sid(),transfer,checker.user().getId(),key,NOTE+" 변경"));assertEquals(1,count("bitcoin_withdrawal_payout_reviews"));
    }
    @Test void newRequestKeyCannotSupersedeAPendingOrApprovedReview()throws Exception {
        var r=proposal();assertThrows(ResponseStatusException.class,this::proposal);decide(r,true);assertThrows(ResponseStatusException.class,this::proposal);assertEquals(1,count("bitcoin_withdrawal_payout_reviews"));
    }
    @Test void rejectionPreservesFundsAndAllowsANewExplicitReview()throws Exception {
        var r=proposal();assertEquals("REJECTED",decide(r,false).status());var next=proposal();assertNotEquals(r.payload().id(),next.payload().id());
        assertThrows(ResponseStatusException.class,()->payouts.commit(r.payload().id()));assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));assertEquals(0,count("bitcoin_withdrawal_cancellations"));
    }
    @Test void missingProposalAuditRollsBackTheEncryptedReviewAndBinding() {
        doNothing().when(audit).record(anyLong(),eq("BTC_PAYOUT_REVIEW_PROPOSED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(DataAccessException.class,this::proposal);reset(audit);assertEquals(0,count("bitcoin_withdrawal_payout_reviews"));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM operator_approval_bindings WHERE kind='BTC_WITHDRAWAL_PAYOUT'",Long.class));
    }
    @Test void missingInspectAuditCannotSatisfyTheApprovalPrerequisite() {
        var r=proposal();doNothing().when(audit).record(anyLong(),eq("BTC_PAYOUT_REVIEW_VIEWED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(DataAccessException.class,()->payouts.inspect(checker.user().getId(),checker.sid(),r.payload().id()));reset(audit);assertEquals(0,count("bitcoin_withdrawal_payout_views"));
    }
    @Test void decisionAuditFailureRollsBackBothTheDecisionAndConsumedSignature()throws Exception {
        var r=proposal();payouts.inspect(checker.user().getId(),checker.sid(),r.payload().id());var proof=signature(r,true);
        doThrow(new IllegalStateException("격리 지급 결정 감사 장애")).when(audit).record(anyLong(),eq("BTC_PAYOUT_REVIEW_DECIDED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(IllegalStateException.class,()->payouts.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true,proof));reset(audit);
        assertEquals(0,count("bitcoin_withdrawal_payout_decisions"));assertFalse(jdbc.queryForObject("SELECT consumed FROM operator_authorization_challenges WHERE id=?",Boolean.class,proof.challengeId()));
        assertEquals("APPROVED",payouts.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true,proof).status());
    }
    @Test void silentlyOmittedDecisionAuditCannotCommit()throws Exception {
        var r=proposal();payouts.inspect(checker.user().getId(),checker.sid(),r.payload().id());var proof=signature(r,true);
        doNothing().when(audit).record(anyLong(),eq("BTC_PAYOUT_REVIEW_DECIDED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(DataAccessException.class,()->payouts.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true,proof));reset(audit);assertEquals(0,count("bitcoin_withdrawal_payout_decisions"));
    }
    @Test void missingExecutionAuditLeavesTheCustomerFreeToCancel()throws Exception {
        var r=proposal();decide(r,true);doNothing().when(audit).record(isNull(),eq("BTC_PAYOUT_EXECUTION_COMMITTED"),anyString(),eq("SUCCESS"),anyString());
        assertThrows(DataAccessException.class,()->payouts.commit(r.payload().id()));reset(audit);assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));cancel();
    }
    @Test void encryptedOpinionAndAllApprovalFactsAreImmutable()throws Exception {
        var r=proposal();decide(r,true);payouts.commit(r.payload().id());
        var row=jdbc.queryForMap("SELECT * FROM bitcoin_withdrawal_payout_reviews WHERE id=?",r.payload().id());String raw=new String(crypto.decrypt(customer.getId(),r.payload().id(),(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
        assertEquals(crypto.fingerprint("btc-payout-review-v1",raw),row.get("payload_hash"));assertTrue(raw.contains(NOTE));assertFalse(new String((byte[])row.get("ciphertext"),StandardCharsets.UTF_8).contains(NOTE));assertFalse(r.toString().contains(NOTE));
        for(String table:List.of("bitcoin_withdrawal_payout_reviews","bitcoin_withdrawal_payout_views","bitcoin_withdrawal_payout_decisions","bitcoin_withdrawal_payout_commitments"))for(String sql:List.of("UPDATE "+table+" SET created_txid=0","DELETE FROM "+table,"TRUNCATE "+table+" CASCADE"))assertThrows(DataAccessException.class,()->jdbc.execute(sql));
    }
    @Test void simultaneousDecisionConsumesTheBoundSignatureOnlyOnce()throws Exception {
        var r=proposal();payouts.inspect(checker.user().getId(),checker.sid(),r.payload().id());var proof=signature(r,true);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> work=()->{assertTrue(start.await(5,TimeUnit.SECONDS));try{payouts.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),true,proof);return true;}catch(ResponseStatusException held){return false;}};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }assertEquals(1,count("bitcoin_withdrawal_payout_decisions"));
    }
    @Test void routineDirectoryRefreshDoesNotChangeTheApprovedEconomicScope()throws Exception {
        var r=proposal();long old=r.payload().basis().transfer().currentDirectoryAttempt();directory.refresh();clearInvocations(directoryClient);
        assertNotEquals(old,directory.current().attemptId());assertEquals("APPROVED",decide(r,true).status());payouts.commit(r.payload().id());
    }
    @Test void corruptedMaterializedBalanceCannotBecomeAPayoutPermission() {
        jdbc.update("UPDATE user_balances SET locked=locked+0.00000001 WHERE user_id=? AND mint_address='BTC'",customer.getId());
        try{assertThrows(ResponseStatusException.class,this::proposal);assertEquals(0,count("bitcoin_withdrawal_payout_reviews"));}
        finally{jdbc.update("UPDATE user_balances SET locked=locked-0.00000001 WHERE user_id=? AND mint_address='BTC'",customer.getId());}
    }
    @Test void laterMarketReferenceDoesNotChangeTheSignedWithdrawal()throws Exception {
        var r=proposal();doAnswer(call->referenceQuote(new BigDecimal("1"))).when(priceClient).read();referencePrices.refresh();assertEquals("APPROVED",decide(r,true).status());payouts.commit(r.payload().id());
    }
    @Test void missingCurrentCounterpartyBlocksNewPayoutPermission() {
        doAnswer(call->new CodeVaspDirectoryClient.Directory(Instant.now(),Instant.now(),List.of())).when(directoryClient).read();directory.refresh();clearInvocations(directoryClient);
        assertThrows(ResponseStatusException.class,this::proposal);assertEquals(0,count("bitcoin_withdrawal_payout_reviews"));
    }
    @Test void strictHttpRejectsInjectedFundsUnknownFieldsAndPublicCommitRoute()throws Exception {
        String url="/api/admin/withdrawals/BTC/payout-reviews";
        String input=json.writeValueAsString(Map.of("transferId",transfer,"checkerId",checker.user().getId(),"requestKey",UUID.randomUUID(),"note",NOTE,"amount","100"));
        mvc.perform(post(url).header("Authorization","Bearer "+maker.access()).contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization","Bearer "+customerAccess).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(url+"/"+UUID.randomUUID()+"/commit").header("Authorization","Bearer "+maker.access())).andExpect(status().isForbidden());assertEquals(0,count("bitcoin_withdrawal_payout_reviews"));
    }
    @Test void headlessHttpUsesTheAssignedInspectionAndActualBoundSignature()throws Exception {
        String url="/api/admin/withdrawals/BTC/payout-reviews";
        var result=mvc.perform(post(url).header("Authorization","Bearer "+maker.access()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("transferId",transfer,"checkerId",checker.user().getId(),"requestKey",UUID.randomUUID(),"note",NOTE)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID id=UUID.fromString(json.readTree(result).path("payload").path("id").asString());String hash=json.readTree(result).path("payloadHash").asString();
        mvc.perform(post(url+"/"+id+"/inspect").header("Authorization","Bearer "+checker.access())).andExpect(status().isOk());
        var optionResult=mvc.perform(post(url+"/"+id+"/approve/options").header("Authorization","Bearer "+checker.access()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("payloadHash",hash)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var node=json.readTree(optionResult).path("authentication");var options=new OperatorMfaService.Options(UUID.fromString(node.path("challengeId").asString()),node.path("publicKey"));
        var proof=new OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,approvalCounter++,null));
        mvc.perform(post(url+"/"+id+"/approve").header("Authorization","Bearer "+checker.access()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("payloadHash",hash,"proof",proof)))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));
    }
    @Test void recognizedButUnmonitoredNativeFundsBlockNewPayoutReview() {
        UUID deposit=nativeInput();pending.recognize(deposit);assertThrows(ResponseStatusException.class,this::proposal);
        assertEquals("NO_SIGNAL",monitoring.processNext().orElseThrow().result().outcome());assertEquals("PENDING",proposal().status());
    }
    @Test void aNewNativeInputAfterApprovalBlocksExecutionUntilANewReview()throws Exception {
        var r=proposal();decide(r,true);pending.recognize(nativeInput());assertThrows(ResponseStatusException.class,()->payouts.commit(r.payload().id()));
        monitoring.processNext().orElseThrow();assertThrows(ResponseStatusException.class,()->payouts.commit(r.payload().id()));assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));
        var fresh=proposal();assertNotEquals(r.payload().basis().scopeHash(),fresh.payload().basis().scopeHash());decide(fresh,true);payouts.commit(fresh.payload().id());
        assertEquals(2,count("bitcoin_withdrawal_payout_reviews"));assertEquals(1,count("bitcoin_withdrawal_payout_commitments"));
    }
    @Test void anOpenNativeMonitoringCaseCannotBeOverriddenByAPayoutOpinion() {
        doAnswer(call->referenceQuote(new BigDecimal("2000000"))).when(priceClient).read();referencePrices.refresh();
        for(int n=0;n<5;n++){pending.recognize(nativeInput());monitoring.processNext().orElseThrow();}
        assertEquals(1,count("deposit_monitoring_cases"));assertThrows(ResponseStatusException.class,this::proposal);assertEquals(0,count("bitcoin_withdrawal_payout_reviews"));
    }

    @Test void databaseCannotCommitAnApprovalAfterTheCurrentKycExpires()throws Exception {
        var r=proposal();decide(r,true);jdbc.update("UPDATE kyc_profiles SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?",customer.getId());
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_withdrawal_payout_commitments(withdrawal_id,review_id,user_id,payload_hash) VALUES(?,?,?,?)",withdrawal,r.payload().id(),customer.getId(),r.payloadHash()));
        assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));
    }
    @Test void databaseCannotCommitPastAnUnprocessedNativeInput()throws Exception {
        var r=proposal();decide(r,true);pending.recognize(nativeInput());
        assertThrows(DataAccessException.class,()->jdbc.update("INSERT INTO bitcoin_withdrawal_payout_commitments(withdrawal_id,review_id,user_id,payload_hash) VALUES(?,?,?,?)",withdrawal,r.payload().id(),customer.getId(),r.payloadHash()));
        assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));
    }

    @Test void temporaryChallengeCleanupPreservesThePermanentBoundDecision()throws Exception {
        var old=proposal();payouts.inspect(checker.user().getId(),checker.sid(),old.payload().id());var proof=signature(old,false);
        payouts.decide(checker.user().getId(),checker.sid(),old.payload().id(),old.payloadHash(),false,proof);
        var fresh=proposal();payouts.inspect(checker.user().getId(),checker.sid(),fresh.payload().id());signature(fresh,true);
        assertFalse(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM operator_authorization_challenges WHERE id=?)",Boolean.class,proof.challengeId()));
        assertEquals(proof.challengeId(),jdbc.queryForObject("SELECT authorization_id FROM bitcoin_withdrawal_payout_decisions WHERE review_id=?",UUID.class,old.payload().id()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='APPROVAL_AUTHORIZATION' AND target=?",Long.class,"request:"+old.payload().id()));
    }

}
