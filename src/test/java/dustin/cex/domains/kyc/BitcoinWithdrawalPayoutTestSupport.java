package dustin.cex.domains.kyc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.security.OperatorMfaService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

/** 지급 승인 시험의 실제 고객·검토자 서명과 원본 준비를 공유한다. 체인 지급은 하지 않는다. */
abstract class BitcoinWithdrawalPayoutTestSupport extends BitcoinWithdrawalTransferTestSupport {
    @Autowired BitcoinWithdrawalPayoutReviews payouts;
    @Autowired BitcoinPendingDepositService pending;
    @Autowired BitcoinDepositQualificationService qualification;
    @Autowired dustin.cex.domains.monitoring.DepositMonitoringService monitoring;
    UUID transfer;
    long approvalCounter=2;
    static final String NOTE="고정 지급 원본과 현재 고객 및 위험 근거를 검토했습니다";
    @BeforeEach void verifiedTransfer() {
        transfer=prepare().intentId();assertEquals("VERIFIED",execute(transfer).status());clearInvocations(addressClient,transferClient);
    }
    @AfterEach void noPrivateOrFinancialEffects(){verifyNoInteractions(addressClient,transferClient);for(var row:jdbc.queryForList("SELECT detail FROM security_audit WHERE action LIKE 'BTC_PAYOUT_%'"))assertFalse(row.get("detail").toString().contains(NOTE));}
    BitcoinWithdrawalPayoutReviews.Request proposal(){return payouts.propose(maker.user().getId(),maker.sid(),transfer,checker.user().getId(),UUID.randomUUID(),NOTE);}
    OperatorMfaService.AuthorizationProof signature(BitcoinWithdrawalPayoutReviews.Request r,boolean approve)throws Exception {
        var options=payouts.options(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve).authentication();
        return new OperatorMfaService.AuthorizationProof(options.challengeId(),checker.key().assertion(options,"http://localhost:3003",true,approvalCounter++,null));
    }
    BitcoinWithdrawalPayoutReviews.Request decide(BitcoinWithdrawalPayoutReviews.Request r,boolean approve)throws Exception {
        payouts.inspect(checker.user().getId(),checker.sid(),r.payload().id());return payouts.decide(checker.user().getId(),checker.sid(),r.payload().id(),r.payloadHash(),approve,signature(r,approve));
    }
    void cancel(){cancellations.cancel(customer.getId(),sid,withdrawal);clearInvocations(engineWithdrawal);}
}
