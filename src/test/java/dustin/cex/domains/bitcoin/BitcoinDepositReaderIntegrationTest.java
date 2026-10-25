package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** 소유 시험 체인의 실제 거래를 읽는다. 관리자 쿠키/개인키는 Java에 제공하지 않는다. */
@EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class BitcoinDepositReaderIntegrationTest {
    BitcoinSettings settings(){return new BitcoinSettings(System.getenv("CEX_BITCOIN_TEST_URL"),System.getenv("CEX_BITCOIN_TEST_USER"),System.getenv("CEX_BITCOIN_TEST_PASSWORD"),"cex_watch","REGTEST");}
    BitcoinDepositReader.Observation inspect(String variable){
        var settings=settings();try(var rpc=new BitcoinRpcClient(settings,JsonMapper.builder().build())){
            return new BitcoinDepositReader(rpc,settings).inspect(System.getenv(variable));
        }
    }
    @Test void realConfirmedReceiveMatchesDecodedOutputAndCanonicalBlock(){
        var result=inspect("CEX_BITCOIN_TEST_CONFIRMED_TX");assertEquals(BitcoinDepositReader.Status.CONFIRMED,result.status());
        assertTrue(result.confirmations()>0);assertNotNull(result.blockHash());assertNotNull(result.blockHeight());assertFalse(result.coinbase());assertFalse(result.walletDebit());
        assertEquals(result.tipHeight()-result.blockHeight()+1,result.confirmations());
        var output=result.outputs().stream().filter(o->o.address().equals(System.getenv("CEX_BITCOIN_TEST_ADDRESS"))).findFirst().orElseThrow();
        assertEquals(new BigDecimal("0.12345678"),output.amount());assertTrue(output.scriptPubKey().matches("0014[0-9a-f]{40}"));
    }
    @Test void actualUnconfirmedReceiveHasNoCanonicalBlock(){
        var result=inspect("CEX_BITCOIN_TEST_UNCONFIRMED_TX");assertEquals(BitcoinDepositReader.Status.UNCONFIRMED,result.status());
        assertEquals(0,result.confirmations());assertNull(result.blockHash());assertNull(result.blockHeight());assertFalse(result.outputs().isEmpty());
    }
    @Test void realFeeReplacementIsConflictedAndNeverPresentedAsConfirmed(){
        var result=inspect("CEX_BITCOIN_TEST_CONFLICTED_TX");assertEquals(BitcoinDepositReader.Status.CONFLICTED,result.status());
        assertTrue(result.confirmations()<0);assertNull(result.blockHash());assertNull(result.blockHeight());
    }
    @Test void invalidatedBlockReceiveIsReadAgainAsUnconfirmed(){
        var result=inspect("CEX_BITCOIN_TEST_REORG_TX");assertEquals(BitcoinDepositReader.Status.UNCONFIRMED,result.status());
        assertEquals(0,result.confirmations());assertNull(result.blockHash());assertFalse(result.outputs().isEmpty());
    }
    @Test void realCoinbaseIsMarkedSeparatelyFromOrdinaryDeposit(){
        var result=inspect("CEX_BITCOIN_TEST_COINBASE_TX");assertTrue(result.coinbase());assertTrue(result.confirmations()>0);assertFalse(result.outputs().isEmpty());
    }
    @Test void actualCustodyWalletSpendRetainsDebitEvidenceAlongsideReceive(){
        var result=inspect("CEX_BITCOIN_TEST_INTERNAL_TX");assertTrue(result.walletDebit());assertFalse(result.coinbase());assertTrue(result.confirmations()>0);
        assertTrue(result.outputs().stream().anyMatch(o->o.address().equals(System.getenv("CEX_BITCOIN_TEST_ADDRESS"))&&o.amount().compareTo(new BigDecimal("0.01"))==0));
    }

}
