package dustin.cex.domains.ethereum;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** 노드·체인·주소 설정이 잘못됐을 때 운영 중이 아니라 시작 단계에서 차단되는지 검증한다. */
class EthereumSettingsTest {
    @Test
    void acceptsLoopbackNodeAndCanonicalizesExternallyProvisionedAddresses() {
        var settings = new EthereumSettings(
                "http://127.0.0.1:8545",
                "31337",
                "7",
                3,
                "0xA000000000000000000000000000000000000001");

        assertTrue(settings.configured());
        assertEquals(new BigInteger("31337"), settings.chainId);
        assertEquals("0xa000000000000000000000000000000000000001", settings.addressPool.getFirst());
    }

    @Test
    void rejectsPlainHttpRemoteNodeAndDuplicateAddresses() {
        assertThrows(IllegalArgumentException.class, () -> new EthereumSettings(
                "http://example.com", "1", "0", 12, ""));
        assertThrows(IllegalArgumentException.class, () -> new EthereumSettings(
                "https://node.example.com", "1", "0", 12,
                "0xa000000000000000000000000000000000000001,0xA000000000000000000000000000000000000001"));
    }

    @Test
    void parsesEthereumQuantitiesWithoutFloatingPointConversion() {
        assertEquals(new BigInteger("1000000000000000000"), EthereumSchemaInitializer.quantity("0xde0b6b3a7640000"));
        assertEquals("0xde0b6b3a7640000", EthereumSchemaInitializer.hex(new BigInteger("1000000000000000000")));
        assertThrows(IllegalArgumentException.class, () -> EthereumSchemaInitializer.quantity("0x01"));
        assertThrows(IllegalArgumentException.class, () -> EthereumSchemaInitializer.quantity("1"));
    }
}
