package dustin.cex.domains.settlement.trade.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementItem;

/**
 * 거래 단위 정산 레코드 리포지토리
 * Trade Settlement Item Repository
 */
@Repository
public interface TradeSettlementItemRepository extends JpaRepository<TradeSettlementItem, Long> {
    
    /**
     * 정산 ID로 정산 아이템 목록 조회
     */
    List<TradeSettlementItem> findBySettlementId(Long settlementId);
    
    /**
     * 거래 ID로 정산 아이템 조회
     */
    List<TradeSettlementItem> findByTradeId(Long tradeId);
    
    /**
     * 정산일로 정산 아이템 목록 조회
     */
    List<TradeSettlementItem> findBySettlementDate(LocalDate settlementDate);
    
    /**
     * 정산 ID와 거래 ID로 조회 (중복 체크용)
     */
    @Query("SELECT i FROM TradeSettlementItem i WHERE i.settlement.id = :settlementId AND i.tradeId = :tradeId")
    java.util.Optional<TradeSettlementItem> findBySettlementIdAndTradeId(
            @Param("settlementId") Long settlementId, 
            @Param("tradeId") Long tradeId);
}
