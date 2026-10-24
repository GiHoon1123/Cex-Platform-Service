package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** 소유 로컬 노드의 실제 블록에서 블록 수신 출력을 발견한다. */
@EnabledIfEnvironmentVariable(named="CEX_BITCOIN_TEST_URL",matches="http://127\\.0\\.0\\.1:[0-9]+")
class BitcoinBlockDiscoveryIntegrationTest {
    BitcoinSettings settings(){return new BitcoinSettings(System.getenv("CEX_BITCOIN_TEST_URL"),System.getenv("CEX_BITCOIN_TEST_USER"),System.getenv("CEX_BITCOIN_TEST_PASSWORD"),"cex_watch","REGTEST");}
    @Test void realBlockActivityFindsKnownReceive(){
        var settings=settings();try(var rpc=new BitcoinRpcClient(settings,JsonMapper.builder().build())){
            var found=new BitcoinBlockDiscovery(rpc,settings).scan(Long.parseLong(System.getenv("CEX_BITCOIN_TEST_CONFIRMED_HEIGHT")),List.of(System.getenv("CEX_BITCOIN_TEST_SCRIPT")));
            var output=found.received().stream().filter(o->o.txid().equals(System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX"))).findFirst().orElseThrow();
            assertEquals("bitcoin-regtest",found.network());assertTrue(found.previousHash().matches("[0-9a-f]{64}"));assertEquals(new BigDecimal("0.12345678"),output.amount());assertEquals(System.getenv("CEX_BITCOIN_TEST_SCRIPT"),output.scriptPubKey());
        }
    }
    @Test void unusedRegisteredStyleScriptDoesNotInventDeposit(){
        var settings=settings();try(var rpc=new BitcoinRpcClient(settings,JsonMapper.builder().build())){
            var found=new BitcoinBlockDiscovery(rpc,settings).scan(Long.parseLong(System.getenv("CEX_BITCOIN_TEST_CONFIRMED_HEIGHT")),List.of(System.getenv("CEX_BITCOIN_TEST_EMPTY_SCRIPT")));
            assertTrue(found.received().isEmpty());
        }
    }
    @Test void discoveredOutpointAgreesWithIndependentWalletTransactionRead(){
        var settings=settings();try(var rpc=new BitcoinRpcClient(settings,JsonMapper.builder().build())){
            var found=new BitcoinBlockDiscovery(rpc,settings).scan(Long.parseLong(System.getenv("CEX_BITCOIN_TEST_CONFIRMED_HEIGHT")),List.of(System.getenv("CEX_BITCOIN_TEST_SCRIPT")));
            var item=found.received().stream().filter(o->o.txid().equals(System.getenv("CEX_BITCOIN_TEST_CONFIRMED_TX"))).findFirst().orElseThrow();
            var observation=new BitcoinDepositReader(rpc,settings).inspect(item.txid());
            assertTrue(observation.outputs().stream().anyMatch(o->o.vout()==item.vout()&&o.scriptPubKey().equals(item.scriptPubKey())&&o.amount().compareTo(item.amount())==0));
        }
    }
}
