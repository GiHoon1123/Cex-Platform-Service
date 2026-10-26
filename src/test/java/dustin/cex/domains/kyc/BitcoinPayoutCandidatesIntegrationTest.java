package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 실제 금융 원본과 내용 결합 서명을 사용하고 노드 입출력만 대역으로 바꾼다. */
class BitcoinPayoutCandidatesIntegrationTest extends BitcoinPayoutCandidatesTestSupport {
    @Test void savedCandidateBindsOriginalApprovalPsbtAndAllInputsWithoutPaying(){var r=prepareCandidate();assertEquals("SAVED",r.status());var p=r.candidate().payload();assertEquals(approved.payload().id(),p.reviewId());assertEquals(approved.payloadHash(),p.reviewHash());assertEquals(approved.payload().basis().scopeHash(),p.scopeHash());assertEquals(ADDRESS,p.funds().address());assertEquals("0.01000000",p.prepared().amount());assertEquals(1,count("bitcoin_payout_input_reservations"));assertEquals(1,count("bitcoin_payout_candidate_inputs"));assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));assertEquals(0,count("bitcoin_withdrawal_cancellations"));}
    @Test void repeatedPreparationReturnsTheImmutableCandidateWithoutAnotherNodeCall(){var first=prepareCandidate();clearInvocations(node);var second=prepareCandidate();assertEquals(first.candidate(),second.candidate());assertNull(second.attemptId());verifyNoInteractions(node);assertEquals(1,count("bitcoin_payout_preparations"));}
    @Test void nodeFailurePreservesAttemptAndFailureAuditWithoutReservingInputs(){doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(node).quote();assertEquals("NODE_UNAVAILABLE",prepareCandidate().status());assertEquals(1,count("bitcoin_payout_preparation_results"));assertEquals(0,count("bitcoin_payout_candidates"));assertEquals(0,count("bitcoin_payout_input_reservations"));assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE action='BTC_PAYOUT_PREPARATION_FINISHED' AND detail='NODE_UNAVAILABLE'",Long.class));}
    @Test void immediateRetryCannotCreateFreshCoinSelectionAttempts(){doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(node).quote();prepareCandidate();clearInvocations(node);assertThrows(ResponseStatusException.class,this::prepareCandidate);verifyNoInteractions(node);assertEquals(1,count("bitcoin_payout_preparations"));}
    @Test void cancellationBeforePreparationCannotReachTheNode(){cancel();assertThrows(ResponseStatusException.class,this::prepareCandidate);verifyNoInteractions(node);assertEquals(0,count("bitcoin_payout_preparations"));}
    @Test void cancellationDuringNodeIoPreservesTheObservationWithoutSavingACandidate(){doAnswer(call->{cancel();return prepared(call.getArgument(0));}).when(node).prepare(any(),any());assertEquals("BASIS_CHANGED",prepareCandidate().status());assertEquals(0,count("bitcoin_payout_candidates"));assertEquals(1,count("bitcoin_payout_preparation_results"));}
    @Test void changedKycDuringNodeIoCannotCreateAnInputReservation(){doAnswer(call->{jdbc.update("UPDATE kyc_profiles SET valid_until=clock_timestamp()-interval '1 second' WHERE user_id=?",customer.getId());return prepared(call.getArgument(0));}).when(node).prepare(any(),any());assertEquals("BASIS_CHANGED",prepareCandidate().status());assertEquals(0,count("bitcoin_payout_input_reservations"));}
    @Test void missingStartAuditPreventsNodeIo(){doNothing().when(audit).record(isNull(),eq("BTC_PAYOUT_PREPARATION_STARTED"),anyString(),eq("SUCCESS"),anyString());try{assertThrows(DataAccessException.class,this::prepareCandidate);}finally{reset(audit);}verifyNoInteractions(node);assertEquals(0,count("bitcoin_payout_preparations"));}
    @Test void omittedCandidateAuditRollsBackAllInputsButPreservesTheStartedAttempt(){doNothing().when(audit).record(isNull(),eq("BTC_PAYOUT_CANDIDATE_SAVED"),anyString(),eq("SUCCESS"),anyString());try{assertThrows(DataAccessException.class,this::prepareCandidate);}finally{reset(audit);}assertEquals(1,count("bitcoin_payout_preparations"));assertEquals(0,count("bitcoin_payout_preparation_results"));assertEquals(0,count("bitcoin_payout_candidates"));assertEquals(0,count("bitcoin_payout_candidate_inputs"));assertEquals(0,count("bitcoin_payout_input_reservations"));}
    @Test void omittedResultAuditCannotPretendToHaveFinishedTheAttempt(){doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).when(node).quote();doNothing().when(audit).record(isNull(),eq("BTC_PAYOUT_PREPARATION_FINISHED"),anyString(),eq("SUCCESS"),anyString());try{assertThrows(DataAccessException.class,this::prepareCandidate);}finally{reset(audit);}assertEquals(1,count("bitcoin_payout_preparations"));assertEquals(0,count("bitcoin_payout_preparation_results"));}
    @Test void storedPayloadIsEncryptedAuthenticatedAndHistoryCannotBeDeleted(){var r=prepareCandidate();var id=r.candidate().payload().id();var row=jdbc.queryForMap("SELECT * FROM bitcoin_payout_candidates WHERE id=?",id);var raw=new String(crypto.decrypt(customer.getId(),id,(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);assertTrue(raw.contains(ADDRESS));assertEquals(row.get("payload_hash"),crypto.fingerprint("btc-payout-candidate-v1",raw));assertFalse(new String((byte[])row.get("ciphertext"),StandardCharsets.UTF_8).contains(ADDRESS));assertFalse(r.toString().contains(PSBT));for(String table:List.of("bitcoin_payout_preparations","bitcoin_payout_preparation_results","bitcoin_payout_candidates","bitcoin_payout_candidate_inputs","bitcoin_payout_active_candidates","bitcoin_payout_input_reservations"))assertThrows(DataAccessException.class,()->jdbc.execute("DELETE FROM "+table));}
    @Test void concurrentPreparationCreatesOneAttemptAndOneCandidate()throws Exception{var start=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){Callable<Boolean> call=()->{assertTrue(start.await(5,TimeUnit.SECONDS));try{return "SAVED".equals(prepareCandidate().status());}catch(ResponseStatusException held){return false;}};var a=pool.submit(call);var b=pool.submit(call);start.countDown();boolean x=a.get(20,TimeUnit.SECONDS),y=b.get(20,TimeUnit.SECONDS);assertTrue(x||y);}assertEquals(1,count("bitcoin_payout_preparations"));assertEquals(1,count("bitcoin_payout_candidates"));org.mockito.Mockito.verify(node,times(1)).prepare(any(),any());}
    @Test void twoApprovedWithdrawalsCannotReserveTheSameNativeInput()throws Exception {secondWithdrawalCollision(false);}
    @Test void laterInputCollisionRollsBackEarlierInputAssignments()throws Exception {secondWithdrawalCollision(true);}
    void secondWithdrawalCollision(boolean partial)throws Exception {
        var first=prepareCandidate();assertEquals("SAVED",first.status());
        // 같은 고객이 다른 금융 원본을 별도로 서명한다. 새 평가액만 높여 동일한 자동 검토 범위로 준비한다.
        when(priceClient.read()).thenAnswer(call->referenceQuote(new BigDecimal("1000000000")));referencePrices.refresh();
        withdrawal=quotes.prepare(customer.getId(),sid,UUID.randomUUID(),ADDRESS,"0.001").payload().id();submit(withdrawal);
        expectedHold=expectedHold.add(new BigDecimal("0.00100010"));events.consumeOrderEvent(event(withdrawal,reserveEngine,"withdrawal_reserved",2));
        transfer=prepare().intentId();doReturn(new dustin.cex.domains.travelrule.CodeVaspWithdrawalAddressClient.Result("VALID",null,PEER)).when(addressClient).verify(any());assertEquals("VERIFIED",execute(transfer).status());clearInvocations(addressClient,transferClient,engineWithdrawal);
        approved=decide(proposal(),true);doReturn(fee()).when(node).quote();
        if(partial)doAnswer(call->{var p=prepared(call.getArgument(0));var extra=new BitcoinPayoutClient.Input("0".repeat(64),1,OWN,"0014"+"cd".repeat(20),"0.05000000");
            return new BitcoinPayoutClient.Prepared(p.withdrawalId(),p.policy(),p.network(),p.wallet(),p.psbt(),p.unsignedTxid(),p.amount(),p.nativeFee(),p.changeAddress(),p.changeScript(),new BigDecimal(p.changeAmount()).add(new BigDecimal("0.05")).toPlainString(),List.of(p.inputs().getFirst(),extra),p.quote(),p.finishedAt());}).when(node).prepare(any(),any());
        var second=prepareCandidate();assertEquals("INPUT_RESERVED",second.status());assertNull(second.candidate());assertEquals(2,count("bitcoin_payout_preparations"));assertEquals(2,count("bitcoin_payout_preparation_results"));assertEquals(1,count("bitcoin_payout_candidates"));assertEquals(first.candidate().payload().id(),jdbc.queryForObject("SELECT candidate_id FROM bitcoin_payout_input_reservations",UUID.class));
    }
    @Test void currentApprovalCannotBeUsedFromAnOpenOuterTransaction(){var tx=new TransactionTemplate(manager);assertThrows(IllegalStateException.class,()->tx.execute(t->prepareCandidate()));verifyNoInteractions(node);assertEquals(0,count("bitcoin_payout_preparations"));}
}
