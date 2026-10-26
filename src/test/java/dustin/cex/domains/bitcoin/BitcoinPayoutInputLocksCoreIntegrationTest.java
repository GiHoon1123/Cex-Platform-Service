package dustin.cex.domains.bitcoin;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Java가 잠근 입력을 별도 시험 실행기가 실제 노드 재시작 후 확인한다. Java에는 관리자 쿠키나 서명 권한이 없다. */
@EnabledIfEnvironmentVariable(named="CEX_BITCOIN_LOCK_TEST_PHASE",matches="LOCK|RESTORED")
class BitcoinPayoutInputLocksCoreIntegrationTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    @Test void ownedInputLocksSurviveCoreRestartAndOnlyTheirReleaseRestoresCoinSelection() {
        var settings=new BitcoinSettings(System.getenv("CEX_BITCOIN_PAYOUT_TEST_URL"),System.getenv("CEX_BITCOIN_PAYOUT_TEST_USER"),System.getenv("CEX_BITCOIN_PAYOUT_TEST_PASSWORD"),"watch","REGTEST");
        try(var rpc=new BitcoinRpcClient(settings,JSON)) {
            var locks=new BitcoinPayoutInputLocks(settings,rpc);var client=new BitcoinPayoutClient(settings,rpc);
            var input=JSON.readTree(System.getenv("CEX_BITCOIN_LOCK_TEST_INPUT"));
            var fixed=new BitcoinPayoutClient.Input(input.path("txid").stringValue(),input.path("vout").intValue(),input.path("address").stringValue(),input.path("scriptPubKey").stringValue(),"0.05000000");
            var target=JSON.readTree(System.getenv("CEX_BITCOIN_PAYOUT_TEST_DESTINATIONS")).get(0);
            var command=new BitcoinPayoutClient.Command(UUID.randomUUID(),"bitcoin-regtest",target.path("address").stringValue(),target.path("scriptPubKey").stringValue(),new BigDecimal("0.01"));
            var chain=rpc.ready().chain();var fee=new BitcoinPayoutClient.FeeQuote("bitcoin-regtest","watch",new BigDecimal("2"),chain.path("bestblockhash").stringValue(),chain.path("blocks").longValue(),Instant.now(),Instant.now());
            boolean restored="RESTORED".equals(System.getenv("CEX_BITCOIN_LOCK_TEST_PHASE"));
            if(!restored){assertTrue(rpc.call("listlockunspent",List.of()).isEmpty());assertEquals(fixed.txid(),client.prepare(command,fee).inputs().getFirst().txid());}
            else {var found=rpc.call("listlockunspent",List.of());assertEquals(1,found.size());assertEquals(fixed.txid(),found.get(0).path("txid").stringValue());assertEquals(fixed.vout(),found.get(0).path("vout").intValue());}
            assertTrue(locks.ensure("bitcoin-regtest","watch",List.of(fixed)).locked());
            assertEquals(503,assertThrows(ResponseStatusException.class,()->client.prepare(command,fee)).getStatusCode().value());
            if(restored){assertFalse(locks.release("bitcoin-regtest","watch",List.of(fixed)).locked());assertFalse(locks.release("bitcoin-regtest","watch",List.of(fixed)).locked());assertTrue(rpc.call("listlockunspent",List.of()).isEmpty());assertEquals(fixed.txid(),client.prepare(command,fee).inputs().getFirst().txid());}
            assertFalse(rpc.ready().wallet().path("private_keys_enabled").booleanValue());
        }
    }
}
