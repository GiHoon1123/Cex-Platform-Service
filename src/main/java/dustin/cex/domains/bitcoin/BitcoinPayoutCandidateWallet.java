package dustin.cex.domains.bitcoin;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.AuditService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 영속 배정이 있는 후보의 노드 잠금/해제 시도와 실제 관측을 보존한다. 금융 확정이나 서명 권한은 부여하지 않는다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinPayoutCandidateWallet {
    public record Proof(UUID candidateId,String candidateHash,String operation,BitcoinPayoutInputLocks.Observation observation) {
        @Override public String toString(){return "PayoutWalletProof[수탁 원문 생략]";}
    }
    public record Outcome(UUID attemptId,String status,boolean allocationReleased){}
    private record Start(UUID id,BitcoinPayoutCandidates.Candidate candidate,String operation){}
    private final BitcoinPayoutCandidates candidates;
    private final BitcoinPayoutInputLocks node;
    private final JdbcTemplate jdbc;
    private final CddCrypto crypto;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public BitcoinPayoutCandidateWallet(BitcoinPayoutCandidates candidates,BitcoinPayoutInputLocks node,JdbcTemplate jdbc,CddCrypto crypto,ObjectMapper json,AuditService audit,PlatformTransactionManager manager){
        this.candidates=candidates;this.node=node;this.jdbc=jdbc;this.crypto=crypto;this.json=json;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 현재 승인과 DB 입력 귀속을 먼저 잠근다. 시작 감사가 커밋되지 않으면 노드에 접근하지 않는다. */
    public Outcome lock(UUID candidateId){
        boundary();var start=tx.execute(t->{var c=candidates.forLock(candidateId);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_payout_wallet_attempts WHERE candidate_id=? AND operation='UNLOCK')",Boolean.class,candidateId)))throw held();
            return start(c,"LOCK");});return execute(start,false);
    }
    /** 취소·새 승인·만료와 미실행을 확인한 자기 배정만 정리한다. 금액 환급이나 금융 이력 삭제는 하지 않는다. */
    public Outcome release(UUID candidateId){
        boundary();var start=tx.execute(t->{
            var owners=jdbc.queryForList("SELECT user_id FROM bitcoin_payout_candidates WHERE id=?",Long.class,candidateId);if(owners.size()!=1)throw held();
            jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",owners.getFirst());
            if(released(candidateId))return null;
            var c=candidates.active(candidateId);String reason=retirement(candidateId);if(reason==null)throw held();
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_payout_wallet_attempts WHERE candidate_id=? AND operation='LOCK')",Boolean.class,candidateId))){
                close(c,null,reason);return null;
            }
            return start(c,"UNLOCK");
        });
        return start==null?new Outcome(null,"RELEASED",true):execute(start,true);
    }
    private Start start(BitcoinPayoutCandidates.Candidate candidate,String operation){
        UUID id=candidate.payload().id();
        var previous=jdbc.queryForList("SELECT coalesce(r.recorded_at,a.started_at) AS stamp FROM bitcoin_payout_wallet_attempts a LEFT JOIN bitcoin_payout_wallet_results r ON r.attempt_id=a.id WHERE a.candidate_id=? ORDER BY a.position DESC LIMIT 1",id);
        if(!previous.isEmpty()&&((Timestamp)previous.getFirst().get("stamp")).toInstant().isAfter(now().minusSeconds(30)))throw held();
        UUID attempt=UUID.randomUUID();jdbc.update("INSERT INTO bitcoin_payout_wallet_attempts(id,candidate_id,operation) VALUES(?,?,?)",attempt,id,operation);
        audit.record(null,"BTC_PAYOUT_WALLET_STARTED","bitcoin-payout-wallet:"+attempt,"SUCCESS",operation);return new Start(attempt,candidate,operation);
    }
    private Outcome execute(Start start,boolean releasing){
        var p=start.candidate().payload();var prepared=p.prepared();BitcoinPayoutInputLocks.Observation observation=null;
        try {
            observation=releasing?node.release(prepared.network(),prepared.wallet(),prepared.inputs()):node.ensure(prepared.network(),prepared.wallet(),prepared.inputs());
            if(!matches(prepared,observation,!releasing))observation=null;
        }catch(ResponseStatusException unknown){if(unknown.getStatusCode().value()!=503)throw unknown;}
        var observed=observation;
        return tx.execute(t->{
            // 취소나 권한 변경이 호출 도중 발생해도 외부 관측 사실은 남긴다. 그것이 현재 지급 허가를 되살리지는 않는다.
            jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",p.funds().customerId());
            String status=observed==null?"UNKNOWN":"OBSERVED",key=null,mac=null;byte[] cipher=null;
            if(observed!=null){String plain=json.writeValueAsString(new Proof(p.id(),start.candidate().payloadHash(),start.operation(),observed));
                if(plain.getBytes(StandardCharsets.UTF_8).length>32768)throw held();key=crypto.keyId();mac=crypto.fingerprint("btc-payout-wallet-observation-v1",plain);cipher=crypto.encrypt(p.funds().customerId(),start.id(),plain.getBytes(StandardCharsets.UTF_8));}
            jdbc.update("INSERT INTO bitcoin_payout_wallet_results(attempt_id,status,locked,key_id,ciphertext,content_mac) VALUES(?,?,?,?,?,?)",start.id(),status,observed==null?null:observed.locked(),key,cipher,mac);
            audit.record(null,"BTC_PAYOUT_WALLET_OBSERVED","bitcoin-payout-wallet:"+start.id(),"SUCCESS",status);
            boolean closed=released(p.id());
            if(releasing&&observed!=null&&!closed){String reason=retirement(p.id());if(reason!=null){candidates.active(p.id());close(start.candidate(),start.id(),reason);closed=true;}}
            return new Outcome(start.id(),status,closed);
        });
    }
    private boolean matches(BitcoinPayoutClient.Prepared p,BitcoinPayoutInputLocks.Observation o,boolean locked){
        if(o==null||!p.network().equals(o.network())||!p.wallet().equals(o.wallet())||locked!=o.locked()||o.observedAt()==null)return false;
        var refs=p.inputs().stream().map(i->i.txid()+":"+i.vout()).sorted().toList();return refs.equals(o.inputs().stream().sorted().toList());
    }
    private void close(BitcoinPayoutCandidates.Candidate c,UUID observed,String reason){
        UUID id=c.payload().id();jdbc.update("INSERT INTO bitcoin_payout_candidate_releases(candidate_id,reason,wallet_attempt) VALUES(?,?,?)",id,reason,observed);
        jdbc.update("DELETE FROM bitcoin_payout_input_reservations WHERE candidate_id=?",id);jdbc.update("DELETE FROM bitcoin_payout_active_candidates WHERE candidate_id=?",id);
        audit.record(null,"BTC_PAYOUT_CANDIDATE_RELEASED","bitcoin-payout-candidate:"+id,"SUCCESS",reason);
    }
    private String retirement(UUID id){return jdbc.queryForObject("SELECT bitcoin_payout_retirement(?)",String.class,id);}
    private boolean released(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_payout_candidate_releases WHERE candidate_id=?)",Boolean.class,id));}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static void boundary(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입력 잠금 업무는 독립 트랜잭션으로 실행하세요");}
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.CONFLICT,"현재 입력 배정과 미실행 종료 근거를 확인하세요");}
}
