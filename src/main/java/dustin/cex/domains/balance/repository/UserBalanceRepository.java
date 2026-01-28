package dustin.cex.domains.balance.repository;

import dustin.cex.domains.balance.model.entity.UserBalance;
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
 * 사용자 잔고 리포지토리
 * User Balance Repository
 * 
 * 역할:
 * - 사용자 잔고 CRUD 작업
 * - 비관적 락을 사용한 동시성 제어
 */
@Repository
public interface UserBalanceRepository extends JpaRepository<UserBalance, Long> {
    
    /**
     * 사용자 ID와 자산으로 잔고 조회 (비관적 락)
     * 
     * @param userId 사용자 ID
     * @param mintAddress 자산 종류
     * @return 잔고 (없으면 Optional.empty())
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM UserBalance b WHERE b.userId = :userId AND b.mintAddress = :mintAddress")
    Optional<UserBalance> findByUserIdAndMintAddressForUpdate(
        @Param("userId") Long userId,
        @Param("mintAddress") String mintAddress
    );
    
    /**
     * 사용자 ID와 자산으로 잔고 조회 (일반 조회)
     * 
     * @param userId 사용자 ID
     * @param mintAddress 자산 종류
     * @return 잔고 (없으면 Optional.empty())
     */
    Optional<UserBalance> findByUserIdAndMintAddress(
        @Param("userId") Long userId,
        @Param("mintAddress") String mintAddress
    );
    
    /**
     * 사용자의 모든 잔고 조회
     * 
     * @param userId 사용자 ID
     * @return 잔고 목록
     */
    List<UserBalance> findByUserId(Long userId);
}
