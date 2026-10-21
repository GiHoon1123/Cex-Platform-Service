package dustin.cex.domains.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import dustin.cex.domains.order.model.entity.OrderDispatch;

/** 후보 조회와 짧은 행 잠금으로 청구를 분리한다. 네트워크 호출은 이 트랜잭션 밖에서 한다. */
public interface OrderDispatchRepository extends JpaRepository<OrderDispatch,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM OrderDispatch d WHERE d.orderId=:id")
    Optional<OrderDispatch> findForUpdate(@Param("id") Long id);
    @Query("SELECT d.orderId FROM OrderDispatch d WHERE d.confirmedAt IS NULL AND d.nextAttemptAt <= :now ORDER BY d.orderId")
    List<Long> findDue(@Param("now") Instant now,Pageable page);
}
