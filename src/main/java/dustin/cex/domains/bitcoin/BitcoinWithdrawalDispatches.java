package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.AuditService;
import dustin.cex.shared.http.EngineWithdrawalClient;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 영속 원본과 시도를 먼저 기록한 뒤 같은 ID/총액으로 엔진을 재확인한다. 응답만으로 자산을 반환하지 않는다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinWithdrawalDispatches {
    public record Execution(UUID withdrawalId, UUID attemptId, EngineWithdrawalClient.Outcome transport) {}
    private record Claim(UUID withdrawalId, UUID attemptId, long owner, BigDecimal gross) {}

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuditService audit;
    private final EngineWithdrawalClient engine;
    private final TransactionTemplate tx;

    public BitcoinWithdrawalDispatches(JdbcTemplate jdbc, UserRepository users, AuditService audit,
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
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("출금 엔진 호출은 DB 잠금을 해제한 뒤 실행하세요");
        if (id == null) throw new IllegalArgumentException("출금 원본 ID가 필요합니다");
        Claim claim = tx.execute(t -> claim(id));
        if (claim == null) return Optional.empty();
        EngineWithdrawalClient.Outcome outcome;
        try {
            outcome = engine.execute(new EngineWithdrawalClient.Command(id, claim.owner(), "BTC",
                claim.gross(), EngineWithdrawalClient.Action.RESERVE));
            if (outcome == null) outcome = EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;
        } catch (RuntimeException uncertain) {
            // 전송/응답 과정의 예외는 미접수를 증명하지 않는다. 같은 금융 ID로만 나중에 재확인한다.
            outcome = EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;
        }
        var result = outcome;
        tx.executeWithoutResult(t -> {
            users.lockById(claim.owner()).orElseThrow(() -> new IllegalStateException("출금 고객 원본이 없습니다"));
            jdbc.update("INSERT INTO bitcoin_withdrawal_dispatch_results(attempt_id,outcome) VALUES(?,?)",
                claim.attemptId(), result.name());
            audit.record(null, "BTC_WITHDRAWAL_DISPATCH_FINISHED", "bitcoin-withdrawal-attempt:" + claim.attemptId(),
                "SUCCESS", "OUTCOME:" + result.name());
        });
        return Optional.of(new Execution(id, claim.attemptId(), result));
    }

    public Optional<Execution> processNext() {
        var due = jdbc.queryForList("""
            SELECT w.id FROM bitcoin_withdrawals w
             LEFT JOIN bitcoin_withdrawal_reservation_events e ON e.withdrawal_id=w.id
             WHERE e.withdrawal_id IS NULL
              AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_dispatch_attempts a
                JOIN bitcoin_withdrawal_dispatch_results r ON r.attempt_id=a.id
                WHERE a.withdrawal_id=w.id AND r.outcome IN('DURABLY_RESERVED','DURABLY_REJECTED'))
              AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_dispatch_attempts recent
               LEFT JOIN bitcoin_withdrawal_dispatch_results rr ON rr.attempt_id=recent.id
               WHERE recent.withdrawal_id=w.id AND recent.position=(SELECT max(p.position)
                 FROM bitcoin_withdrawal_dispatch_attempts p WHERE p.withdrawal_id=w.id)
                AND (rr.attempt_id IS NULL AND recent.started_at>clock_timestamp()-interval '30 seconds'
                  OR rr.attempt_id IS NOT NULL AND rr.completed_at>clock_timestamp()-interval '5 seconds'))
             ORDER BY w.created_at,w.id LIMIT 1
            """, UUID.class);
        return due.isEmpty() ? Optional.empty() : execute(due.getFirst());
    }

    private Claim claim(UUID id) {
        var originals = jdbc.queryForList("SELECT user_id,total FROM bitcoin_withdrawals WHERE id=?", id);
        if (originals.size() != 1) return null;
        var row = originals.getFirst();
        long owner = ((Number) row.get("user_id")).longValue();
        users.lockById(owner).orElseThrow(() -> new IllegalStateException("출금 고객 원본이 없습니다"));
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_reservation_events WHERE withdrawal_id=?)",
                Boolean.class, id))) return null;
        // WAL 성공/금융 거절 응답도 금융 소비의 증거는 아니지만 같은 예약 명령의 전송은 더 늘리지 않는다.
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_dispatch_attempts a
             JOIN bitcoin_withdrawal_dispatch_results r ON r.attempt_id=a.id
             WHERE a.withdrawal_id=? AND r.outcome IN('DURABLY_RESERVED','DURABLY_REJECTED'))
            """, Boolean.class, id))) return null;
        var previous = jdbc.queryForList("""
            SELECT CASE WHEN r.attempt_id IS NULL THEN a.started_at<=clock_timestamp()-interval '30 seconds'
             ELSE r.completed_at<=clock_timestamp()-interval '5 seconds' END due
             FROM bitcoin_withdrawal_dispatch_attempts a LEFT JOIN bitcoin_withdrawal_dispatch_results r ON r.attempt_id=a.id
             WHERE a.withdrawal_id=? ORDER BY a.position DESC LIMIT 1
            """, id);
        if (!previous.isEmpty() && !Boolean.TRUE.equals(previous.getFirst().get("due"))) return null;
        UUID attempt = UUID.randomUUID();
        jdbc.update("INSERT INTO bitcoin_withdrawal_dispatch_attempts(id,withdrawal_id) VALUES(?,?)", attempt, id);
        audit.record(null, "BTC_WITHDRAWAL_DISPATCH_STARTED", "bitcoin-withdrawal-attempt:" + attempt,
            "SUCCESS", "WITHDRAWAL:" + id);
        return new Claim(id, attempt, owner, (BigDecimal) row.get("total"));
    }
}
