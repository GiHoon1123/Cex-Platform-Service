package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** 같은 미서명 거래를 다시 준비할 수 있어도 현재 입력의 독점 배정과 과거 미실행 종료는 유지해야 한다. */
class BitcoinPayoutCandidateReapprovalIntegrationTest extends BitcoinPayoutCandidatesTestSupport {
    @Autowired BitcoinPayoutCandidateWallet wallet;
    static final String UNSIGNED="c".repeat(64);
    @BeforeEach void noChangeTransaction(){
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(count("bitcoin_payout_preparations")>0);return fee();}).when(node).quote();
        doAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());var p=prepared(call.getArgument(0));
            // Node의 실제 PSBT 유효성은 별도 Core 시험에서 확인한다. 여기서는 불변 후보/재승인·배정 계약에 집중한다.
            return new BitcoinPayoutClient.Prepared(p.withdrawalId(),p.policy(),p.network(),p.wallet(),p.psbt(),UNSIGNED,p.amount(),p.nativeFee(),null,null,null,
                List.of(new BitcoinPayoutClient.Input(INPUT,0,OWN,"0014"+"cd".repeat(20),new BigDecimal(p.amount()).add(new BigDecimal(p.nativeFee())).toPlainString())),p.quote(),p.finishedAt());
        }).when(node).prepare(any(),any());
    }
    @AfterEach void neverLockSignOrBroadcast(){verifyNoInteractions(lockNode);assertEquals(0,count("bitcoin_withdrawal_payout_commitments"));}
    BitcoinWithdrawalPayoutReviews.Request newerReview()throws Exception {
        pending.recognize(nativeInput());assertEquals("NO_SIGNAL",monitoring.processNext().orElseThrow().result().outcome());return decide(proposal(),true);
    }
    @Test void aNewApprovalReusesTheUnsignedIdentityOnlyAfterTheOldAllocationIsReleased()throws Exception {
        var first=prepareCandidate().candidate();approved=newerReview();assertTrue(wallet.release(first.payload().id()).allocationReleased());
        var next=prepareCandidate();assertEquals("SAVED",next.status());assertNotEquals(first.payload().id(),next.candidate().payload().id());
        assertEquals(first.payload().prepared().unsignedTxid(),next.candidate().payload().prepared().unsignedTxid());assertEquals(first.payload().prepared().psbt(),next.candidate().payload().prepared().psbt());
        assertNotEquals(first.payload().reviewHash(),next.candidate().payload().reviewHash());assertEquals(2,count("bitcoin_payout_candidates"));assertEquals(2,count("bitcoin_payout_candidate_inputs"));assertEquals(1,count("bitcoin_payout_candidate_releases"));
        assertEquals(next.candidate().payload().id(),jdbc.queryForObject("SELECT candidate_id FROM bitcoin_payout_input_reservations",UUID.class));
        assertThrows(ResponseStatusException.class,()->wallet.lock(first.payload().id()));
    }
    @Test void aNewReviewCannotReplaceAnUnreleasedActiveCandidate()throws Exception {
        var first=prepareCandidate().candidate();approved=newerReview();clearInvocations(node);
        assertEquals(409,assertThrows(ResponseStatusException.class,this::prepareCandidate).getStatusCode().value());verifyNoInteractions(node);
        assertEquals(1,count("bitcoin_payout_candidates"));assertEquals(first.payload().id(),jdbc.queryForObject("SELECT candidate_id FROM bitcoin_payout_input_reservations",UUID.class));assertEquals(0,count("bitcoin_payout_candidate_releases"));
    }
    @Test void anotherWithdrawalWithTheSameUnsignedIdentityStillWaitsForTheOldInputRelease()throws Exception {
        var first=prepareCandidate().candidate();cancel();events.consumeOrderEvent(event(withdrawal,reserveEngine,"withdrawal_released",2));amount(FUNDS,BigDecimal.ZERO);
        withdrawal=price().payload().id();submit(withdrawal);events.consumeOrderEvent(event(withdrawal,reserveEngine,"withdrawal_reserved",3));
        transfer=prepare().intentId();doReturn(new dustin.cex.domains.travelrule.CodeVaspWithdrawalAddressClient.Result("VALID",null,PEER)).when(addressClient).verify(any());
        assertEquals("VERIFIED",execute(transfer).status());clearInvocations(addressClient,transferClient,engineWithdrawal);approved=decide(proposal(),true);
        assertEquals("INPUT_RESERVED",prepareCandidate().status());assertEquals(first.payload().id(),jdbc.queryForObject("SELECT candidate_id FROM bitcoin_payout_input_reservations",UUID.class));
        assertTrue(wallet.release(first.payload().id()).allocationReleased());
        // 앞선 실패 기록의 재시도 대기는 그대로 지킨다. 원래 금융/감사 타임스탬프를 변경하지 않는다.
        Thread.sleep(31000);var next=prepareCandidate();assertEquals("SAVED",next.status());assertEquals(UNSIGNED,next.candidate().payload().prepared().unsignedTxid());
        assertNotEquals(first.payload().funds().withdrawalId(),next.candidate().payload().funds().withdrawalId());assertEquals(1,count("bitcoin_payout_input_reservations"));assertEquals(2,count("bitcoin_payout_candidates"));
    }
}
