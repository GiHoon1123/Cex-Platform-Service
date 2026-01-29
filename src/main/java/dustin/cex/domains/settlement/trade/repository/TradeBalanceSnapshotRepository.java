package dustin.cex.domains.settlement.trade.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.settlement.trade.model.entity.TradeBalanceSnapshot;

/**
 * 거래 정산용 잔고 스냅샷 Repository
 * Trade Balance Snapshot Repository
 * 
 * 하위 도메인 분리:
 * ================
 * 이 Repository는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산에만 필요한 잔고 스냅샷입니다.
 */
@Repository
public interface TradeBalanceSnapshotRepository extends JpaRepository<TradeBalanceSnapshot, Long> {
    
    /**
     * 사용자별 특정 날짜의 스냅샷 조회
     */
    Optional<TradeBalanceSnapshot> findByUserIdAndSnapshotDateAndMintAddress(
        Long userId, LocalDate snapshotDate, String mintAddress
    );
    
    /**
     * 특정 날짜의 모든 스냅샷 조회
     */
    List<TradeBalanceSnapshot> findBySnapshotDate(LocalDate snapshotDate);
    
    /**
     * 사용자별 특정 날짜 범위의 스냅샷 조회
     */
    @Query("SELECT s FROM TradeBalanceSnapshot s WHERE s.userId = :userId AND s.snapshotDate BETWEEN :startDate AND :endDate ORDER BY s.snapshotDate DESC")
    List<TradeBalanceSnapshot> findByUserIdAndSnapshotDateBetween(
        @Param("userId") Long userId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );
    
    /**
     * 특정 날짜의 스냅샷 존재 여부 확인
     */
    boolean existsBySnapshotDate(LocalDate snapshotDate);
}
