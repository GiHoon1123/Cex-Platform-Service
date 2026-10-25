package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;
import dustin.cex.domains.reconciliation.ReconciliationService;
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

/** 체인 위험의 복구 근거를 검증한다. 정상 근거를 만들어도 위험 제한·원장·엔진 금융 상태를 변경하지 않는다. */
@Service
@DependsOn("bitcoinCreditSchemaInitializer")
public class BitcoinChainRecoveryProofs {
    public static final String POLICY="BTC_CHAIN_RECOVERY_PROOF_V1";
    // record는 읽기 결과의 필드를 묶는 값 타입이다. READY도 해제 권한이 아니며 만료 시 조회에서 EXPIRED로 표현한다.
    public record Report(UUID id,UUID receiptId,String state,String reason,String scopeHash,String ledgerHash,
        UUID financialRun,UUID nativeRun,String blockHash,Long blockHeight,Instant expiresAt){}
    private record Payload(UUID id,UUID receiptId,long actorId,String scopeHash,String ledgerHash,String policy){}
    // 시작 트랜잭션에서 확보한 식별자와 지문만 전달한다. 사용자 엔티티나 잠금·DB 연결을 외부 조회 구간에 넘기지 않는다.
    private record Claim(UUID id,UUID receipt,long actor,UUID sid,long customer,String txid,String scope,String ledger,boolean created){}
    private record Evidence(String reason,UUID financial,UUID nativeRun,String blockHash,Long height){}
    private final JdbcTemplate jdbc;private final BitcoinSettings settings;private final BitcoinDepositOperatorAccess access;
    private final UserRepository users;private final OperatorSessionPolicy sessions;private final AuditService audit;private final ObjectMapper json;
    private final ReconciliationService finance;private final BitcoinObservationService observations;
    private final BitcoinDepositQualificationService qualification;private final BitcoinDepositReconciliationService nativeReconciliation;
    private final TransactionTemplate tx;
    public BitcoinChainRecoveryProofs(JdbcTemplate jdbc,BitcoinSettings settings,BitcoinDepositOperatorAccess access,UserRepository users,
        OperatorSessionPolicy sessions,AuditService audit,ObjectMapper json,ReconciliationService finance,BitcoinObservationService observations,
        BitcoinDepositQualificationService qualification,BitcoinDepositReconciliationService nativeReconciliation,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.settings=settings;this.access=access;this.users=users;this.sessions=sessions;this.audit=audit;this.json=json;
        this.finance=finance;this.observations=observations;this.qualification=qualification;this.nativeReconciliation=nativeReconciliation;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Report prepare(long actor,UUID sid,UUID receipt,UUID key){
        outside();if(key==null||key.equals(new UUID(0,0)))throw denied(HttpStatus.BAD_REQUEST);
        Claim started=tx.execute(t->begin(actor,sid,receipt,key));
        if(!started.created())return get(actor,sid,started.id());
        // 길어질 수 있는 저널 읽기 대사를 먼저 수행한다. 이후 새 노드 관찰/입금 대사로 최신 체인을 확인한다.
        // 외부 호출 동안 고객 잠금·DB 연결을 보유하지 않으며 기존 장수 HTTP 클라이언트를 사용한다.
        Evidence evidence=collect(started);
        // 완료/감사 저장 실패는 외부 검증 실패로 다시 분류하지 않는다. 시작만 남으면 승인 근거가 아니다.
        return tx.execute(t->finish(started,evidence));
    }
    private Claim begin(long actor,UUID sid,UUID receipt,UUID key){
        var authorized=access.require(actor,sid,receipt,settings,UserRole.OPERATOR,UserRole.APPROVER);
        if(actor==authorized.customer().getId())throw denied(HttpStatus.FORBIDDEN);
        var old=jdbc.queryForList("SELECT id,receipt_id FROM bitcoin_chain_recovery_probes WHERE actor_id=? AND request_key=?",actor,key);
        if(!old.isEmpty()){
            if(!receipt.equals(old.getFirst().get("receipt_id")))throw denied(HttpStatus.CONFLICT);
            return new Claim((UUID)old.getFirst().get("id"),receipt,actor,sid,authorized.customer().getId(),null,null,null,false);
        }
        var basis=basis(receipt);if(basis==null)throw denied(HttpStatus.CONFLICT);
        UUID id=UUID.randomUUID();String scope=(String)basis.get("scope_hash"),ledger=(String)basis.get("ledger_hash");
        String raw=json.writeValueAsString(new Payload(id,receipt,actor,scope,ledger,POLICY)),hash=BitcoinCreditBasis.digest(raw);
        jdbc.update("INSERT INTO bitcoin_chain_recovery_probes(id,receipt_id,user_id,actor_id,actor_person,actor_version,session_id,request_key,scope_hash,ledger_hash,payload,payload_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            id,receipt,authorized.customer().getId(),actor,authorized.actor().getOperatorPersonId(),authorized.actor().getVersion(),sid,key,scope,ledger,raw,hash);
        audit.record(actor,"BTC_CHAIN_RECOVERY_STARTED","bitcoin-chain-recovery:"+id,"SUCCESS",hash);
        String txid=jdbc.queryForObject("SELECT txid FROM bitcoin_deposit_receipts WHERE id=?",String.class,receipt);
        return new Claim(id,receipt,actor,sid,authorized.customer().getId(),txid,scope,ledger,true);
    }
    private Evidence collect(Claim started){
        UUID financial=null,nativeRun=null;
        try{
            var report=finance.run();financial=report.id();
            if(!"MATCHED".equals(report.status()))return new Evidence("FINANCIAL_RECONCILIATION_REQUIRED",financial,null,null,null);
            var observed=observations.refresh(started.txid());
            tx.executeWithoutResult(t->qualification.evaluate(observed.id()));
            if(observed.status()!=BitcoinDepositReader.Status.CONFIRMED||observed.confirmations()<6)return new Evidence("CHAIN_NOT_READY",financial,null,null,null);
            var nativeReport=nativeReconciliation.reconcile(started.receipt());nativeRun=nativeReport.id();
            if(nativeReport.status()!=BitcoinDepositReconciliationService.Status.MATCHED)return new Evidence("NATIVE_RECONCILIATION_REQUIRED",financial,nativeRun,null,null);
            // 응답으로 받은 상태뿐 아니라 실제 보존된 노드 원본의 앵커를 읽는다. 최종 SQL 제약도 같은 근거를 다시 결합한다.
            String raw=jdbc.queryForObject("SELECT payload FROM bitcoin_deposit_reconciliation_evidence WHERE run_id=? AND kind='NODE'",String.class,nativeRun);
            var metadata=json.readTree(raw).path("metadata");
            if(!metadata.path("blockHash").isString()||(!metadata.path("blockHeight").isIntegralNumber()||!metadata.path("blockHeight").canConvertToLong()))return new Evidence("NODE_SOURCE_UNAVAILABLE",financial,nativeRun,null,null);
            return new Evidence("VERIFIED_RECOVERY_BASIS",financial,nativeRun,metadata.path("blockHash").asString(),metadata.path("blockHeight").longValue());
        }catch(RuntimeException unavailable){return new Evidence("SOURCE_UNAVAILABLE",financial,nativeRun,null,null);}
    }
    private Report finish(Claim started,Evidence evidence){
        access.require(started.actor(),started.sid(),started.receipt(),settings,UserRole.OPERATOR,UserRole.APPROVER);
        var current=basis(started.receipt());String reason=evidence.reason();
        if(current==null||!started.scope().equals(current.get("scope_hash"))||!started.ledger().equals(current.get("ledger_hash")))reason="RISK_OR_FINANCIAL_STATE_CHANGED";
        Instant expires=null;
        if("VERIFIED_RECOVERY_BASIS".equals(reason)){
            Boolean healthy=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id
                 JOIN bitcoin_observation_heads h ON h.network=r.network AND h.txid=r.txid JOIN bitcoin_transaction_observations o ON o.id=h.observation_id AND o.wallet=a.wallet
                 JOIN bitcoin_observation_wallet_debits w ON w.observation_id=o.id AND NOT w.wallet_debit
                 JOIN bitcoin_deposit_chain_heads ch ON ch.receipt_id=r.id JOIN bitcoin_deposit_chain_decisions d ON d.id=ch.decision_id AND d.observation_id=o.id AND d.reason='CHAIN_READY'
                 WHERE r.id=? AND o.status='CONFIRMED' AND o.confirmations>=6 AND NOT o.coinbase AND o.block_hash=? AND o.block_height=?
                  AND o.read_started_at BETWEEN clock_timestamp()-interval '2 minutes' AND clock_timestamp())
                """,Boolean.class,started.receipt(),evidence.blockHash(),evidence.height());
            if(!Boolean.TRUE.equals(healthy))reason="CURRENT_CHAIN_CHANGED";
            else{
                var rows=jdbc.queryForList("""
                    SELECT least(n.snapshot_at+interval '2 minutes',f.completed_at+interval '2 minutes') expires
                     FROM bitcoin_current_deposit_reconciliation n CROSS JOIN reconciliation_runs f
                     WHERE n.id=? AND n.receipt_id=? AND n.status='MATCHED' AND f.id=? AND f.status='MATCHED'
                    """,evidence.nativeRun(),started.receipt(),evidence.financial());
                if(rows.size()!=1||rows.getFirst().get("expires")==null)reason="CURRENT_RECONCILIATION_CHANGED";
                else{
                    expires=((Timestamp)rows.getFirst().get("expires")).toInstant();
                    Instant databaseNow=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
                    if(!expires.isAfter(databaseNow)){reason="RECOVERY_PROOF_EXPIRED";expires=null;}
                }
            }
        }
        String status="VERIFIED_RECOVERY_BASIS".equals(reason)?"READY":"HELD";
        jdbc.update("INSERT INTO bitcoin_chain_recovery_results(probe_id,status,reason,financial_run,native_run,block_hash,block_height,expires_at) VALUES(?,?,?,?,?,?,?,?)",
            started.id(),status,reason,evidence.financial(),evidence.nativeRun(),evidence.blockHash(),evidence.height(),expires==null?null:Timestamp.from(expires));
        audit.record(started.actor(),"BTC_CHAIN_RECOVERY_FINISHED","bitcoin-chain-recovery:"+started.id(),"SUCCESS",status+":"+reason);
        return read(started.id());
    }
    public Report get(long actor,UUID sid,UUID id){
        outside();return tx.execute(t->{
            // 원본의 존재를 응답하기 전에 현재 인증을 확인한다. 알려진 원본은 고객/운영자를 같은 오름차순으로 다시 잠근다.
            var rows=jdbc.queryForList("SELECT receipt_id FROM bitcoin_chain_recovery_probes WHERE id=?",UUID.class,id);
            if(rows.isEmpty()){
                sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);
                throw denied(HttpStatus.NOT_FOUND);
            }
            access.require(actor,sid,rows.getFirst(),settings,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);
            var result=read(id);audit.record(actor,"BTC_CHAIN_RECOVERY_READ","bitcoin-chain-recovery:"+id,"SUCCESS","STATE:"+result.state());return result;
        });
    }
    // 원장 행 수·마지막 ID는 불변 원장의 추가를 탐지하고 잔액 벡터는 투영 변경을 탐지한다.
    // 지문 비교만으로 회계 일치를 증명하지 않으므로 실제 대사의 MATCHED 결과를 별도로 요구한다.
    private Map<String,Object> basis(UUID receipt){var rows=jdbc.queryForList("SELECT * FROM bitcoin_chain_recovery_basis(?)",receipt);return rows.isEmpty()?null:rows.getFirst();}
    private Report read(UUID id){
        var r=jdbc.queryForMap("SELECT p.*,r.status,r.reason,r.financial_run,r.native_run,r.block_hash,r.block_height,r.expires_at,r.expires_at>clock_timestamp() fresh FROM bitcoin_chain_recovery_probes p LEFT JOIN bitcoin_chain_recovery_results r ON r.probe_id=p.id WHERE p.id=?",id);
        String status=r.get("status")==null?"STARTED":(String)r.get("status");if("READY".equals(status)&&!Boolean.TRUE.equals(r.get("fresh")))status="EXPIRED";
        return new Report(id,(UUID)r.get("receipt_id"),status,(String)r.get("reason"),(String)r.get("scope_hash"),(String)r.get("ledger_hash"),(UUID)r.get("financial_run"),(UUID)r.get("native_run"),(String)r.get("block_hash"),r.get("block_height")==null?null:((Number)r.get("block_height")).longValue(),r.get("expires_at")==null?null:((Timestamp)r.get("expires_at")).toInstant());
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("복구 검증은 외부 트랜잭션 없이 시작하세요");}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 운영 인증·확정 입금·미종결 금융 업무·위험 범위를 확인하세요");}
}
