package dustin.cex.domains.settlement.trade.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.settlement.trade.model.entity.TradeUserSettlement;

/**
 * 사용자별 거래 정산 내역 Repository
 * Trade User Settlement Repository
 * 
 * 역할:
 * - user_settlements 테이블에 대한 데이터베이스 접근 제공
 * - 사용자별 거래 정산 데이터 조회 쿼리 제공
 * 
 * 하위 도메인 분리:
 * ================
 * 이 Repository는 settlement.trade 하위 도메인에 속합니다.
 * 거래 정산만을 담당하며, 향후 입출금/이벤트/쿠폰 정산은 별도 하위 도메인에서 처리됩니다.
 */
@Repository
public interface TradeUserSettlementRepository extends JpaRepository<TradeUserSettlement, Long> {
    
    /**
     * 특정 사용자의 일별 정산 데이터 조회
     * 
     * @param userId 사용자 ID
     * @param settlementDate 정산일
     * @param settlementType 정산 유형 ('daily' 또는 'monthly')
     * @param baseMint 기준 자산 (NULL이면 전체)
     * @param quoteMint 기준 통화 (기본값: USDT)
     * @return 사용자별 정산 데이터
     */
    Optional<TradeUserSettlement> findByUserIdAndSettlementDateAndSettlementTypeAndBaseMintAndQuoteMint(
            Long userId,
            LocalDate settlementDate,
            String settlementType,
            String baseMint,
            String quoteMint
    );
    
    /**
     * 특정 사용자의 정산 데이터 조회 (날짜 범위 지정)
     * 
     * @param userId 사용자 ID
     * @param startDate 시작 날짜
     * @param endDate 종료 날짜
     * @param settlementType 정산 유형
     * @return 해당 기간의 사용자별 정산 데이터 목록
     */
    @Query("SELECT us FROM TradeUserSettlement us WHERE us.user.id = :userId AND us.settlementDate BETWEEN :startDate AND :endDate AND us.settlementType = :settlementType ORDER BY us.settlementDate ASC")
    List<TradeUserSettlement> findByUserIdAndSettlementDateBetweenAndSettlementType(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("settlementType") String settlementType
    );
    
    /**
     * 특정 날짜의 모든 사용자별 정산 데이터 조회
     * 
     * @param settlementDate 정산일
     * @param settlementType 정산 유형
     * @return 해당 날짜의 모든 사용자별 정산 데이터 목록
     */
    @Query("SELECT us FROM TradeUserSettlement us WHERE us.settlementDate = :settlementDate AND us.settlementType = :settlementType ORDER BY us.totalVolume DESC")
    List<TradeUserSettlement> findBySettlementDateAndSettlementType(
            @Param("settlementDate") LocalDate settlementDate,
            @Param("settlementType") String settlementType
    );
}
