package dustin.cex.domains.settlement.trade.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementAuditLog;

/**
 * 거래 정산 감사 로그 리포지토리
 * Trade Settlement Audit Log Repository
 */
@Repository
public interface TradeSettlementAuditLogRepository extends JpaRepository<TradeSettlementAuditLog, Long> {
    
    /**
     * 정산 ID로 감사 로그 목록 조회
     */
    List<TradeSettlementAuditLog> findBySettlementIdOrderByCreatedAtDesc(Long settlementId);
    
    /**
     * 정산일로 감사 로그 목록 조회
     */
    List<TradeSettlementAuditLog> findBySettlementDateOrderByCreatedAtDesc(LocalDate settlementDate);
    
    /**
     * 작업 유형으로 감사 로그 목록 조회
     */
    List<TradeSettlementAuditLog> findByActionTypeOrderByCreatedAtDesc(String actionType);
    
    /**
     * 작업 수행자로 감사 로그 목록 조회
     */
    List<TradeSettlementAuditLog> findByActionByOrderByCreatedAtDesc(String actionBy);
    
    /**
     * 날짜 범위로 감사 로그 목록 조회
     */
    @Query("SELECT l FROM TradeSettlementAuditLog l WHERE l.createdAt BETWEEN :startDate AND :endDate ORDER BY l.createdAt DESC")
    List<TradeSettlementAuditLog> findByCreatedAtBetween(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);
    
    /**
     * 정산 ID와 작업 유형으로 조회
     */
    @Query("SELECT l FROM TradeSettlementAuditLog l WHERE l.settlement.id = :settlementId AND l.actionType = :actionType ORDER BY l.createdAt DESC")
    List<TradeSettlementAuditLog> findBySettlementIdAndActionType(
            @Param("settlementId") Long settlementId,
            @Param("actionType") String actionType);
}
