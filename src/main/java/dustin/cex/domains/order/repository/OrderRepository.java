package dustin.cex.domains.order.repository;

import java.util.List;
import java.util.Optional;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.order.model.entity.Order;
import jakarta.persistence.LockModeType;

/**
 * 주문 리포지토리
 * Order Repository
 * 
 * 역할:
 * - 주문 엔티티의 데이터베이스 접근을 담당
 * - Spring Data JPA를 사용하여 기본 CRUD 작업 제공
 * - 사용자별 주문 조회, 상태별 필터링 등 커스텀 쿼리 제공
 * 
 * 사용 예시:
 * - 주문 생성: save(order)
 * - 주문 조회: findById(orderId)
 * - 사용자 주문 목록: findByUserId(userId, pageable)
 * - 상태별 필터링: findByUserIdAndStatus(userId, status, pageable)
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    
    /**
     * 사용자 ID로 주문 목록 조회
     * Find orders by user ID
     * 
     * @param userId 사용자 ID
     * @param pageable 페이지네이션 정보 (페이지 번호, 크기, 정렬)
     * @return 주문 목록 (페이지네이션 적용)
     */
    Page<Order> findByUserId(Long userId, Pageable pageable);
    
    /**
     * 사용자 ID와 주문 상태로 주문 목록 조회
     * Find orders by user ID and status
     * 
     * @param userId 사용자 ID
     * @param status 주문 상태 ('pending', 'partial', 'filled', 'cancelled')
     * @param pageable 페이지네이션 정보
     * @return 주문 목록 (페이지네이션 적용)
     */
    Page<Order> findByUserIdAndStatus(Long userId, String status, Pageable pageable);
    
    /**
     * 사용자 ID와 주문 ID로 주문 조회
     * Find order by user ID and order ID
     * 
     * 사용자가 자신의 주문만 조회할 수 있도록 보안 검증에 사용
     * 
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @return 주문 정보 (없으면 Optional.empty())
     */
    Optional<Order> findByUserIdAndId(Long userId, Long orderId);
    
    /** 봇의 미체결 주문 누적을 제한하고 가장 오래된 주문부터 회수한다. */
    long countByUserIdAndStatusIn(Long userId, java.util.Collection<String> statuses);
    Optional<Order> findFirstByUserIdAndStatusInOrderByCreatedAtAsc(Long userId, java.util.Collection<String> statuses);

    /**
     * 주문 ID로 주문 조회 (비관적 락)
     * Find order by ID with pessimistic lock (FOR UPDATE)
     * 
     * 체결 이벤트 처리 시 동시성 제어를 위해 사용
     * SELECT FOR UPDATE로 락을 걸어 다른 트랜잭션의 동시 수정을 방지
     * 
     * @param orderId 주문 ID
     * @return 주문 정보 (없으면 Optional.empty())
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);
    
    /**
     * 사용자 ID와 주문 ID로 주문 조회 (비관적 락)
     * Find order by user ID and order ID with pessimistic lock (FOR UPDATE)
     * 
     * 주문 취소 시 동시성 제어를 위해 사용
     * SELECT FOR UPDATE로 락을 걸어 다른 트랜잭션의 동시 수정을 방지
     * 
     * 사용 사례:
     * - 주문 취소 요청 시 Kafka Consumer가 동시에 체결 이벤트를 처리하는 경우 방지
     * - 주문 상태가 변경되는 동안 취소 요청이 들어오는 경우 방지
     * 
     * @param userId 사용자 ID
     * @param orderId 주문 ID
     * @return 주문 정보 (없으면 Optional.empty())
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.userId = :userId AND o.id = :orderId")
    Optional<Order> findByUserIdAndIdForUpdate(
        @Param("userId") Long userId,
        @Param("orderId") Long orderId
    );
}
