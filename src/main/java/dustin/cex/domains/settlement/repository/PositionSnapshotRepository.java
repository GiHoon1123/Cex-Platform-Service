package dustin.cex.domains.settlement.repository;

import dustin.cex.domains.settlement.model.entity.PositionSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 포지션 스냅샷 Repository
 * Position Snapshot Repository
 */
@Repository
public interface PositionSnapshotRepository extends JpaRepository<PositionSnapshot, Long> {
    
    /**
     * 사용자별 특정 날짜의 스냅샷 조회
     */
    Optional<PositionSnapshot> findByUserIdAndSnapshotDateAndBaseMintAndQuoteMint(
        Long userId, LocalDate snapshotDate, String baseMint, String quoteMint
    );
    
    /**
     * 특정 날짜의 모든 스냅샷 조회
     */
    List<PositionSnapshot> findBySnapshotDate(LocalDate snapshotDate);
    
    /**
     * 사용자별 특정 날짜 범위의 스냅샷 조회
     */
    @Query("SELECT s FROM PositionSnapshot s WHERE s.userId = :userId AND s.snapshotDate BETWEEN :startDate AND :endDate ORDER BY s.snapshotDate DESC")
    List<PositionSnapshot> findByUserIdAndSnapshotDateBetween(
        @Param("userId") Long userId,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
    );
    
    /**
     * 특정 날짜의 스냅샷 존재 여부 확인
     */
    boolean existsBySnapshotDate(LocalDate snapshotDate);
}
