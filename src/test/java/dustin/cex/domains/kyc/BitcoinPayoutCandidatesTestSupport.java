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

/** 후보와 잠금 검증이 같은 실제 승인/금융 원본 및 노드 대역을 사용하도록 공유한다. */
abstract class BitcoinPayoutCandidatesTestSupport extends BitcoinWithdrawalPayoutTestSupport {
    @Autowired BitcoinPayoutCandidates candidates;
    @MockitoBean BitcoinPayoutClient node;
    BitcoinWithdrawalPayoutReviews.Request approved;
    static final String PSBT="cHNidP8BAAA=",INPUT="a".repeat(64);
    @BeforeEach void nodeFixture()throws Exception {
        approved=decide(proposal(),true);
        when(node.quote()).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(1,count("bitcoin_payout_preparations"));return fee();});
        when(node.prepare(any(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return prepared(call.getArgument(0));});
    }
    BitcoinPayoutClient.FeeQuote fee(){return new BitcoinPayoutClient.FeeQuote("bitcoin-regtest","watch",new BigDecimal("2"),"b".repeat(64),200,Instant.now(),Instant.now());}
    BitcoinPayoutClient.Prepared prepared(BitcoinPayoutClient.Command c){return new BitcoinPayoutClient.Prepared(c.withdrawalId(),BitcoinPayoutClient.POLICY,c.network(),"watch",PSBT,c.withdrawalId().toString().replace("-", "").repeat(2),c.amount().toPlainString(),"0.00000282","bcrt1qsyntheticchange","0014"+"ef".repeat(20),new BigDecimal("0.05").subtract(c.amount()).subtract(new BigDecimal("0.00000282")).toPlainString(),List.of(new BitcoinPayoutClient.Input(INPUT,0,OWN,"0014"+"cd".repeat(20),"0.05000000")),fee(),Instant.now());}
    BitcoinPayoutCandidates.Result prepareCandidate(){return candidates.prepare(approved.payload().id());}
}
