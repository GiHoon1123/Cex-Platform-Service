package dustin.signer;

import dustin.cex.domains.bitcoin.BitcoinPayoutExecutionRecords;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.AuditService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** 고정 서명 요청을 하나씩 가져와 DB 밖에서 서명하고 결과와 필수 감사를 함께 보존한다. 방송 권한은 없다. */
public final class BitcoinSigningService {
    public record Outcome(UUID request,UUID attempt,String status,String txid){}
    public record StoredSigned(String txid,String rawTransaction){}
    private record Start(UUID request,UUID attempt,long owner,String executionMac,BitcoinPayoutExecutionRecords.Payload payload){}
    private final JdbcTemplate jdbc;private final CddCrypto crypto;private final ObjectMapper json;private final AuditService audit;private final BitcoinSignerRpcClient node;private final TransactionTemplate tx;
    public BitcoinSigningService(JdbcTemplate jdbc,CddCrypto crypto,ObjectMapper json,AuditService audit,BitcoinSignerRpcClient node,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.crypto=crypto;this.json=json;this.audit=audit;this.node=node;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Optional<Outcome> processNext(){
        Start start=tx.execute(t->start());if(start==null)return Optional.empty();
        try{return Optional.of(finish(start,node.sign(start.payload())));}catch(BitcoinSignerRpcClient.SignFailure failure){return Optional.of(fail(start,failure.failure.name()));}
    }
    private Start start(){
        var rows=jdbc.queryForList("""
            SELECT q.id request_id,q.execution_mac,e.* FROM bitcoin_payout_sign_requests q
             JOIN bitcoin_payout_executions e ON e.withdrawal_id=q.withdrawal_id
             WHERE NOT EXISTS(SELECT 1 FROM bitcoin_payout_sign_heads h WHERE h.request_id=q.id)
              AND(SELECT count(*) FROM bitcoin_payout_sign_attempts a WHERE a.request_id=q.id)<100
              AND NOT EXISTS(SELECT 1 FROM bitcoin_payout_sign_attempts a LEFT JOIN bitcoin_payout_sign_results r ON r.attempt_id=a.id
               WHERE a.request_id=q.id AND a.position=(SELECT max(z.position) FROM bitcoin_payout_sign_attempts z WHERE z.request_id=q.id)
                AND((r.status IS NULL AND a.started_at>clock_timestamp()-interval '10 seconds') OR(r.status='UNKNOWN' AND r.recorded_at>clock_timestamp()-interval '5 seconds') OR r.status='REJECTED'))
             ORDER BY q.requested_at,q.id FOR UPDATE OF q SKIP LOCKED LIMIT 1
            """);
        if(rows.isEmpty())return null;var row=rows.getFirst();UUID request=(UUID)row.get("request_id");var payload=read(row);
        int position=jdbc.queryForObject("SELECT coalesce(max(position),0)+1 FROM bitcoin_payout_sign_attempts WHERE request_id=?",Integer.class,request);
        UUID attempt=UUID.randomUUID();jdbc.update("INSERT INTO bitcoin_payout_sign_attempts(id,request_id,position) VALUES(?,?,?)",attempt,request,position);
        audit.record(null,"BTC_NATIVE_SIGN_STARTED","bitcoin-sign-attempt:"+attempt,"SUCCESS","POSITION:"+position);
        return new Start(request,attempt,payload.funds().customerId(),(String)row.get("execution_mac"),payload);
    }
    private Outcome finish(Start start,BitcoinSignerRpcClient.Signed signed){
        return tx.execute(t->{var current=lock(start);if(jdbc.queryForObject("SELECT count(*) FROM bitcoin_payout_sign_heads WHERE request_id=?",Long.class,start.request())!=0)throw new IllegalStateException("이미 확정한 서명 요청입니다");
            String mac=crypto.fingerprint("btc-native-signed-transaction-v1",signed.rawTransaction());
            byte[] cipher=crypto.encrypt(start.owner(),start.attempt(),signed.rawTransaction().getBytes(StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO bitcoin_payout_sign_results(attempt_id,status,txid,key_id,ciphertext,content_mac) VALUES(?,'SIGNED',?,?,?,?)",start.attempt(),signed.txid(),crypto.keyId(),cipher,mac);
            jdbc.update("INSERT INTO bitcoin_payout_sign_heads(request_id,attempt_id,txid) VALUES(?,?,?)",start.request(),start.attempt(),signed.txid());
            audit.record(null,"BTC_NATIVE_PAYOUT_SIGNED","bitcoin-sign-attempt:"+start.attempt(),"SUCCESS",mac);
            return new Outcome(start.request(),start.attempt(),"SIGNED",signed.txid());});
    }
    private Outcome fail(Start start,String status){
        return tx.execute(t->{lock(start);jdbc.update("INSERT INTO bitcoin_payout_sign_results(attempt_id,status) VALUES(?,?)",start.attempt(),status);
            audit.record(null,"BTC_NATIVE_PAYOUT_SIGN_FAILED","bitcoin-sign-attempt:"+start.attempt(),"FAILURE",status);
            return new Outcome(start.request(),start.attempt(),status,null);});
    }
    /** 다음 방송 워커도 같은 암호/HMAC 검증을 거친 고정 원시 거래만 읽는다. */
    public StoredSigned readSigned(UUID request){
        return tx.execute(t->{var rows=jdbc.queryForList("""
            SELECT h.txid,r.*,e.user_id FROM bitcoin_payout_sign_heads h JOIN bitcoin_payout_sign_results r ON r.attempt_id=h.attempt_id
             JOIN bitcoin_payout_sign_requests q ON q.id=h.request_id JOIN bitcoin_payout_executions e ON e.withdrawal_id=q.withdrawal_id WHERE h.request_id=?
            """,request);if(rows.size()!=1)throw new IllegalStateException("확정 서명 결과가 없습니다");var row=rows.getFirst();
            if(!"SIGNED".equals(row.get("status"))||!crypto.keyId().equals(row.get("key_id")))throw new IllegalStateException("서명 결과 원본을 확인할 수 없습니다");
            String raw=new String(crypto.decrypt(((Number)row.get("user_id")).longValue(),(UUID)row.get("attempt_id"),(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("btc-native-signed-transaction-v1",raw).equals(row.get("content_mac")))throw new IllegalStateException("서명 결과 원본을 확인할 수 없습니다");
            return new StoredSigned((String)row.get("txid"),raw);});
    }
    private Map<String,Object> lock(Start start){
        var rows=jdbc.queryForList("SELECT q.execution_mac,e.* FROM bitcoin_payout_sign_requests q JOIN bitcoin_payout_executions e ON e.withdrawal_id=q.withdrawal_id WHERE q.id=? FOR UPDATE OF q",start.request());
        if(rows.size()!=1||!start.executionMac().equals(rows.getFirst().get("execution_mac"))||!start.payload().equals(read(rows.getFirst())))throw new IllegalStateException("서명 요청 원본이 변경됐습니다");return rows.getFirst();
    }
    private BitcoinPayoutExecutionRecords.Payload read(Map<String,Object> row){
        try{
            long owner=((Number)row.get("user_id")).longValue();UUID withdrawal=(UUID)row.get("withdrawal_id");
            if(!crypto.keyId().equals(row.get("key_id")))throw new IllegalStateException();String raw=new String(crypto.decrypt(owner,withdrawal,(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("btc-native-payout-execution-v1",raw).equals(row.get("content_mac"))||!row.get("content_mac").equals(row.get("execution_mac")))throw new IllegalStateException();
            var p=json.readValue(raw,BitcoinPayoutExecutionRecords.Payload.class);
            if(!BitcoinPayoutExecutionRecords.POLICY.equals(p.policy())||!withdrawal.equals(p.withdrawalId())||p.funds().customerId()!=owner
                    ||!p.reviewId().equals(row.get("review_id"))||!p.candidateId().equals(row.get("candidate_id"))||!p.prepared().network().equals(row.get("network"))
                    ||!p.prepared().unsignedTxid().equals(row.get("unsigned_txid"))||!p.authorizedAt().equals(((Timestamp)row.get("authorized_at")).toInstant()))throw new IllegalStateException();return p;
        }catch(RuntimeException invalid){throw new IllegalStateException("고정 서명 요청 원본을 확인할 수 없습니다");}
    }
}
