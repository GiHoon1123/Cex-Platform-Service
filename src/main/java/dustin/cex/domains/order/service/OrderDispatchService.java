package dustin.cex.domains.order.service;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import dustin.cex.domains.order.model.OrderCommand;
import dustin.cex.domains.order.repository.*;
import dustin.cex.shared.http.EngineHttpClient;
import lombok.extern.slf4j.Slf4j;

/**
 * 원본 명령을 같은 주문 식별자로 전달한다. 재시도는 금융 보상이 아니며 예약을 해제하지 않는다.
 * 시도 청구·엔진 호출·결과 기록을 분리해 디비 트랜잭션 안에서 네트워크를 기다리지 않는다.
 */
@Slf4j
@Service
public class OrderDispatchService {
    private final OrderDispatchRepository dispatches;
    private final OrderSubmissionRepository submissions;
    private final EngineHttpClient engine;
    private final TransactionTemplate transaction;
    private record Claim(Long orderId,Long userId,OrderCommand command,long attempt) {}
    public OrderDispatchService(OrderDispatchRepository dispatches,OrderSubmissionRepository submissions,
            EngineHttpClient engine,PlatformTransactionManager manager) {
        this.dispatches=dispatches; this.submissions=submissions; this.engine=engine;
        this.transaction=new TransactionTemplate(manager);
    }
    @Transactional(propagation=Propagation.NEVER)
    public boolean submitImmediately(Long id) { return submit(id,true); }
    private boolean submit(Long id,boolean immediate) {
        Claim claim=transaction.execute(status->{
            var row=dispatches.findForUpdate(id).orElseThrow();
            if(!row.claim(Instant.now(),immediate)) return null;
            var original=submissions.findByOrderId(id).orElseThrow();
            return new Claim(id,original.getUserId(),original.command(),row.getAttempts());
        });
        if(claim==null) return dispatches.findById(id).orElseThrow().getConfirmedAt()!=null;
        boolean acknowledged=false;
        RuntimeException transportFailure=null;
        try {
            var c=claim.command();
            acknowledged=engine.submitOrder(id,claim.userId(),c.orderType(),c.orderSide(),c.baseMint(),c.quoteMint(),
                    text(c.price()),text(c.amount()),text(c.quoteAmount()));
        } catch(RuntimeException failure) {
            transportFailure=failure;
            // HTTP 오류·시간 초과는 미실행의 증거가 아니다. 원본 예약을 보존하고 다음 시도를 기록한다.
            log.warn("엔진 제출 재확인 대기: orderId={}, attempt={}",id,claim.attempt(),failure);
        }
        boolean result=acknowledged;
        boolean confirmed=Boolean.TRUE.equals(transaction.execute(status->{
            var row=dispatches.findForUpdate(id).orElseThrow();
            row.finish(claim.attempt(),result,Instant.now());
            return row.getConfirmedAt()!=null;
        }));
        // 응답 경로에는 최초 원인을 보존한다. 재시도 일정은 예외를 던지기 전에 이미 커밋했다.
        if(immediate && !confirmed && transportFailure!=null) throw transportFailure;
        return confirmed;
    }
    /** 한 번에 최대 20개만 처리한다. 지수 지연은 저장되며 프로세스 재시작에도 남는다. */
    @Transactional(propagation=Propagation.NEVER)
    public void retryDue() {
        for(Long id:dispatches.findDue(Instant.now(),PageRequest.of(0,20))) {
            try { submit(id,false); }
            catch(RuntimeException failure) {
                log.error("엔진 전송 기록 처리 실패: orderId={}",id,failure);
                // 저장소 오류를 건너뛰며 대량 전송하지 않는다. 다음 주기에서 원인을 다시 확인한다.
                break;
            }
        }
    }
    private static String text(java.math.BigDecimal value) { return value==null ? null : value.toPlainString(); }
}
