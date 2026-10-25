package dustin.cex.domains.monitoring;

import dustin.cex.domains.bitcoin.BitcoinDepositOperatorAccess;
import dustin.cex.domains.bitcoin.BitcoinSettings;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.security.AuditService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 현재 운영 인증 아래 실제 수탁 원본을 다시 처리 대상으로 알린다. 원장·평가·임대·처리 순번을 되돌리지 않는다. */
@Service
public class DepositMonitoringRequest {
    public record Requested(UUID receiptId,long targetCursor,String status){}
    private final BitcoinDepositOperatorAccess access;
    private final BitcoinSettings settings;
    private final DepositMonitoringQueue queue;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public DepositMonitoringRequest(BitcoinDepositOperatorAccess access,BitcoinSettings settings,DepositMonitoringQueue queue,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){
        this.access=access;this.settings=settings;this.queue=queue;this.jdbc=jdbc;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Requested request(long actor,UUID sid,UUID receipt){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("탐지 요청은 독립 트랜잭션으로 시작하세요");
        try{return tx.execute(t->{
            var allowed=access.require(actor,sid,receipt,settings,UserRole.OPERATOR,UserRole.APPROVER);
            var original=jdbc.queryForList("SELECT operation_key FROM bitcoin_pending_deposits WHERE receipt_id=?",String.class,receipt);
            if(original.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"수탁 인식 원본이 먼저 필요합니다");
            queue.enqueue(allowed.customer().getId(),original.getFirst());
            var current=jdbc.queryForMap("""
                SELECT j.target_cursor,j.observed_cursor,a.policy_id FROM deposit_monitoring_jobs j
                 LEFT JOIN deposit_monitoring_heads h ON h.user_id=j.user_id LEFT JOIN deposit_monitoring_assessments a ON a.id=h.assessment_id
                 WHERE j.user_id=?
                """,allowed.customer().getId());
            long cursor=((Number)current.get("target_cursor")).longValue();boolean pending=cursor>((Number)current.get("observed_cursor")).longValue()||!SmallDepositMonitoringPolicy.POLICY.equals(current.get("policy_id"));
            audit.record(actor,"DEPOSIT_MONITORING_REQUESTED","bitcoin-deposit:"+receipt,"SUCCESS","CURSOR:"+cursor+":POLICY:"+SmallDepositMonitoringPolicy.POLICY);
            return new Requested(receipt,cursor,pending?"QUEUED":"ALREADY_CURRENT");
        });}catch(ResponseStatusException denied){audit.denied(actor,"DEPOSIT_MONITORING_REQUEST","restricted","POLICY_OR_SOURCE_"+denied.getStatusCode().value());throw denied;}
    }
}
