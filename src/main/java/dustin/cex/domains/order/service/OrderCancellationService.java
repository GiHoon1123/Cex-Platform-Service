package dustin.cex.domains.order.service;

import dustin.cex.domains.order.model.entity.*;
import dustin.cex.domains.order.repository.*;
import dustin.cex.domains.security.*;
import dustin.cex.shared.http.EngineHttpClient;
import java.time.Instant;
import java.util.Set;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.*;

/** 취소 요청·시도 청구·외부 호출·응답 기록을 분리한다. 금융 종료는 기존 Kafka 수신만 확정한다. */
@Service
@DependsOn("orderCancellationSchemaInitializer")
public class OrderCancellationService {
    private static final Logger log=LoggerFactory.getLogger(OrderCancellationService.class);
    private static final Set<String> TERMINAL=Set.of("filled","cancelled","rejected","expired");
    private final OrderRepository orders;private final OrderSubmissionRepository submissions;private final OrderCancellationRepository cancellations;
    private final EngineHttpClient engine;private final TradingAccessPolicy access;private final AuditService audit;private final TransactionTemplate tx;
    private record Claim(long orderId,long userId,String pair,long attempt){}
    public OrderCancellationService(OrderRepository orders,OrderSubmissionRepository submissions,OrderCancellationRepository cancellations,
        EngineHttpClient engine,TradingAccessPolicy access,AuditService audit,PlatformTransactionManager manager){
        this.orders=orders;this.submissions=submissions;this.cancellations=cancellations;this.engine=engine;this.access=access;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 제한된 고객도 기존 주문의 취소를 요청할 수 있다. 신규 금융 허가 검사를 취소에 잘못 적용하지 않는다. */
    @Transactional(propagation=Propagation.NEVER)
    public void request(long userId,long orderId){
        boolean pending=Boolean.TRUE.equals(tx.execute(t->{
            var owned=orders.findByUserIdAndIdForUpdate(userId,orderId).orElseThrow(()->new IllegalArgumentException("본인 주문을 확인하세요"));
            if("cancelled".equals(owned.getStatus()))return false;
            if(TERMINAL.contains(owned.getStatus()))throw new IllegalArgumentException("종료된 주문은 취소할 수 없습니다");
            ensureQueued(owned);
            access.recordCancellationIntent(userId,orderId);return true;
        }));
        if(pending){
            // 여기까지 오면 요청과 최초 감사는 이미 영속 확정됐다. 이후 즉시 전송/시도 기록의 실패는
            // 그 접수를 되돌리지 않는다. 응답은 계속 접수202이며 확인되지 않은 시도는 저장된 일정으로 이어간다.
            try{attempt(orderId,true);}catch(RuntimeException pendingDelivery){log.error("취소 요청 접수는 보존됐으나 즉시 재확인 기록을 완료하지 못했습니다: orderId={}",orderId);}
        }
    }
    /**
     * 검증한 업무가 같은 TX에 취소 원본만 예약하는 내부 경계다. 외부 호출은 하지 않는다.
     * 권한 근거/필수 감사는 호출 업무가 함께 남겨야 하며 DB 제약이 빠진 근거의 커밋을 거부한다.
     */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean stage(long userId,long orderId){
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("취소 예약은 검증 업무의 트랜잭션 안에서 실행하세요");
        var owned=orders.findByUserIdAndIdForUpdate(userId,orderId).orElseThrow(()->new IllegalArgumentException("취소할 원본 주문을 확인하세요"));
        if(TERMINAL.contains(owned.getStatus()))return false;
        return ensureQueued(owned);
    }
    private boolean ensureQueued(Order owned){
        if(cancellations.findForUpdate(owned.getId()).isPresent())return false;
        var original=submissions.findByOrderId(owned.getId()).orElseThrow(()->new IllegalStateException("취소할 불변 접수 원본이 없습니다"));
        // 최초 요청은 즉시 재확인할 수 있다. 실제 전송 청구가 성공했을 때만30초 임대가 시작된다.
        cancellations.saveAndFlush(new OrderCancellation(owned,original,Instant.now()));return true;
    }
    private void attempt(long id,boolean immediate){
        Claim claim=tx.execute(t->{
            // 고객 요청과 같은 주문→취소 행 순서다. Kafka 정산이 먼저 종료했다면 네트워크를 호출하지 않는다.
            var order=orders.findByIdForUpdate(id).orElseThrow();if(TERMINAL.contains(order.getStatus()))return null;
            var row=cancellations.findForUpdate(id).orElseThrow();if(!row.claim(Instant.now(),immediate))return null;
            // JPA 변경을 먼저 전송해 DB가 정한 실제 변경 시각 뒤에 필수 감사를 기록한다.
            // 두 쓰기는 여전히 같은 TX여서 감사 누락/실패면 시도 청구도 롤백된다.
            cancellations.flush();
            audit.record(null,"ORDER_CANCEL_ATTEMPT_STARTED","order:"+id,"SUCCESS","ATTEMPT:"+row.getAttempts());
            return new Claim(id,row.getUserId(),row.getBaseMint()+"/"+row.getQuoteMint(),row.getAttempts());
        });
        if(claim==null)return;
        // 이 지점에는 DB 트랜잭션/행 잠금이 없다. 이미 접수한 취소의 통신 오류를 금융 거절로 바꾸지 않는다.
        boolean acknowledged=false;
        try{acknowledged=engine.cancelOrder(claim.orderId(),claim.userId(),claim.pair());}
        catch(RuntimeException unknown){log.warn("취소 응답 재확인 대기: orderId={}, attempt={}",id,claim.attempt());}
        boolean confirmed=acknowledged;
        tx.executeWithoutResult(t->{
            var row=cancellations.findForUpdate(id).orElseThrow();row.finish(claim.attempt(),confirmed,Instant.now());cancellations.flush();
            audit.record(null,"ORDER_CANCEL_ATTEMPT_FINISHED","order:"+id,"SUCCESS","ATTEMPT:"+claim.attempt()+":"+(confirmed?"ACKNOWLEDGED":"UNCERTAIN"));
        });
    }
    /** 전용 워커는 한 건의 TX를 끝낸 뒤 종료 여부를 확인한다. 네트워크 동안 행 잠금을 보유하지 않는다. */
    @Transactional(propagation=Propagation.NEVER)
    public boolean retryNext(){
        var due=cancellations.findDue(Instant.now(),PageRequest.of(0,1));if(due.isEmpty())return false;
        attempt(due.getFirst(),false);return true;
    }
}
