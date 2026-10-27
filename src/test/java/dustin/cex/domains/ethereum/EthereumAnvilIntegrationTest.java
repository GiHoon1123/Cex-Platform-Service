package dustin.cex.domains.ethereum;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 실행기가 소유한 실제 Anvil 노드에서 송신·영수증·정식 블록 연결을 검증한다. */
@EnabledIfEnvironmentVariable(named="CEX_ETHEREUM_RPC", matches="http://127\\.0\\.0\\.1:[0-9]+")
class EthereumAnvilIntegrationTest {
    @Test
    void sendsNativeEtherAndReadsCanonicalReceiptWithoutFloatingPoint() throws Exception {
        var settings = new EthereumSettings(System.getenv("CEX_ETHEREUM_RPC"), "31337", "0", 1, "");
        try (var rpc = new EthereumJsonRpcClient(settings)) {
            var accounts = rpc.call("eth_accounts", List.of());
            assertTrue(accounts.isArray());
            String from = accounts.get(0).asString(), to = accounts.get(1).asString();
            String before = rpc.call("eth_getBalance", List.of(to, "latest")).asString();
            String hash = rpc.call("eth_sendTransaction", List.of(Map.of("from", from, "to", to, "value", "0xde0b6b3a7640000"))).asString();
            assertTrue(hash.matches("0x[0-9a-f]{64}"));
            tools.jackson.databind.JsonNode receipt = null;
            for (int attempt = 0; attempt < 100; attempt++) {
                receipt = rpc.call("eth_getTransactionReceipt", List.of(hash));
                if (!receipt.isNull()) break;
                Thread.sleep(20);
            }
            assertNotNull(receipt);
            assertEquals("0x1", receipt.path("status").asString());
            var block = rpc.call("eth_getBlockByNumber", List.of(receipt.path("blockNumber").asString(), false));
            assertEquals(receipt.path("blockHash").asString(), block.path("hash").asString());
            BigInteger delta = EthereumSchemaInitializer.quantity(rpc.call("eth_getBalance", List.of(to, "latest")).asString())
                    .subtract(EthereumSchemaInitializer.quantity(before));
            assertEquals(new BigInteger("1000000000000000000"), delta);
        }
    }
}
