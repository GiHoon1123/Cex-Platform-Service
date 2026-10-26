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

/** 고객의 취소 의도를 먼저 보존한다. 엔진 예약과 해제의 금융 확정 전에는 고객 잔액을 반환하지 않는다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinWithdrawalCancellations {
    private record Claim(UUID withdrawal, UUID attempt, long owner, BigDecimal total) {}
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final BitcoinWithdrawalQuotes quotes;
    private final BitcoinWithdrawalRequests requests;
    private final EngineWithdrawalClient engine;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public BitcoinWithdrawalCancellations(JdbcTemplate jdbc, UserRepository users,
            BitcoinWithdrawalQuotes quotes, BitcoinWithdrawalRequests requests,
            EngineWithdrawalClient engine, AuditService audit, PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.quotes=quotes;this.requests=requests;
        this.engine=engine;this.audit=audit;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    public BitcoinWithdrawalRequests.Receipt cancel(long actor, UUID sid, UUID id) {
        independent();
        try { tx.executeWithoutResult(t -> {
            // 갱신된 본인 세션도 과거 요청을 취소할 수 있다. 만료된 견적으로 새 지급 권한을 만들지는 않는다.
            quotes.historicalForWithdrawal(actor,sid,id);
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawals WHERE id=? AND user_id=?)",Boolean.class,id,actor)))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"출금 원본이 없습니다");
            // 승인만 받은 요청은 아직 취소할 수 있다. 지급 워커가 실행을 확정한 뒤에는 반환 의도를 만들지 않는다.
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=?)",Boolean.class,id)))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"지급 실행이 확정되어 고객 취소할 수 없습니다");
            // 최초 예약이 거절됐으면 이미 전액 반환됐다. 같은 취소로 새 반환이나 해제를 만들지 않는다.
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_reservation_events
                 WHERE withdrawal_id=? AND event_type='withdrawal_rejected')
                """,Boolean.class,id))) return;
            int inserted=jdbc.update("""
                INSERT INTO bitcoin_withdrawal_cancellations(withdrawal_id,user_id,session_id)
                VALUES(?,?,?) ON CONFLICT(withdrawal_id) DO NOTHING
                """,id,actor,sid);
            if(inserted==1) audit.record(actor,"BTC_WITHDRAWAL_CANCEL_REQUESTED","bitcoin-withdrawal:"+id,
                "SUCCESS","RETURN_ORIGINAL_GROSS");
        }); } catch(org.springframework.web.server.ResponseStatusException denied) {
            if(denied.getStatusCode().is4xxClientError()) audit.denied(actor,"BTC_WITHDRAWAL_CANCEL",
                "bitcoin-withdrawal:"+id,"POLICY_"+denied.getStatusCode().value());
            throw denied;
        }
        execute(id);
        return requests.status(actor,sid,id);
    }

    public Optional<BitcoinWithdrawalDispatches.Execution> execute(UUID id) {
        independent();
        if(id==null)throw new IllegalArgumentException("출금 원본 ID가 필요합니다");
        Claim claim=tx.execute(t -> claim(id));
        if(claim==null)return Optional.empty();
        EngineWithdrawalClient.Outcome outcome;
        try {
            outcome=engine.execute(new EngineWithdrawalClient.Command(id,claim.owner(),"BTC",claim.total(),EngineWithdrawalClient.Action.RELEASE));
            if(outcome==null)outcome=EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;
        } catch(RuntimeException uncertain) {
            outcome=EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;
        }
        // HTTP 확인·거절·불명 모두 원장을 직접 바꾸지 않는다. 실제 반환은 해제 확정 이벤트의 한 TX다.
        var result=outcome;
        if(result!=EngineWithdrawalClient.Outcome.DURABLY_RELEASED
                &&result!=EngineWithdrawalClient.Outcome.DURABLY_REJECTED
                &&result!=EngineWithdrawalClient.Outcome.NOT_ACCEPTED)
            result=EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;
        var saved=result;
        tx.executeWithoutResult(t -> {
            users.lockById(claim.owner()).orElseThrow();
            jdbc.update("INSERT INTO bitcoin_withdrawal_release_results(attempt_id,outcome) VALUES(?,?)",claim.attempt(),saved.name());
            audit.record(null,"BTC_WITHDRAWAL_RELEASE_FINISHED","bitcoin-withdrawal-release:"+claim.attempt(),"SUCCESS","OUTCOME:"+saved.name());
        });
        return Optional.of(new BitcoinWithdrawalDispatches.Execution(id,claim.attempt(),saved));
    }

    public Optional<BitcoinWithdrawalDispatches.Execution> processNext() {
        var due=jdbc.queryForList("""
            SELECT c.withdrawal_id FROM bitcoin_withdrawal_cancellations c
             JOIN bitcoin_withdrawal_reservation_events e ON e.withdrawal_id=c.withdrawal_id AND e.event_type='withdrawal_reserved'
             WHERE NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_events f WHERE f.withdrawal_id=c.withdrawal_id)
              AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_attempts a
               JOIN bitcoin_withdrawal_release_results r ON r.attempt_id=a.id
               WHERE a.withdrawal_id=c.withdrawal_id AND r.outcome IN('DURABLY_RELEASED','DURABLY_REJECTED'))
              AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_attempts a
               LEFT JOIN bitcoin_withdrawal_release_results r ON r.attempt_id=a.id
               WHERE a.withdrawal_id=c.withdrawal_id AND a.position=(SELECT max(p.position)
                FROM bitcoin_withdrawal_release_attempts p WHERE p.withdrawal_id=c.withdrawal_id)
                AND (r.attempt_id IS NULL AND a.started_at>clock_timestamp()-interval '30 seconds'
                 OR r.attempt_id IS NOT NULL AND r.completed_at>clock_timestamp()-interval '5 seconds'))
             ORDER BY c.requested_at,c.withdrawal_id LIMIT 1
            """,UUID.class);
        return due.isEmpty()?Optional.empty():execute(due.getFirst());
    }

    private Claim claim(UUID id) {
        var rows=jdbc.queryForList("""
            SELECT w.user_id,w.total FROM bitcoin_withdrawals w
             JOIN bitcoin_withdrawal_cancellations c ON c.withdrawal_id=w.id WHERE w.id=?
            """,id);
        if(rows.size()!=1)return null;
        long owner=((Number)rows.getFirst().get("user_id")).longValue();
        users.lockById(owner).orElseThrow();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_reservation_events e
             WHERE e.withdrawal_id=? AND e.event_type='withdrawal_reserved')
             AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_events WHERE withdrawal_id=?)
             AND NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_release_attempts a
              JOIN bitcoin_withdrawal_release_results r ON r.attempt_id=a.id
              WHERE a.withdrawal_id=? AND r.outcome IN('DURABLY_RELEASED','DURABLY_REJECTED'))
            """,Boolean.class,id,id,id)))return null;
        var previous=jdbc.queryForList("""
            SELECT CASE WHEN r.attempt_id IS NULL THEN a.started_at<=clock_timestamp()-interval '30 seconds'
             ELSE r.completed_at<=clock_timestamp()-interval '5 seconds' END due
             FROM bitcoin_withdrawal_release_attempts a LEFT JOIN bitcoin_withdrawal_release_results r ON r.attempt_id=a.id
             WHERE a.withdrawal_id=? ORDER BY a.position DESC LIMIT 1
            """,id);
        if(!previous.isEmpty()&&!Boolean.TRUE.equals(previous.getFirst().get("due")))return null;
        UUID attempt=UUID.randomUUID();
        jdbc.update("INSERT INTO bitcoin_withdrawal_release_attempts(id,withdrawal_id) VALUES(?,?)",attempt,id);
        audit.record(null,"BTC_WITHDRAWAL_RELEASE_STARTED","bitcoin-withdrawal-release:"+attempt,"SUCCESS","WITHDRAWAL:"+id);
        return new Claim(id,attempt,owner,(BigDecimal)rows.getFirst().get("total"));
    }

    private static void independent() {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("출금 취소와 해제 전송은 독립 트랜잭션 경계를 사용하세요");
    }
}
