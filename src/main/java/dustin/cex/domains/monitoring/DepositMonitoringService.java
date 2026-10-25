package dustin.cex.domains.monitoring;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.AuditService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

/** 수탁 입력을 고객별로 순서 있게 읽어 한 가지 탐지 근거를 보존한다. 금융 잔액·고객 제한·신고 결론은 바꾸지 않는다. */
@Service
@DependsOn("depositMonitoringSchemaInitializer")
public class DepositMonitoringService {
    public record Assessment(UUID id,long userId,long inputCursor,Instant referenceAt,SmallDepositMonitoringPolicy.Result result,String sourceHash){}
    private record Claim(long user,UUID lease){}
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public DepositMonitoringService(JdbcTemplate jdbc,UserRepository users,ObjectMapper json,AuditService audit,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.users=users;this.json=json;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Optional<Assessment> processNext(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("모니터링 작업은 독립 트랜잭션으로 시작하세요");
        // 임대 확보에서는 고객을 잠그지 않는다. 실제 처리에서는 다른 수탁 업무와 같은 고객→작업 순서로 잠근다.
        Claim claim=tx.execute(t->claim());if(claim==null)return Optional.empty();
        return tx.execute(t->process(claim));
    }
    private Claim claim(){
        var rows=jdbc.queryForList("""
            SELECT j.user_id FROM deposit_monitoring_jobs j
             WHERE j.target_cursor>j.observed_cursor
              AND(j.lease_until IS NULL OR j.lease_until<=clock_timestamp())
             ORDER BY j.target_cursor,j.user_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
            """,Long.class);
        if(rows.isEmpty())return null;long user=rows.getFirst();UUID lease=UUID.randomUUID();
        jdbc.update("UPDATE deposit_monitoring_jobs SET lease_id=?,lease_until=clock_timestamp()+interval '2 minutes',updated_at=clock_timestamp() WHERE user_id=?",lease,user);
        audit.record(null,"DEPOSIT_MONITORING_CLAIMED","monitoring-customer:"+user,"SUCCESS","LEASE:"+lease);
        return new Claim(user,lease);
    }
    private Optional<Assessment> process(Claim claim){
        users.lockById(claim.user()).orElseThrow(()->new IllegalStateException("모니터링 고객 원본이 없습니다"));
        var jobs=jdbc.queryForList("SELECT lease_id,observed_cursor FROM deposit_monitoring_jobs WHERE user_id=? FOR UPDATE",claim.user());
        if(jobs.size()!=1||!claim.lease().equals(jobs.getFirst().get("lease_id")))return Optional.empty();
        // 최신 위치로 건너뛰면 밀린 입력의 과거 시간 창이 사라진다. 미처리 원장 입력을 하나씩 확정한다.
        long observed=((Number)jobs.getFirst().get("observed_cursor")).longValue();
        var anchor=jdbc.queryForMap("SELECT input_cursor,recognized_at FROM deposit_monitoring_inputs WHERE user_id=? AND input_cursor>? ORDER BY input_cursor LIMIT 1",claim.user(),observed);
        long cursor=((Number)anchor.get("input_cursor")).longValue();Instant reference=((Timestamp)anchor.get("recognized_at")).toInstant();
        var inputs=DepositMonitoringInputs.read(jdbc,claim.user(),cursor,reference);
        String document=json.writeValueAsString(inputs),hash=sha(document);var result=SmallDepositMonitoringPolicy.assess(reference,inputs);
        var old=jdbc.queryForList("SELECT id,source_hash FROM deposit_monitoring_assessments WHERE user_id=? AND policy_id=? AND input_cursor=?",claim.user(),SmallDepositMonitoringPolicy.POLICY,cursor);
        UUID id;
        if(old.isEmpty()){
            id=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO deposit_monitoring_assessments(id,user_id,input_cursor,policy_id,reference_at,outcome,reason,small_transfers,small_total_krw,unknown_transfers,source_count,source_document,source_hash)
                 VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,claim.user(),cursor,SmallDepositMonitoringPolicy.POLICY,Timestamp.from(reference),result.outcome(),result.reason(),result.smallTransfers(),result.smallTotalKrw(),result.unknownTransfers(),inputs.size(),document,hash);
            jdbc.update("INSERT INTO deposit_monitoring_heads(user_id,assessment_id) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET assessment_id=excluded.assessment_id",claim.user(),id);
            audit.record(null,"DEPOSIT_MONITORING_ASSESSED","monitoring-assessment:"+id,"SUCCESS","POLICY:"+SmallDepositMonitoringPolicy.POLICY+":OUTCOME:"+result.outcome()+":REASON:"+result.reason()+":SOURCE:"+hash);
        }else{
            // 같은 원장 위치는 같은 근거여야 한다. 중복 실행으로 새 결론을 덮거나 다른 원본을 정상화하지 않는다.
            if(!hash.equals(old.getFirst().get("source_hash")))throw new IllegalStateException("모니터링 원본이 과거 확정 근거와 다릅니다");
            id=(UUID)old.getFirst().get("id");
            var heads=jdbc.queryForList("SELECT assessment_id FROM deposit_monitoring_heads WHERE user_id=?",UUID.class,claim.user());
            if(heads.size()!=1||!id.equals(heads.getFirst()))throw new IllegalStateException("모니터링 현재 위치와 확정 근거가 다릅니다");
        }
        jdbc.update("UPDATE deposit_monitoring_jobs SET observed_cursor=?,target_cursor=greatest(target_cursor,?),lease_id=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE user_id=? AND lease_id=?",cursor,cursor,claim.user(),claim.lease());
        return Optional.of(new Assessment(id,claim.user(),cursor,reference,result,hash));
    }
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("모니터링 지문을 만들 수 없습니다");}}
}
