package dustin.cex.domains.settlement.trade.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementAdjustment;

/**
 * 거래 정산 보정 레코드 리포지토리
 * Trade Settlement Adjustment Repository
 */
@Repository
public interface TradeSettlementAdjustmentRepository extends JpaRepository<TradeSettlementAdjustment, Long> {
    
    /**
     * 정산 ID로 보정 레코드 목록 조회
     */
    List<TradeSettlementAdjustment> findBySettlementId(Long settlementId);
    
    /**
     * 정산일로 보정 레코드 목록 조회
     */
    List<TradeSettlementAdjustment> findBySettlementDate(LocalDate settlementDate);
    
    /**
     * 보정 유형으로 보정 레코드 목록 조회
     */
    List<TradeSettlementAdjustment> findByAdjustmentType(String adjustmentType);
    
    /**
     * 정산 ID와 보정 유형으로 조회
     */
    @Query("SELECT a FROM TradeSettlementAdjustment a WHERE a.settlement.id = :settlementId AND a.adjustmentType = :adjustmentType")
    List<TradeSettlementAdjustment> findBySettlementIdAndAdjustmentType(
            @Param("settlementId") Long settlementId,
            @Param("adjustmentType") String adjustmentType);
}
