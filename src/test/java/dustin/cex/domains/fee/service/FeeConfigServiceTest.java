package dustin.cex.domains.fee.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import dustin.cex.domains.fee.model.entity.FeeConfig;
import dustin.cex.domains.fee.repository.FeeConfigRepository;

/** 정책 누락·모호함을 기본값으로 숨기지 않고, 검증 실패가 공개 상태를 바꾸지 않는지 확인한다. */
class FeeConfigServiceTest {
    FeeConfig config(String base, String quote, String rate) {
        return FeeConfig.builder().baseMint(base).quoteMint(quote).feeRate(new BigDecimal(rate)).feeType("both").isActive(true).build();
    }
    @Test void missingPolicyRejectsInsteadOfInventingDefault() {
        var repository = mock(FeeConfigRepository.class);
        when(repository.findByIsActiveTrue()).thenReturn(List.of());
        var service = new FeeConfigService(repository);
        service.loadFeeConfigs();
        assertThrows(IllegalStateException.class, () -> service.getFeeConfig("BTC", "USDT"));
    }
    @Test void specificityAndAtomicRefreshPreserveValidatedPolicy() {
        var repository = mock(FeeConfigRepository.class);
        var fallback = config(null, null, "0.002");
        var exact = config("BTC", "USDT", "0.001");
        when(repository.findByIsActiveTrue()).thenReturn(List.of(fallback, exact));
        var service = new FeeConfigService(repository);
        service.loadFeeConfigs();
        assertEquals(new BigDecimal("0.001"), service.getFeeConfig("BTC", "USDT").getFeeRate());
        assertEquals(new BigDecimal("0.002"), service.getFeeConfig("ETH", "USDT").getFeeRate());
        // 반환 객체나 저장소 엔티티를 바꿔도 이미 공개한 사본은 변하지 않는다.
        exact.setFeeRate(BigDecimal.ONE);
        service.getFeeConfig("BTC", "USDT").setFeeRate(BigDecimal.ONE);
        assertEquals(new BigDecimal("0.001"), service.getFeeConfig("BTC", "USDT").getFeeRate());
        when(repository.findByIsActiveTrue()).thenReturn(List.of(config("BTC", "USDT", "0.003"), config("BTC", "USDT", "0.004")));
        assertThrows(IllegalArgumentException.class, service::loadFeeConfigs);
        assertEquals(new BigDecimal("0.001"), service.getFeeConfig("BTC", "USDT").getFeeRate());
    }
    @Test void invalidRatesAndUnsupportedTypesAreRejected() {
        for (String rate : List.of("-0.001", "1", "0.0000001")) {
            assertThrows(IllegalArgumentException.class, () -> FeeConfigService.requireRate(new BigDecimal(rate)));
        }
        var repository = mock(FeeConfigRepository.class);
        var policy = config(null, null, "0.001"); policy.setFeeType("maker");
        when(repository.findByIsActiveTrue()).thenReturn(List.of(policy));
        assertThrows(IllegalArgumentException.class, () -> new FeeConfigService(repository).loadFeeConfigs());
    }
}
