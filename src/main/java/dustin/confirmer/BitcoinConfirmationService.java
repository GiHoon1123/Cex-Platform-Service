package dustin.confirmer;

import dustin.cex.domains.security.AuditService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 방송 머리를 하나씩 읽어 외부 조회 밖에서는 잠금을 놓고, 확인 결과와 감사를 불변 저장한다. */
public final class BitcoinConfirmationService {
    public record Outcome(UUID request,UUID attempt,String status){}private record Start(UUID request,UUID attempt,String txid){}
    private final JdbcTemplate jdbc;private final AuditService audit;private final BitcoinConfirmationRpcClient node;private final TransactionTemplate tx;
    public BitcoinConfirmationService(JdbcTemplate jdbc,AuditService audit,BitcoinConfirmationRpcClient node,PlatformTransactionManager manager){this.jdbc=jdbc;this.audit=audit;this.node=node;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);}
    public Optional<Outcome> processNext(){Start start=tx.execute(t->start());if(start==null)return Optional.empty();return Optional.of(finish(start,node.observe(start.txid())));}
    private Start start(){var rows=jdbc.queryForList("""
        SELECT b.request_id,b.txid FROM bitcoin_payout_broadcast_heads b WHERE NOT EXISTS(SELECT 1 FROM bitcoin_payout_chain_heads h WHERE h.request_id=b.request_id)
         AND NOT EXISTS(SELECT 1 FROM bitcoin_payout_chain_attempts a LEFT JOIN bitcoin_payout_chain_results r ON r.attempt_id=a.id WHERE a.request_id=b.request_id
          AND a.position=(SELECT max(z.position) FROM bitcoin_payout_chain_attempts z WHERE z.request_id=b.request_id) AND((r.status IS NULL AND a.started_at>clock_timestamp()-interval '10 seconds') OR(r.status IN('WAITING','UNKNOWN') AND r.recorded_at>clock_timestamp()-interval '60 seconds') OR r.status='CONFLICT'))
         ORDER BY b.request_id FOR UPDATE OF b SKIP LOCKED LIMIT 1
        """);if(rows.isEmpty())return null;var row=rows.getFirst();UUID request=(UUID)row.get("request_id");int position=jdbc.queryForObject("SELECT coalesce(max(position),0)+1 FROM bitcoin_payout_chain_attempts WHERE request_id=?",Integer.class,request);UUID attempt=UUID.randomUUID();jdbc.update("INSERT INTO bitcoin_payout_chain_attempts(id,request_id,position) VALUES(?,?,?)",attempt,request,position);audit.record(null,"BTC_NATIVE_CHAIN_OBSERVATION_STARTED","bitcoin-chain-attempt:"+attempt,"SUCCESS","POSITION:"+position);return new Start(request,attempt,(String)row.get("txid"));}
    private Outcome finish(Start start,BitcoinConfirmationRpcClient.Result result){return tx.execute(t->{var rows=jdbc.queryForList("SELECT txid FROM bitcoin_payout_broadcast_heads WHERE request_id=? FOR UPDATE",start.request());if(rows.size()!=1||!start.txid().equals(rows.getFirst().get("txid")))throw new IllegalStateException("고정 방송 거래가 변경됐습니다");String status=result.status().name();jdbc.update("INSERT INTO bitcoin_payout_chain_results(attempt_id,status,txid,block_hash,block_height,confirmations,observed_tip) VALUES(?,?,?,?,?,?,?)",start.attempt(),status,result.txid(),result.blockHash(),result.status()==BitcoinConfirmationRpcClient.Status.CONFIRMED?result.blockHeight():null,result.status()==BitcoinConfirmationRpcClient.Status.CONFIRMED?result.confirmations():null,result.observedTip());if(result.status()==BitcoinConfirmationRpcClient.Status.CONFIRMED)jdbc.update("INSERT INTO bitcoin_payout_chain_heads(request_id,attempt_id,txid,block_hash,block_height,confirmations) VALUES(?,?,?,?,?,?)",start.request(),start.attempt(),result.txid(),result.blockHash(),result.blockHeight(),result.confirmations());audit.record(null,result.status()==BitcoinConfirmationRpcClient.Status.CONFIRMED?"BTC_NATIVE_PAYOUT_CHAIN_CONFIRMED":"BTC_NATIVE_PAYOUT_CHAIN_OBSERVED","bitcoin-chain-attempt:"+start.attempt(),result.status()==BitcoinConfirmationRpcClient.Status.CONFIRMED?"SUCCESS":"FAILURE",status);return new Outcome(start.request(),start.attempt(),status);});}
}
