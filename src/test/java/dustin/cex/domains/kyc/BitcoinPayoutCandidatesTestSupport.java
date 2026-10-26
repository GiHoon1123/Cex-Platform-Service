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
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean BitcoinPayoutInputSources inputSources;
    BitcoinWithdrawalPayoutReviews.Request approved;
    static final String PSBT="cHNidP8BAAA=",INPUT="a".repeat(64);
    @BeforeEach void nodeFixture()throws Exception {
        approved=decide(proposal(),true);
        // 출처 자격 판정만 대역으로 두고 암호화 저장·원본 검증·필수 감사는 실제 서비스를 사용한다.
        inputSources=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(inputSources);
        doNothing().when(inputSources).lockForReview(any(),anyString(),anyString(),anyList());
        doAnswer(call->{var p=call.getArgument(0,BitcoinPayoutClient.Prepared.class);
            return p.inputs().stream().map(i->{var point=new BitcoinPayoutInputSources.Point(i.txid(),i.vout());UUID root=UUID.nameUUIDFromBytes((i.txid()+":"+i.vout()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String event="synthetic-credit:"+i.vout(),authorization="d".repeat(64),credit="e".repeat(64);
                String mac=crypto.fingerprint("btc-native-input-origin-v1",json.writeValueAsString(List.of(BitcoinPayoutInputSources.POLICY,p.network(),p.wallet(),point,i.amount(),i.scriptPubKey(),root,customer.getId(),authorization,event,credit)));
                return new BitcoinPayoutInputSources.Certificate(point,i.amount(),i.scriptPubKey(),new BitcoinPayoutInputSources.Origin(root,customer.getId(),authorization,event,credit,mac),
                    new BitcoinPayoutInputSources.Chain(1,1,"b".repeat(64),0),new BitcoinPayoutInputSources.Risk(UUID.fromString("11111111-1111-1111-1111-111111111111"),"a".repeat(64),"a".repeat(64)));}).toList();
        }).when(inputSources).require(any());
        when(node.quote()).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(1,count("bitcoin_payout_preparations"));return fee();});
        when(node.prepare(any(),any())).thenAnswer(call->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return prepared(call.getArgument(0));});
    }
    BitcoinPayoutClient.FeeQuote fee(){return new BitcoinPayoutClient.FeeQuote("bitcoin-regtest","watch",new BigDecimal("2"),"b".repeat(64),200,Instant.now(),Instant.now());}
    BitcoinPayoutClient.Prepared prepared(BitcoinPayoutClient.Command c){return new BitcoinPayoutClient.Prepared(c.withdrawalId(),BitcoinPayoutClient.POLICY,c.network(),"watch",PSBT,c.withdrawalId().toString().replace("-", "").repeat(2),c.amount().toPlainString(),"0.00000282","bcrt1qsyntheticchange","0014"+"ef".repeat(20),new BigDecimal("0.05").subtract(c.amount()).subtract(new BigDecimal("0.00000282")).toPlainString(),List.of(new BitcoinPayoutClient.Input(INPUT,0,OWN,"0014"+"cd".repeat(20),"0.05000000")),fee(),Instant.now());}
    BitcoinPayoutCandidates.Result prepareCandidate(){return candidates.prepare(approved.payload().id());}
}
