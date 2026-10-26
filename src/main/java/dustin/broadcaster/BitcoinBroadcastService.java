package dustin.broadcaster;

import dustin.cex.domains.bitcoin.BitcoinSignerEnvelopeCrypto;
import dustin.cex.domains.security.AuditService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 확정 서명 결과를 하나씩 가져와 데이터베이스 트랜잭션 밖에서 방송하고 결과를 불변 이력으로 남긴다. */
public final class BitcoinBroadcastService {
    public record Outcome(UUID request,UUID attempt,String status,String txid){}
    private record Start(UUID request,UUID attempt,String txid,String raw){}
    private final JdbcTemplate jdbc;private final BitcoinSignerEnvelopeCrypto crypto;private final AuditService audit;private final BitcoinBroadcastRpcClient node;private final TransactionTemplate tx;
    public BitcoinBroadcastService(JdbcTemplate jdbc,BitcoinSignerEnvelopeCrypto crypto,AuditService audit,BitcoinBroadcastRpcClient node,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.crypto=crypto;this.audit=audit;this.node=node;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 외부 호출 전 시도를 커밋하고 외부 호출 뒤 결과를 별도 커밋해 프로세스 종료와 응답 불명을 구분한다. */
    public Optional<Outcome> processNext(){Start start=tx.execute(t->start());if(start==null)return Optional.empty();return Optional.of(finish(start,node.publish(start.txid(),start.raw())));}
    /** 잠근 서명 머리 하나만 선점하며 암호문은 인증된 뒤에만 데이터베이스 트랜잭션 밖으로 전달한다. */
    private Start start(){
        var rows=jdbc.queryForList("""
            SELECT h.request_id,h.attempt_id signed_attempt,h.txid,r.key_id,r.ciphertext,r.content_mac,e.user_id
              FROM bitcoin_payout_sign_heads h JOIN bitcoin_payout_sign_results r ON r.attempt_id=h.attempt_id
              JOIN bitcoin_payout_sign_requests q ON q.id=h.request_id JOIN bitcoin_payout_executions e ON e.withdrawal_id=q.withdrawal_id
             WHERE NOT EXISTS(SELECT 1 FROM bitcoin_payout_broadcast_heads x WHERE x.request_id=h.request_id)
               AND(SELECT count(*) FROM bitcoin_payout_broadcast_attempts a WHERE a.request_id=h.request_id)<100
               AND NOT EXISTS(SELECT 1 FROM bitcoin_payout_broadcast_attempts a LEFT JOIN bitcoin_payout_broadcast_results z ON z.attempt_id=a.id
                 WHERE a.request_id=h.request_id AND a.position=(SELECT max(y.position) FROM bitcoin_payout_broadcast_attempts y WHERE y.request_id=h.request_id)
                  AND((z.status IS NULL AND a.started_at>clock_timestamp()-interval '10 seconds') OR(z.status='UNKNOWN' AND z.recorded_at>clock_timestamp()-interval '5 seconds') OR z.status='REJECTED'))
             ORDER BY q.requested_at,h.request_id FOR UPDATE OF h SKIP LOCKED LIMIT 1
            """);
        if(rows.isEmpty())return null;var row=rows.getFirst();UUID request=(UUID)row.get("request_id");String raw=readRaw(row);
        int position=jdbc.queryForObject("SELECT coalesce(max(position),0)+1 FROM bitcoin_payout_broadcast_attempts WHERE request_id=?",Integer.class,request);
        UUID attempt=UUID.randomUUID();jdbc.update("INSERT INTO bitcoin_payout_broadcast_attempts(id,request_id,position) VALUES(?,?,?)",attempt,request,position);
        audit.record(null,"BTC_NATIVE_BROADCAST_STARTED","bitcoin-broadcast-attempt:"+attempt,"SUCCESS","POSITION:"+position);
        return new Start(request,attempt,(String)row.get("txid"),raw);
    }
    private String readRaw(Map<String,Object> row){
        try{if(!crypto.keyId().equals(row.get("key_id")))throw new IllegalStateException();String raw=new String(crypto.decrypt(((Number)row.get("user_id")).longValue(),(UUID)row.get("signed_attempt"),(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);if(!crypto.fingerprint(raw).equals(row.get("content_mac")))throw new IllegalStateException();return raw;}
        catch(RuntimeException invalid){throw new IllegalStateException("서명 결과 원본을 확인할 수 없습니다");}
    }
    /** 노드 수신이 확인된 경우에만 현재 방송 머리를 만들고 결과·감사를 같은 금융 커밋으로 묶는다. */
    private Outcome finish(Start start,BitcoinBroadcastRpcClient.Result result){
        return tx.execute(t->{var rows=jdbc.queryForList("SELECT txid FROM bitcoin_payout_sign_heads WHERE request_id=? FOR UPDATE",start.request());if(rows.size()!=1||!start.txid().equals(rows.getFirst().get("txid")))throw new IllegalStateException("고정 서명 결과가 변경됐습니다");
            String status=result.status().name();if(result.status()==BitcoinBroadcastRpcClient.Status.ACCEPTED&&!start.txid().equals(result.txid()))throw new IllegalStateException("방송 거래 식별자가 고정 서명 결과와 다릅니다");
            jdbc.update("INSERT INTO bitcoin_payout_broadcast_results(attempt_id,status,txid) VALUES(?,?,?)",start.attempt(),status,result.txid());
            if(result.status()==BitcoinBroadcastRpcClient.Status.ACCEPTED)jdbc.update("INSERT INTO bitcoin_payout_broadcast_heads(request_id,attempt_id,txid) VALUES(?,?,?)",start.request(),start.attempt(),start.txid());
            audit.record(null,result.status()==BitcoinBroadcastRpcClient.Status.ACCEPTED?"BTC_NATIVE_PAYOUT_BROADCAST":"BTC_NATIVE_PAYOUT_BROADCAST_FAILED","bitcoin-broadcast-attempt:"+start.attempt(),result.status()==BitcoinBroadcastRpcClient.Status.ACCEPTED?"SUCCESS":"FAILURE",status);
            return new Outcome(start.request(),start.attempt(),status,result.txid());});
    }
}
