package dustin.cex.domains.fee.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import dustin.cex.domains.settlement.trade.model.entity.TradeFee;
import dustin.cex.domains.settlement.trade.repository.*;
import dustin.cex.domains.settlement.trade.service.TradeSettlementService;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.trade.repository.TradeRepository;

/** 환율이 없는 서로 다른 자산의 수익이 숫자 하나로 합쳐지지 않는지 검증한다. */
class FeeRevenueTest {
    @Test void revenueKeepsEachAssetAndExactDayBoundary() {
        var repository=mock(TradeFeeRepository.class);
        var day=LocalDate.of(2026,10,22);
        when(repository.findForRevenueWindow(day.atStartOfDay(),day.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(
                        TradeFee.builder().feeMint("BTC").feeAmount(new BigDecimal("0.001")).build(),
                        TradeFee.builder().feeMint("BTC").feeAmount(new BigDecimal("0.002")).build(),
                        TradeFee.builder().feeMint("USDT").feeAmount(new BigDecimal("0.09")).build()));
        var service=new TradeSettlementService(mock(TradeRepository.class),repository,mock(TradeSettlementRepository.class),
                mock(TradeSettlementItemRepository.class),mock(TradeSettlementAdjustmentRepository.class),
                mock(TradeSettlementAuditLogRepository.class),mock(TradeSettlementFailureRepository.class),
                mock(TradeUserSettlementRepository.class),mock(UserRepository.class));
        var revenue=service.calculateFeeRevenueByAsset(day,day);
        assertEquals(new BigDecimal("0.003"),revenue.get("BTC"));
        assertEquals(new BigDecimal("0.09"),revenue.get("USDT"));
        assertEquals(2,revenue.size());
    }
}
