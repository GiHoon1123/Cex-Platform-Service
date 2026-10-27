package dustin.ethconfirmer;

import dustin.cex.domains.ethereum.EthereumJsonRpcClient;
import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 제출된 ETH 거래가 현재 정식 체인에서 필요한 깊이에 도달한 경우에만 불변 확정 증거를 저장한다. */
public class EthereumConfirmationService {
    private final JdbcTemplate jdbc;
    private final EthereumConfirmationSettings settings;
    private final EthereumJsonRpcClient rpc;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public EthereumConfirmationService(JdbcTemplate jdbc, EthereumConfirmationSettings settings,
            EthereumJsonRpcClient rpc, AuditService audit, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.rpc = rpc;
        this.audit = audit;
        tx = new TransactionTemplate(manager);
    }

    public boolean processNext() {
        if (!quantity(rpc.call("eth_chainId", List.of()).asString()).equals(settings.node().chainId()))
            throw new IllegalStateException("이더리움 확인 노드의 체인이 다릅니다");
        var rows = jdbc.queryForList("SELECT a.id,a.withdrawal_id,r.tx_hash FROM ethereum_payout_attempts a JOIN ethereum_payout_results r ON r.attempt_id=a.id AND r.outcome='SUBMITTED' WHERE NOT EXISTS(SELECT 1 FROM ethereum_payout_confirmations c WHERE c.withdrawal_id=a.withdrawal_id) ORDER BY a.position LIMIT 1");
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        UUID attempt = (UUID) row.get("id"), withdrawal = (UUID) row.get("withdrawal_id");
        String hash = (String) row.get("tx_hash");
        var receipt = rpc.call("eth_getTransactionReceipt", List.of(hash));
        if (receipt.isNull() || !"0x1".equals(receipt.path("status").asString()) || !hash.equals(receipt.path("transactionHash").asString())) return false;
        BigInteger block = quantity(receipt.path("blockNumber").asString());
        String blockHash = data(receipt.path("blockHash").asString());
        BigInteger tip = quantity(rpc.call("eth_blockNumber", List.of()).asString());
        if (tip.subtract(block).add(BigInteger.ONE).compareTo(BigInteger.valueOf(settings.required())) < 0) return false;
        var canonical = rpc.call("eth_getBlockByNumber", List.of("0x" + block.toString(16), false));
        if (!blockHash.equals(canonical.path("hash").asString())) throw new IllegalStateException("이더리움 지급 블록이 현재 정식 체인과 다릅니다");
        BigInteger gas = quantity(receipt.path("gasUsed").asString()).multiply(quantity(receipt.path("effectiveGasPrice").asString()));
        BigDecimal fee = new BigDecimal(gas, 18);
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO ethereum_payout_confirmations(withdrawal_id,attempt_id,tx_hash,block_number,block_hash,gas_fee) VALUES(?,?,?,?,?,?)",
                    withdrawal, attempt, hash, block, blockHash, fee);
            audit.record(null, "ETH_PAYOUT_CONFIRMED", "ethereum-withdrawal:" + withdrawal, "SUCCESS", "TX:" + hash);
        });
        return true;
    }

    private static BigInteger quantity(String value) {
        if (value == null || !value.matches("0x(?:0|[1-9a-f][0-9a-f]*)")) throw new IllegalStateException("이더리움 수량 형식이 다릅니다");
        return new BigInteger(value.substring(2), 16);
    }
    private static String data(String value) {
        if (value == null || !value.matches("0x[0-9a-f]{64}")) throw new IllegalStateException("이더리움 블록 해시 형식이 다릅니다");
        return value;
    }
}
