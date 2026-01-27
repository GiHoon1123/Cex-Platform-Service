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
    
    /**
     * 특정 거래쌍의 활성 주문 조회 (오더북용)
     * Find active orders for trading pair (for orderbook)
     * 
     * 오더북 조회 시 사용
     * 상태가 'pending' 또는 'partial'인 주문만 조회
     * 
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @return 활성 주문 목록
     */
    @Query("SELECT o FROM Order o WHERE o.baseMint = :baseMint AND o.quoteMint = :quoteMint " +
           "AND o.status IN ('pending', 'partial') ORDER BY o.price DESC")
    List<Order> findActiveOrdersByTradingPair(
        @Param("baseMint") String baseMint,
        @Param("quoteMint") String quoteMint
    );
    
    /**
     * 특정 거래쌍의 매수 주문 조회 (오더북용)
     * Find buy orders for trading pair (for orderbook)
     * 
     * 오더북의 매수 호가창 조회 시 사용
     * 가격 내림차순 정렬 (높은 가격부터)
     * 
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @param pageable 페이지네이션 정보 (limit 포함)
     * @return 매수 주문 목록 (페이지네이션 적용)
     */
    @Query("SELECT o FROM Order o WHERE o.baseMint = :baseMint AND o.quoteMint = :quoteMint " +
           "AND o.orderType = 'buy' AND o.status IN ('pending', 'partial') " +
           "ORDER BY o.price DESC")
    List<Order> findBuyOrdersByTradingPair(
        @Param("baseMint") String baseMint,
        @Param("quoteMint") String quoteMint,
        Pageable pageable
    );
    
    /**
     * 특정 거래쌍의 매도 주문 조회 (오더북용)
     * Find sell orders for trading pair (for orderbook)
     * 
     * 오더북의 매도 호가창 조회 시 사용
     * 가격 오름차순 정렬 (낮은 가격부터)
     * 
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @param pageable 페이지네이션 정보 (limit 포함)
     * @return 매도 주문 목록 (페이지네이션 적용)
     */
    @Query("SELECT o FROM Order o WHERE o.baseMint = :baseMint AND o.quoteMint = :quoteMint " +
           "AND o.orderType = 'sell' AND o.status IN ('pending', 'partial') " +
           "ORDER BY o.price ASC")
    List<Order> findSellOrdersByTradingPair(
        @Param("baseMint") String baseMint,
        @Param("quoteMint") String quoteMint,
        Pageable pageable
    );
    
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
}
