package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 실제 승인·후보·PG 배정과 노드 시도/관측의 실패 경계를 검증한다. 키 사용과 지급은 하지 않는다. */
class BitcoinPayoutCandidateWalletIntegrationTest extends BitcoinPayoutCandidatesTestSupport {
    @Autowired BitcoinPayoutCandidateWallet wallet;
    @MockitoBean BitcoinPayoutInputLocks lockNode;
    BitcoinPayoutCandidates.Candidate candidate;
    @BeforeEach void preparedCandidate(){candidate=prepareCandidate().candidate();clearInvocations(node);
        when(lockNode.ensure(anyString(),anyString(),anyList())).thenAnswer(call->{assertOutsideTransactionAndStarted();return observation(true);});
        when(lockNode.release(anyString(),anyString(),anyList())).thenAnswer(call->{assertOutsideTransactionAndStarted();return observation(false);});}
    void assertOutsideTransactionAndStarted(){assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(count("bitcoin_payout_wallet_attempts")>0);assertEquals(1,count("bitcoin_payout_input_reservations"));}
    UUID id(){return candidate.payload().id();}
    BitcoinPayoutInputLocks.Observation observation(boolean locked){return new BitcoinPayoutInputLocks.Observation("bitcoin-regtest","watch",List.of(INPUT+":0"),locked,Instant.now());}
    void noPayment(){assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));assertEquals(0,count("bitcoin_payout_candidate_releases"));assertEquals(1,count("bitcoin_payout_input_reservations"));}
    void waitRetry()throws Exception {
        // 금융 불변 기록의 시각을 수정하거나 트리거를 끄지 않는다. 실제 대기 계약을 지나 같은 후보로 재확인한다.
        Thread.sleep(31000);
    }
    @Test void lockObservationIsEncryptedAndBoundToTheOriginalCandidate(){var r=wallet.lock(id());assertEquals("OBSERVED",r.status());assertFalse(r.allocationReleased());var row=jdbc.queryForMap("SELECT * FROM bitcoin_payout_wallet_results WHERE attempt_id=?",r.attemptId());String raw=new String(crypto.decrypt(customer.getId(),r.attemptId(),(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);var p=json.readValue(raw,BitcoinPayoutCandidateWallet.Proof.class);assertEquals(id(),p.candidateId());assertEquals(candidate.payloadHash(),p.candidateHash());assertEquals("LOCK",p.operation());assertTrue(p.observation().locked());assertEquals(crypto.fingerprint("btc-payout-wallet-observation-v1",raw),row.get("content_mac"));assertFalse(r.toString().contains(INPUT));verifyNoInteractions(node);noPayment();}
    @Test void nodeFailureLeavesAnUnknownResultAndRetainsTheFinancialAllocation(){doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(lockNode).ensure(anyString(),anyString(),anyList());var r=wallet.lock(id());assertEquals("UNKNOWN",r.status());assertFalse(r.allocationReleased());assertNull(jdbc.queryForObject("SELECT ciphertext FROM bitcoin_payout_wallet_results WHERE attempt_id=?",byte[].class,r.attemptId()));noPayment();}
    @Test void aMismatchedObservationCannotClaimTheRequestedInputsAreLocked(){doReturn(new BitcoinPayoutInputLocks.Observation("bitcoin-regtest","watch",List.of("f".repeat(64)+":0"),true,Instant.now())).when(lockNode).ensure(anyString(),anyString(),anyList());assertEquals("UNKNOWN",wallet.lock(id()).status());noPayment();}
    @Test void currentCancellationBeforeLockPreventsNodeIo(){cancel();assertThrows(ResponseStatusException.class,()->wallet.lock(id()));verifyNoInteractions(lockNode);noPayment();}
    @Test void cancellationDuringLockPreservesTheObservationWithoutRevivingAuthority(){doAnswer(call->{cancel();return observation(true);}).when(lockNode).ensure(anyString(),anyString(),anyList());assertEquals("OBSERVED",wallet.lock(id()).status());assertThrows(ResponseStatusException.class,()->wallet.lock(id()));noPayment();}
    @Test void immediateRepeatedLockCannotOverlapTheSameCandidate(){wallet.lock(id());clearInvocations(lockNode);assertThrows(ResponseStatusException.class,()->wallet.lock(id()));verifyNoInteractions(lockNode);assertEquals(1,count("bitcoin_payout_wallet_attempts"));noPayment();}
    @Test void missingStartAuditPreventsAllNodeCalls(){doNothing().when(audit).record(isNull(),eq("BTC_PAYOUT_WALLET_STARTED"),anyString(),eq("SUCCESS"),anyString());try{assertThrows(DataAccessException.class,()->wallet.lock(id()));}finally{reset(audit);}verifyNoInteractions(lockNode);assertEquals(0,count("bitcoin_payout_wallet_attempts"));noPayment();}
    @Test void missingObservationAuditLeavesTheStartedAttemptUnresolved(){doNothing().when(audit).record(isNull(),eq("BTC_PAYOUT_WALLET_OBSERVED"),anyString(),eq("SUCCESS"),anyString());try{assertThrows(DataAccessException.class,()->wallet.lock(id()));}finally{reset(audit);}assertEquals(1,count("bitcoin_payout_wallet_attempts"));assertEquals(0,count("bitcoin_payout_wallet_results"));noPayment();}
    @Test void validApprovalCannotReleaseItsInputsWithoutRetirement(){assertThrows(ResponseStatusException.class,()->wallet.release(id()));verifyNoInteractions(lockNode);noPayment();}
    @Test void neverLockedCancellationClosesOnlyCurrentAllocationsAndPreservesAllHistory(){cancel();var r=wallet.release(id());assertTrue(r.allocationReleased());assertEquals("RELEASED",r.status());assertEquals(1,count("bitcoin_payout_candidates"));assertEquals(1,count("bitcoin_payout_candidate_inputs"));assertEquals(1,count("bitcoin_payout_candidate_releases"));assertEquals(0,count("bitcoin_payout_active_candidates"));assertEquals(0,count("bitcoin_payout_input_reservations"));assertTrue(wallet.release(id()).allocationReleased());verifyNoInteractions(lockNode,node);assertThrows(ResponseStatusException.class,()->wallet.lock(id()));}
    @Test void missingReleaseAuditRollsBackTheReleaseAndCurrentAllocationDeletion(){cancel();doNothing().when(audit).record(isNull(),eq("BTC_PAYOUT_CANDIDATE_RELEASED"),anyString(),eq("SUCCESS"),anyString());try{assertThrows(DataAccessException.class,()->wallet.release(id()));}finally{reset(audit);}verifyNoInteractions(lockNode);noPayment();}
    @Test void directAllocationDeletionWithoutReleaseProofIsRejected(){assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM bitcoin_payout_input_reservations WHERE candidate_id=?",id()));assertThrows(DataAccessException.class,()->jdbc.update("DELETE FROM bitcoin_payout_active_candidates WHERE candidate_id=?",id()));noPayment();}
    @Test void unknownLockCanBeReobservedUsingTheSameCandidateAfterThePersistentDelay()throws Exception{doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(lockNode).ensure(anyString(),anyString(),anyList());assertEquals("UNKNOWN",wallet.lock(id()).status());waitRetry();doReturn(observation(true)).when(lockNode).ensure(anyString(),anyString(),anyList());assertEquals("OBSERVED",wallet.lock(id()).status());assertEquals(2,count("bitcoin_payout_wallet_attempts"));assertEquals(2,count("bitcoin_payout_wallet_results"));assertEquals(1,count("bitcoin_payout_candidates"));noPayment();}
    @Test void observedUnlockAfterCancellationClosesTheAllocationAndKeepsLockHistory()throws Exception{wallet.lock(id());cancel();waitRetry();assertTrue(wallet.release(id()).allocationReleased());assertEquals(2,count("bitcoin_payout_wallet_attempts"));assertEquals(2,count("bitcoin_payout_wallet_results"));assertEquals(1,count("bitcoin_payout_candidate_releases"));assertEquals(1,count("bitcoin_payout_candidate_inputs"));assertEquals(0,count("bitcoin_payout_input_reservations"));assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));}
    @Test void unknownUnlockCannotReleaseTheAllocationOrRestoreLockPermission()throws Exception{wallet.lock(id());cancel();waitRetry();doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(lockNode).release(anyString(),anyString(),anyList());var r=wallet.release(id());assertEquals("UNKNOWN",r.status());assertFalse(r.allocationReleased());assertThrows(ResponseStatusException.class,()->wallet.lock(id()));noPayment();}
    @Test void releaseAuditFailureAfterNodeUnlockKeepsTheAllocationAndUnresolvedAttempt()throws Exception {
        wallet.lock(id());cancel();waitRetry();doNothing().when(audit).record(isNull(),eq("BTC_PAYOUT_CANDIDATE_RELEASED"),anyString(),eq("SUCCESS"),anyString());
        try{assertThrows(DataAccessException.class,()->wallet.release(id()));}finally{reset(audit);}
        assertEquals(2,count("bitcoin_payout_wallet_attempts"));assertEquals(1,count("bitcoin_payout_wallet_results"));assertEquals(0,count("bitcoin_payout_candidate_releases"));assertEquals(1,count("bitcoin_payout_input_reservations"));
        org.mockito.Mockito.verify(lockNode,times(1)).release(anyString(),anyString(),anyList());assertThrows(ResponseStatusException.class,()->wallet.lock(id()));
    }
    @Test void committedPayoutCannotReleaseInputsEvenAfterCancellationIsAttempted(){payouts.commit(approved.payload().id());assertThrows(ResponseStatusException.class,this::cancel);assertThrows(ResponseStatusException.class,()->wallet.release(id()));verifyNoInteractions(lockNode);assertEquals(1,count("bitcoin_payout_input_reservations"));assertEquals(0,count("bitcoin_payout_candidate_releases"));}
    @Test void aNewCurrentReviewRetiresOnlyTheOldCandidateWithoutNodeContact()throws Exception {
        pending.recognize(nativeInput());assertEquals("NO_SIGNAL",monitoring.processNext().orElseThrow().result().outcome());
        var next=proposal();assertNotEquals(approved.payload().basis().scopeHash(),next.payload().basis().scopeHash());
        assertTrue(wallet.release(id()).allocationReleased());assertEquals("SUPERSEDED",jdbc.queryForObject("SELECT reason FROM bitcoin_payout_candidate_releases WHERE candidate_id=?",String.class,id()));
        assertEquals(0,count("bitcoin_payout_input_reservations"));assertEquals(2,count("bitcoin_withdrawal_payout_reviews"));verifyNoInteractions(lockNode,node);
        assertThrows(ResponseStatusException.class,()->wallet.lock(id()));
    }
    @Test void actualReviewExpiryRetiresTheUnsignedCandidateWithoutChangingFinancialHistory()throws Exception {
        // 불변 검토의 만료 시각이나 DB 시계를 조작하지 않는다. 실제 DB 시각이 원래 승인 기한을 지날 때까지 기다린다.
        // 시험 실행기는 다른 격리 DB를 건드리지 않으며, 대기 중에도 노드 잠금/지급은 시작하지 않는다.
        while(true){var now=jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant();
            long remaining=java.time.Duration.between(now,approved.payload().expiresAt()).toMillis();if(remaining<0)break;Thread.sleep(Math.min(45000,remaining+5));}
        assertTrue(wallet.release(id()).allocationReleased());assertEquals("EXPIRED",jdbc.queryForObject("SELECT reason FROM bitcoin_payout_candidate_releases WHERE candidate_id=?",String.class,id()));
        assertEquals(0,count("bitcoin_payout_input_reservations"));assertEquals(1,count("bitcoin_payout_candidate_inputs"));assertEquals(0,count("bitcoin_withdrawal_cancellations"));assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));verifyNoInteractions(lockNode,node);
        assertThrows(ResponseStatusException.class,()->wallet.lock(id()));
    }
    @Test void outerDatabaseTransactionCannotStartWalletIo(){assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->wallet.lock(id())));assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(t->wallet.release(id())));verifyNoInteractions(lockNode);noPayment();}
}
