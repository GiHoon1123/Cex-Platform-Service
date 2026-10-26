package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.AuditService;
import dustin.cex.shared.http.EngineWithdrawalClient;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 여섯 확인 머리만 같은 출금 원본의 엔진 COMPLETE 명령으로 영속 재확인한다. */
@Service @DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinPayoutCompletionDispatches {
    public record Execution(UUID withdrawalId,UUID attemptId,EngineWithdrawalClient.Outcome transport){}private record Claim(UUID request,UUID attempt,long owner,BigDecimal gross){}
    private final JdbcTemplate jdbc;private final UserRepository users;private final AuditService audit;private final EngineWithdrawalClient engine;private final TransactionTemplate tx;
    public BitcoinPayoutCompletionDispatches(JdbcTemplate jdbc,UserRepository users,AuditService audit,EngineWithdrawalClient engine,PlatformTransactionManager manager){this.jdbc=jdbc;this.users=users;this.audit=audit;this.engine=engine;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);}
    /** 체인 권한과 시도를 먼저 커밋하고 엔진 HTTP 뒤 결과를 별도 커밋한다. HTTP 성공만으로 고객 잔액은 바꾸지 않는다. */
    public Optional<Execution> processNext(){Claim claim=tx.execute(t->claim());if(claim==null)return Optional.empty();EngineWithdrawalClient.Outcome outcome;try{outcome=engine.execute(new EngineWithdrawalClient.Command(claim.request(),claim.owner(),"BTC",claim.gross(),EngineWithdrawalClient.Action.COMPLETE));if(outcome==null)outcome=EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;}catch(RuntimeException uncertain){outcome=EngineWithdrawalClient.Outcome.OUTCOME_UNKNOWN;}var saved=outcome;tx.executeWithoutResult(t->{users.lockById(claim.owner()).orElseThrow();jdbc.update("INSERT INTO bitcoin_payout_completion_results(attempt_id,outcome) VALUES(?,?)",claim.attempt(),saved.name());audit.record(null,"BTC_WITHDRAWAL_COMPLETE_FINISHED","bitcoin-withdrawal-complete:"+claim.attempt(),"SUCCESS","OUTCOME:"+saved.name());});return Optional.of(new Execution(claim.request(),claim.attempt(),saved));}
    /** 여섯 확인 머리가 있고 아직 Kafka 완료가 없는 출금 하나만 잠근다. 불명 재확인은 같은 UUID와 총액을 유지한다. */
    private Claim claim(){var rows=jdbc.queryForList("""
        SELECT h.request_id,w.user_id,w.total FROM bitcoin_payout_chain_heads h JOIN bitcoin_withdrawals w ON w.id=h.request_id
         WHERE NOT EXISTS(SELECT 1 FROM bitcoin_withdrawal_completion_events e WHERE e.withdrawal_id=h.request_id)
          AND NOT EXISTS(SELECT 1 FROM bitcoin_payout_completion_attempts a JOIN bitcoin_payout_completion_results r ON r.attempt_id=a.id WHERE a.request_id=h.request_id AND r.outcome IN('DURABLY_COMPLETED','DURABLY_REJECTED'))
          AND NOT EXISTS(SELECT 1 FROM bitcoin_payout_completion_attempts a LEFT JOIN bitcoin_payout_completion_results r ON r.attempt_id=a.id WHERE a.request_id=h.request_id AND a.position=(SELECT max(z.position) FROM bitcoin_payout_completion_attempts z WHERE z.request_id=h.request_id) AND((r.attempt_id IS NULL AND a.started_at>clock_timestamp()-interval '30 seconds') OR(r.attempt_id IS NOT NULL AND r.completed_at>clock_timestamp()-interval '5 seconds')))
         ORDER BY h.request_id FOR UPDATE OF h SKIP LOCKED LIMIT 1
        """);if(rows.isEmpty())return null;var row=rows.getFirst();UUID request=(UUID)row.get("request_id");long owner=((Number)row.get("user_id")).longValue();users.lockById(owner).orElseThrow();int position=jdbc.queryForObject("SELECT coalesce(max(position),0)+1 FROM bitcoin_payout_completion_attempts WHERE request_id=?",Integer.class,request);UUID attempt=UUID.randomUUID();jdbc.update("INSERT INTO bitcoin_payout_completion_attempts(id,request_id,position) VALUES(?,?,?)",attempt,request,position);audit.record(null,"BTC_WITHDRAWAL_COMPLETE_STARTED","bitcoin-withdrawal-complete:"+attempt,"SUCCESS","WITHDRAWAL:"+request);return new Claim(request,attempt,owner,(BigDecimal)row.get("total"));}
}
