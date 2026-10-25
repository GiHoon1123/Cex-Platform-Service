package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 현재 노드 관찰로 온체인 조건만 판단한다. 고객 자격/위험 심사/원장/엔진 적립 허용은 이 결정과 별개다. */
@Service
public class BitcoinDepositQualificationService {
    static final String POLICY="BTC_CHAIN_V1";
    public enum Reason {OUTPUT_MISMATCH,CONFLICTED,WAITING_CHAIN,STALE_OBSERVATION,IMMATURE_COINBASE,WAITING_CONFIRMATIONS,CHAIN_READY}
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    public BitcoinDepositQualificationService(JdbcTemplate jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}
    @Transactional(propagation=Propagation.MANDATORY)
    public int evaluate(long observationId){
        // 현재 관찰 행을 짧게 공유 잠금해 판단/감사 확정 사이에 원본 포인터가 교체되는 틈을 닫는다. RPC는 없다.
        var sources=jdbc.queryForList("""
            SELECT o.*,o.read_started_at<=clock_timestamp() AND o.read_started_at>=clock_timestamp()-interval '2 minutes' AS fresh
             FROM bitcoin_transaction_observations o JOIN bitcoin_observation_heads h ON h.observation_id=o.id
             WHERE o.id=? FOR SHARE OF h
            """,observationId);
        if(sources.isEmpty()){
            audit.record(null,"BTC_DEPOSIT_CHAIN_EVALUATION_SKIPPED","bitcoin-observation:"+observationId,"DENIED","SUPERSEDED_OBSERVATION");return 0;
        }
        var source=sources.getFirst();
        var deposits=jdbc.queryForList("""
            SELECT r.id,COALESCE(count(e)=1 AND bool_and(e->>'address'=a.address AND e->>'scriptPubKey'=a.script_pub_key
                AND (e->>'amount')::numeric=r.amount),false) AS matches
             FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id
             JOIN bitcoin_transaction_observations o ON o.id=? AND o.network=r.network AND o.txid=r.txid AND o.wallet=a.wallet
             LEFT JOIN LATERAL jsonb_array_elements(o.outputs) e ON (e->>'vout')::integer=r.vout GROUP BY r.id ORDER BY r.id
            """,observationId);
        int created=0;
        for(var deposit:deposits){
            UUID receipt=(UUID)deposit.get("id");
            Reason reason=reason((Boolean)deposit.get("matches"),BitcoinDepositReader.Status.valueOf((String)source.get("status")),
                ((Number)source.get("confirmations")).longValue(),(Boolean)source.get("coinbase"),(Boolean)source.get("fresh"));
            // 같은 관찰도 시간이 지나면 오래된 근거가 된다. 현재 이유와 원본이 같은 재시도만 합치고 상태 변화는 새 기록으로 남긴다.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"btc-chain-decision-v1:"+receipt);
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM bitcoin_deposit_chain_heads h JOIN bitcoin_deposit_chain_decisions d ON d.id=h.decision_id
                 WHERE h.receipt_id=? AND d.observation_id=? AND d.policy_id=? AND d.reason=?)
                """,Boolean.class,receipt,observationId,POLICY,reason.name())))continue;
            long id=jdbc.queryForObject("INSERT INTO bitcoin_deposit_chain_decisions(receipt_id,observation_id,policy_id,reason) VALUES(?,?,?,?) RETURNING id",
                Long.class,receipt,observationId,POLICY,reason.name());
            jdbc.update("""
                INSERT INTO bitcoin_deposit_chain_heads(receipt_id,decision_id) VALUES(?,?)
                 ON CONFLICT(receipt_id) DO UPDATE SET decision_id=excluded.decision_id
                """,receipt,id);
            audit.record(null,"BTC_DEPOSIT_CHAIN_EVALUATED","bitcoin-deposit:"+receipt,"SUCCESS","DECISION:"+id+":"+reason.name());created++;
        }
        return created;
    }
    static Reason reason(boolean matches,BitcoinDepositReader.Status status,long confirmations,boolean coinbase,boolean fresh){
        if(status==null||(status==BitcoinDepositReader.Status.CONFIRMED?confirmations<=0:status==BitcoinDepositReader.Status.CONFLICTED?confirmations>=0:confirmations!=0))throw new IllegalArgumentException("관찰 상태와 확정 수가 다릅니다");
        if(!matches)return Reason.OUTPUT_MISMATCH;
        if(status==BitcoinDepositReader.Status.CONFLICTED)return Reason.CONFLICTED;
        if(status==BitcoinDepositReader.Status.UNCONFIRMED)return Reason.WAITING_CHAIN;
        if(!fresh)return Reason.STALE_OBSERVATION;
        // 일반6회는 프로젝트의 보수적 정책이다. 보상 거래101 깊이는 Core 지갑의 성숙도 계산과 맞춘다.
        if(coinbase&&confirmations<101)return Reason.IMMATURE_COINBASE;
        if(confirmations<6)return Reason.WAITING_CONFIRMATIONS;
        return Reason.CHAIN_READY;
    }
}
