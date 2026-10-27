package dustin.ethsigner;

import dustin.cex.domains.ethereum.EthereumJsonRpcClient;
import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 승인된 ETH 출금 하나만 고정 송신 주소의 서명 RPC로 보내고 응답 불명은 자동 재전송하지 않는다. */
public class EthereumPayoutService {
    private record Claim(UUID attempt, UUID withdrawal, String to, BigDecimal amount) {}
    private final JdbcTemplate jdbc;
    private final EthereumSignerSettings settings;
    private final EthereumJsonRpcClient rpc;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public EthereumPayoutService(JdbcTemplate jdbc, EthereumSignerSettings settings, EthereumJsonRpcClient rpc,
            AuditService audit, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.rpc = rpc;
        this.audit = audit;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    public boolean processNext() {
        Claim claim = tx.execute(status -> claim());
        if (claim == null) return false;
        String outcome = "UNKNOWN", hash = null;
        try {
            BigInteger actualChain = quantity(rpc.call("eth_chainId", List.of()).asString());
            if (!actualChain.equals(settings.node().chainId())) throw new IllegalStateException("이더리움 서명 노드의 체인이 다릅니다");
            BigInteger wei = claim.amount().movePointRight(18).toBigIntegerExact();
            var result = rpc.call("eth_sendTransaction", List.of(Map.of(
                    "from", settings.from(), "to", claim.to(), "value", "0x" + wei.toString(16))));
            String value = result.asString();
            if (value.matches("0x[0-9a-f]{64}")) { outcome = "SUBMITTED"; hash = value; }
            else outcome = "REJECTED";
        } catch (IllegalArgumentException rejected) {
            outcome = "REJECTED";
        } catch (RuntimeException uncertain) {
            // 송신 노드가 요청을 받았는지 증명할 수 없으므로 새 nonce로 자동 재전송하지 않고 운영 확인 대상으로 남긴다.
        }
        String savedOutcome = outcome, savedHash = hash;
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO ethereum_payout_results(attempt_id,outcome,tx_hash) VALUES(?,?,?)", claim.attempt(), savedOutcome, savedHash);
            audit.record(null, "ETH_PAYOUT_FINISHED", "ethereum-payout-attempt:" + claim.attempt(), "SUCCESS", "OUTCOME:" + savedOutcome);
        });
        return true;
    }

    private Claim claim() {
        var rows = jdbc.queryForList("""
            SELECT w.id,w.address,w.amount,r.id review_id FROM ethereum_withdrawals w
            JOIN ethereum_payout_reviews r ON r.withdrawal_id=w.id
            JOIN ethereum_payout_decisions d ON d.review_id=r.id AND d.approved
            JOIN users maker ON maker.id=r.requester_id AND maker.enabled AND maker.role='OPERATOR'
              AND maker.operator_person_id=r.requester_person AND maker.version=r.requester_version
            JOIN users checker ON checker.id=r.checker_id AND checker.enabled AND checker.role='APPROVER'
              AND checker.operator_person_id=r.checker_person AND checker.version=r.checker_version
            WHERE r.expires_at>clock_timestamp()
              AND NOT EXISTS(SELECT 1 FROM ethereum_payout_attempts a WHERE a.withdrawal_id=w.id)
            ORDER BY r.position LIMIT 1 FOR UPDATE OF w SKIP LOCKED
            """);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        UUID attempt = UUID.randomUUID(), withdrawal = (UUID) row.get("id");
        jdbc.update("INSERT INTO ethereum_payout_attempts(id,withdrawal_id,review_id) VALUES(?,?,?)", attempt, withdrawal, row.get("review_id"));
        audit.record(null, "ETH_PAYOUT_STARTED", "ethereum-payout-attempt:" + attempt, "SUCCESS", "WITHDRAWAL:" + withdrawal);
        return new Claim(attempt, withdrawal, (String) row.get("address"), (BigDecimal) row.get("amount"));
    }

    private static BigInteger quantity(String value) {
        if (value == null || !value.matches("0x(?:0|[1-9a-f][0-9a-f]*)")) throw new IllegalStateException("이더리움 체인 ID 형식이 다릅니다");
        return new BigInteger(value.substring(2), 16);
    }
}
