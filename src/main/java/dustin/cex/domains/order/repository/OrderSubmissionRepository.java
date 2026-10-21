package dustin.cex.domains.order.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import dustin.cex.domains.order.model.entity.OrderSubmission;

/** 유일 제약은 조회 후 삽입 사이의 경쟁도 막는다. find만으로 중복을 방지하지 않는다. */
public interface OrderSubmissionRepository extends JpaRepository<OrderSubmission, Long> {
    Optional<OrderSubmission> findByUserIdAndRequestId(Long userId, UUID requestId);
}
