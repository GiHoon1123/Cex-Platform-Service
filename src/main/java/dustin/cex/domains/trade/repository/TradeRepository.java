package dustin.cex.domains.trade.repository;

import dustin.cex.domains.trade.model.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 체결 내역 리포지토리
 * Trade Repository
 */
@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {
    // 추가 쿼리 메서드 필요 시 여기에 정의
}
