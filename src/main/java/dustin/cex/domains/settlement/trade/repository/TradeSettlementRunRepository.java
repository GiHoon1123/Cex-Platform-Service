package dustin.cex.domains.settlement.trade.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import dustin.cex.domains.settlement.trade.model.entity.TradeSettlementRun;

/**
 * 거래 정산 실행 런 리포지토리
 * Trade Settlement Run Repository
 *
 * 테이블: trade_settlement_runs (거래 정산 도메인)
 */
@Repository
public interface TradeSettlementRunRepository extends JpaRepository<TradeSettlementRun, Long> {

    List<TradeSettlementRun> findBySettlementDateOrderByRunAtDesc(LocalDate settlementDate, Pageable pageable);

    default List<TradeSettlementRun> findLatestBySettlementDate(LocalDate settlementDate, int limit) {
        return findBySettlementDateOrderByRunAtDesc(settlementDate, PageRequest.of(0, limit));
    }
}
