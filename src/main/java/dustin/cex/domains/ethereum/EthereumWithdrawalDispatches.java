package dustin.cex.domains.ethereum;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.AuditService;
import dustin.cex.shared.http.EngineWithdrawalClient;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 커밋된 ETH 출금 원본만 DB 잠금 밖에서 같은 ID와 총액으로 엔진에 예약한다. */
@Service
public class EthereumWithdrawalDispatches {
    public record Execution(UUID withdrawalId, UUID attemptId, EngineWithdrawalClient.Outcome outcome) {}
    private record Claim(UUID id, UUID attempt, long owner, BigDecimal total) {}

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuditService audit;
    private final EngineWithdrawalClient engine;
    private final TransactionTemplate tx;

    public EthereumWithdrawalDispatches(JdbcTemplate jdbc, UserRepository users, AuditService audit,
            EngineWithdrawalClient engine, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.engine = engine;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    public Optional<Execution> execute(UUID id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("ETH 출금 엔진 호출은 DB 잠금 밖에서 실행하세요");
        Claim claim = tx.execute(status -> claim(id));
        if (claim == null) return Optional.empty();
        EngineWithdrawalClient.Outcome outcome;
        try {
            outcome = engine.execute(new EngineWithdrawalClient.Command(id, claim.owner(), "ETH", claim.total(), EngineWithdrawalClient.Action.RESERVE));
            if (outcome == null) outcome = EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;
        } catch (RuntimeException uncertain) {
            outcome = EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;
        }
        var saved = outcome;
        tx.executeWithoutResult(status -> {
            users.lockById(claim.owner()).orElseThrow();
            jdbc.update("INSERT INTO ethereum_withdrawal_dispatch_results(attempt_id,outcome) VALUES(?,?)", claim.attempt(), saved.name());
            audit.record(null, "ETH_WITHDRAWAL_DISPATCH_FINISHED", "ethereum-withdrawal-attempt:" + claim.attempt(), "SUCCESS", "OUTCOME:" + saved.name());
        });
        return Optional.of(new Execution(id, claim.attempt(), saved));
    }

    private Claim claim(UUID id) {
        var rows = jdbc.queryForList("SELECT user_id,total FROM ethereum_withdrawals WHERE id=?", id);
        if (rows.size() != 1) return null;
        var row = rows.getFirst();
        long owner = ((Number) row.get("user_id")).longValue();
        users.lockById(owner).orElseThrow();
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ethereum_withdrawal_reservation_events WHERE withdrawal_id=?)", Boolean.class, id))) return null;
        var latest = jdbc.queryForList("SELECT a.started_at,r.completed_at,r.outcome FROM ethereum_withdrawal_dispatch_attempts a LEFT JOIN ethereum_withdrawal_dispatch_results r ON r.attempt_id=a.id WHERE a.withdrawal_id=? ORDER BY a.position DESC LIMIT 1", id);
        if (!latest.isEmpty()) {
            var previous = latest.getFirst();
            String result = (String) previous.get("outcome");
            if ("DURABLY_RESERVED".equals(result) || "DURABLY_REJECTED".equals(result)) return null;
            var boundary = previous.get("completed_at") == null ? (java.sql.Timestamp) previous.get("started_at") : (java.sql.Timestamp) previous.get("completed_at");
            if (boundary.toInstant().isAfter(java.time.Instant.now().minusSeconds(previous.get("completed_at") == null ? 30 : 5))) return null;
        }
        UUID attempt = UUID.randomUUID();
        jdbc.update("INSERT INTO ethereum_withdrawal_dispatch_attempts(id,withdrawal_id) VALUES(?,?)", attempt, id);
        audit.record(null, "ETH_WITHDRAWAL_DISPATCH_STARTED", "ethereum-withdrawal-attempt:" + attempt, "SUCCESS", "WITHDRAWAL:" + id);
        return new Claim(id, attempt, owner, (BigDecimal) row.get("total"));
    }
}
