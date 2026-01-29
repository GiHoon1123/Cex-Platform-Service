package dustin.cex.domains.settlement.trade.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementFailure;

/**
 * 거래 정산 실패 기록 리포지토리
 * Trade Settlement Failure Repository
 *
 * 테이블: trade_settlement_failures (거래 정산 도메인)
 */
@Repository
public interface TradeSettlementFailureRepository extends JpaRepository<TradeSettlementFailure, Long> {

    List<TradeSettlementFailure> findBySettlementDateOrderByCreatedAtDesc(LocalDate settlementDate);

    List<TradeSettlementFailure> findBySettlementDateAndStepOrderByCreatedAtDesc(LocalDate settlementDate, Integer step);
}
