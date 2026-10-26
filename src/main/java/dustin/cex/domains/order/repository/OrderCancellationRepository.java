package dustin.cex.domains.order.repository;

import dustin.cex.domains.order.model.entity.OrderCancellation;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/** 일정만 선택하고 시도 청구는 실제 주문/취소 행 잠금 아래 다시 확인한다. 종료 주문을 취소 대기로 되살리지 않는다. */
public interface OrderCancellationRepository extends JpaRepository<OrderCancellation,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM OrderCancellation c WHERE c.orderId=:id")
    Optional<OrderCancellation> findForUpdate(@Param("id") Long id);
    @Query("SELECT c.orderId FROM OrderCancellation c JOIN c.order o WHERE c.acknowledgedAt IS NULL AND c.nextAttemptAt<=:now AND o.status NOT IN('filled','cancelled','rejected','expired') ORDER BY c.nextAttemptAt,c.orderId")
    List<Long> findDue(@Param("now") Instant now,Pageable page);
}
