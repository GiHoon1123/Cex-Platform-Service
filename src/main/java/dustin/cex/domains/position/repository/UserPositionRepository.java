package dustin.cex.domains.position.repository;

import dustin.cex.domains.position.model.entity.UserPosition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 사용자 포지션 리포지토리
 * User Position Repository
 * 
 * 역할:
 * - 사용자 포지션 CRUD 작업
 * - 비관적 락을 사용한 동시성 제어
 */
@Repository
public interface UserPositionRepository extends JpaRepository<UserPosition, Long> {
    
    /**
     * 사용자 ID와 거래쌍으로 포지션 조회 (비관적 락)
     * 
     * @param userId 사용자 ID
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @return 포지션 (없으면 Optional.empty())
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM UserPosition p WHERE p.userId = :userId AND p.baseMint = :baseMint AND p.quoteMint = :quoteMint")
    Optional<UserPosition> findByUserIdAndBaseMintAndQuoteMintForUpdate(
        @Param("userId") Long userId,
        @Param("baseMint") String baseMint,
        @Param("quoteMint") String quoteMint
    );
    
    /**
     * 사용자 ID와 거래쌍으로 포지션 조회 (일반 조회)
     * 
     * @param userId 사용자 ID
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @return 포지션 (없으면 Optional.empty())
     */
    Optional<UserPosition> findByUserIdAndBaseMintAndQuoteMint(
        @Param("userId") Long userId,
        @Param("baseMint") String baseMint,
        @Param("quoteMint") String quoteMint
    );
    
    /**
     * 사용자의 모든 포지션 조회
     * 
     * @param userId 사용자 ID
     * @return 포지션 목록
     */
    List<UserPosition> findByUserId(Long userId);
    
    /**
     * 포지션 수량이 0이 아닌 포지션만 조회
     * 
     * @param userId 사용자 ID
     * @return 포지션 목록
     */
    @Query("SELECT p FROM UserPosition p WHERE p.userId = :userId AND p.positionAmount != 0")
    List<UserPosition> findActivePositionsByUserId(@Param("userId") Long userId);
}
