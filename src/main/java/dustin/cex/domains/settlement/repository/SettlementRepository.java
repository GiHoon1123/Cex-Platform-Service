package dustin.cex.domains.settlement.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.settlement.model.entity.Settlement;

/**
 * 정산 내역 Repository
 * Settlement Repository
 * 
 * 역할:
 * - settlements 테이블에 대한 데이터베이스 접근 제공
 * - 일별/월별 정산 데이터 조회 및 집계 쿼리 제공
 */
@Repository
public interface SettlementRepository extends JpaRepository<Settlement, Long> {
    
    /**
     * 특정 날짜의 일별 정산 조회
     * 
     * @param settlementDate 정산일
     * @param baseMint 기준 자산 (NULL이면 전체)
     * @param quoteMint 기준 통화 (기본값: USDT)
     * @return 일별 정산 데이터
     */
    Optional<Settlement> findBySettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
            LocalDate settlementDate,
            String settlementType,
            String baseMint,
            String quoteMint
    );
    
    /**
     * 특정 날짜 범위의 정산 데이터 조회
     * 
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @param settlementType 정산 유형 ('daily' 또는 'monthly')
     * @return 해당 기간의 정산 데이터 목록
     */
    @Query("SELECT s FROM Settlement s WHERE s.settlementDate BETWEEN :startDate AND :endDate AND s.settlementType = :settlementType ORDER BY s.settlementDate ASC")
    List<Settlement> findBySettlementDateBetweenAndSettlementType(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("settlementType") String settlementType
    );
    
    /**
     * 특정 날짜의 정산 데이터 존재 여부 확인
     * 
     * @param settlementDate 정산일
     * @param settlementType 정산 유형
     * @return 존재 여부
     */
    boolean existsBySettlementDateAndSettlementType(LocalDate settlementDate, String settlementType);
}
