package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.order.repository.*;
import dustin.cex.domains.order.service.OrderCancellationService;
import dustin.cex.domains.security.AuditService;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** 현재 체인 위험 고객의 미종결 지정가만 영속 취소에 연결한다. 노드·엔진 HTTP와 금융 변경은 수행하지 않는다. */
@Service
@DependsOn("bitcoinCreditSchemaInitializer")
public class BitcoinChainRiskOrderCancellations {
    public static final String POLICY="BTC_CHAIN_RISK_CANCEL_LIMIT_ORDERS_V1";
    public record Plan(long orderId,long customerId,UUID receiptId,long observationId){}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OrderRepository orders;
    private final OrderCancellationRepository cancellations;private final OrderCancellationService output;private final AuditService audit;private final TransactionTemplate tx;
    public BitcoinChainRiskOrderCancellations(JdbcTemplate jdbc,UserRepository users,OrderRepository orders,OrderCancellationRepository cancellations,
        OrderCancellationService output,AuditService audit,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.users=users;this.orders=orders;this.cancellations=cancellations;this.output=output;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Optional<Plan> stageNext(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("위험 주문 정리는 외부 트랜잭션 없이 시작하세요");
        // 오래된 미해결 위험부터 처리한다. 새 정상 관찰/반복 위험 관찰 때문에 기존 위험의 우선순위를 계속 뒤로 밀지 않는다.
        var due=jdbc.queryForList("""
            SELECT o.id,o.user_id,r.receipt_id,r.observation_id FROM orders o JOIN order_submissions s ON s.order_id=o.id AND s.user_id=o.user_id
             CROSS JOIN LATERAL(SELECT cr.receipt_id,cr.observation_id FROM bitcoin_credit_chain_risks cr WHERE cr.user_id=o.user_id ORDER BY cr.observation_id,cr.receipt_id LIMIT 1) r
             WHERE s.order_side='limit' AND o.status NOT IN('filled','cancelled','rejected','expired')
              AND NOT EXISTS(SELECT 1 FROM order_cancellations q WHERE q.order_id=o.id)
             ORDER BY r.observation_id,o.id LIMIT 1
            """);
        if(due.isEmpty())return Optional.empty();var row=due.getFirst();
        var candidate=new Plan(((Number)row.get("id")).longValue(),((Number)row.get("user_id")).longValue(),(UUID)row.get("receipt_id"),((Number)row.get("observation_id")).longValue());
        return tx.execute(t->{
            users.lockById(candidate.customerId()).orElseThrow();var original=orders.findByUserIdAndIdForUpdate(candidate.customerId(),candidate.orderId()).orElseThrow();
            // 후보 조회는 권한이 아니다. 고객 잠금 뒤 현재 범위와 기존 요청을 다시 읽어 경합의 이전 관찰을 재사용하지 않는다.
            if(Set.of("filled","cancelled","rejected","expired").contains(original.getStatus())||cancellations.findForUpdate(candidate.orderId()).isPresent())return Optional.empty();
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_credit_chain_risks WHERE receipt_id=? AND observation_id=? AND user_id=?)",Boolean.class,candidate.receiptId(),candidate.observationId(),candidate.customerId())))return Optional.empty();
            String detail=jdbc.queryForObject("INSERT INTO bitcoin_chain_risk_order_cancellations(order_id,user_id,receipt_id,observation_id,policy) VALUES(?,?,?,?,?) RETURNING audit_detail",String.class,
                candidate.orderId(),candidate.customerId(),candidate.receiptId(),candidate.observationId(),POLICY);
            // 원본 연결→취소 예약→시스템 감사가 같은 커밋이다. 지연 외래키/감사 제약이 빠진 단계의 커밋을 거부한다.
            if(!output.stage(candidate.customerId(),candidate.orderId()))throw new IllegalStateException("검증한 위험 취소 원본의 예약 상태가 달라졌습니다");
            audit.record(null,"BTC_CHAIN_RISK_CANCEL_PLANNED","order:"+candidate.orderId(),"SUCCESS",detail);return Optional.of(candidate);
        });
    }
}
