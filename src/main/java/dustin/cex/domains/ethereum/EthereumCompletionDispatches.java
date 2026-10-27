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
import org.springframework.transaction.support.TransactionTemplate;

/** 정식 체인 확정 증거가 있는 ETH 출금만 엔진의 같은 예약 ID로 완료한다. */
@Service
public class EthereumCompletionDispatches {
    public record Execution(UUID withdrawal, UUID attempt, EngineWithdrawalClient.Outcome outcome) {}
    private record Claim(UUID withdrawal, UUID attempt, long owner, BigDecimal total) {}
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuditService audit;
    private final EngineWithdrawalClient engine;
    private final TransactionTemplate tx;

    public EthereumCompletionDispatches(JdbcTemplate jdbc, UserRepository users, AuditService audit,
            EngineWithdrawalClient engine, PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.audit=audit;this.engine=engine;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Optional<Execution> processNext() {
        Claim claim=tx.execute(status->claim());if(claim==null)return Optional.empty();
        EngineWithdrawalClient.Outcome outcome;
        try{outcome=engine.execute(new EngineWithdrawalClient.Command(claim.withdrawal(),claim.owner(),"ETH",claim.total(),EngineWithdrawalClient.Action.COMPLETE));if(outcome==null)outcome=EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;}
        catch(RuntimeException uncertain){outcome=EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;}
        var saved=outcome;tx.executeWithoutResult(status->{users.lockById(claim.owner()).orElseThrow();jdbc.update("INSERT INTO ethereum_completion_results(attempt_id,outcome) VALUES(?,?)",claim.attempt(),saved.name());audit.record(null,"ETH_WITHDRAWAL_COMPLETE_FINISHED","ethereum-completion:"+claim.attempt(),"SUCCESS","OUTCOME:"+saved.name());});
        return Optional.of(new Execution(claim.withdrawal(),claim.attempt(),saved));
    }
    private Claim claim(){
        var rows=jdbc.queryForList("SELECT w.id,w.user_id,w.total FROM ethereum_withdrawals w JOIN ethereum_payout_confirmations c ON c.withdrawal_id=w.id WHERE NOT EXISTS(SELECT 1 FROM ethereum_completion_events e WHERE e.withdrawal_id=w.id) AND NOT EXISTS(SELECT 1 FROM ethereum_completion_attempts a JOIN ethereum_completion_results r ON r.attempt_id=a.id WHERE a.withdrawal_id=w.id AND r.outcome IN('DURABLY_COMPLETED','DURABLY_REJECTED')) AND NOT EXISTS(SELECT 1 FROM ethereum_completion_attempts a WHERE a.withdrawal_id=w.id AND a.started_at>clock_timestamp()-interval '30 seconds') ORDER BY c.confirmed_at LIMIT 1 FOR UPDATE OF w SKIP LOCKED");
        if(rows.isEmpty())return null;var row=rows.getFirst();long owner=((Number)row.get("user_id")).longValue();users.lockById(owner).orElseThrow();UUID id=(UUID)row.get("id"),attempt=UUID.randomUUID();jdbc.update("INSERT INTO ethereum_completion_attempts(id,withdrawal_id) VALUES(?,?)",attempt,id);audit.record(null,"ETH_WITHDRAWAL_COMPLETE_STARTED","ethereum-completion:"+attempt,"SUCCESS","WITHDRAWAL:"+id);return new Claim(id,attempt,owner,(BigDecimal)row.get("total"));
    }
}
