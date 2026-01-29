package dustin.cex.domains.trade.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.trade.model.entity.Trade;

/**
 * 체결 내역 리포지토리
 * Trade Repository
 */
@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {
    
    /**
     * 거래쌍별 체결 내역 조회 (최신순)
     * Find trades by trading pair ordered by created date descending
     * 
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @param pageable 페이지네이션 정보
     * @return 체결 내역 목록
     */
    List<Trade> findByBaseMintAndQuoteMintOrderByCreatedAtDesc(
            String baseMint, String quoteMint, Pageable pageable);
    
    /**
     * 사용자별 체결 내역 조회 (최신순)
     * Find trades by user ID ordered by created date descending
     * 
     * 매수자 또는 매도자로 참여한 모든 체결 내역 조회
     * 
     * @param userId 사용자 ID
     * @param pageable 페이지네이션 정보
     * @return 체결 내역 목록
     */
    @Query("SELECT t FROM Trade t WHERE t.buyerId = :userId OR t.sellerId = :userId ORDER BY t.createdAt DESC")
    List<Trade> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") Long userId, Pageable pageable);
    
    /**
     * 사용자별 특정 자산 체결 내역 조회 (최신순)
     * Find trades by user ID and base mint ordered by created date descending
     * 
     * @param userId 사용자 ID
     * @param baseMint 기준 자산
     * @param pageable 페이지네이션 정보
     * @return 체결 내역 목록
     */
    @Query("SELECT t FROM Trade t WHERE (t.buyerId = :userId OR t.sellerId = :userId) AND t.baseMint = :baseMint ORDER BY t.createdAt DESC")
    List<Trade> findByUserIdAndBaseMintOrderByCreatedAtDesc(
            @Param("userId") Long userId, @Param("baseMint") String baseMint, Pageable pageable);
    
    /**
     * 날짜 범위별 체결 내역 조회 (정산 검증용)
     * Find trades by date range for settlement validation
     * 
     * @param startDateTime 시작 날짜/시간
     * @param endDateTime 종료 날짜/시간
     * @return 해당 기간의 모든 체결 내역
     */
    @Query("SELECT t FROM Trade t WHERE t.createdAt BETWEEN :startDateTime AND :endDateTime ORDER BY t.createdAt ASC")
    List<Trade> findByCreatedAtBetween(
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime
    );
}
