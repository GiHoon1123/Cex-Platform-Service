package dustin.cex.domains.fee.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import dustin.cex.domains.fee.model.entity.FeeConfig;
import dustin.cex.domains.fee.repository.FeeConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * 플랫폼은 주문에 적용할 정책을 선택하고, 엔진은 고정된 적용률로 실제 차감액을 계산한다.
 * 빈 정책을 임의 기본률로 숨기지 않는다. 정책이 없는 시장의 신규 접수만 거부한다.
 */
@Service
@RequiredArgsConstructor
public class FeeConfigService {
    private final FeeConfigRepository repository;
    private volatile Map<String, FeeConfig> policies = Map.of();

    @PostConstruct
    public void loadFeeConfigs() {
        var next = new HashMap<String, FeeConfig>();
        for (var config : repository.findByIsActiveTrue()) {
            requireRate(config.getFeeRate());
            if (!"both".equals(config.getFeeType())) {
                throw new IllegalArgumentException("현재 지원하는 수수료 정책은 양측 동일률입니다");
            }
            // 외부 엔티티의 변경이 공개한 캐시를 바꾸지 않게 사본을 저장한다.
            var copy = FeeConfig.builder().baseMint(config.getBaseMint()).quoteMint(config.getQuoteMint())
                    .feeRate(config.getFeeRate()).feeType("both").isActive(true).build();
            if (next.putIfAbsent(key(copy.getBaseMint(), copy.getQuoteMint()), copy) != null) {
                throw new IllegalArgumentException("동일 범위의 활성 수수료 정책이 중복되었습니다");
            }
        }
        // 검증 실패는 기존 공개 상태를 부분 변경하지 않는다. 코어의 공유 상태와 무관한 플랫폼 캐시다.
        policies = Map.copyOf(next);
    }

    public FeeConfig getFeeConfig(String base, String quote) {
        for (String scope : new String[]{key(base, quote), key(base, null), key(null, quote), key(null, null)}) {
            var policy = policies.get(scope);
            if (policy != null) return FeeConfig.builder().baseMint(policy.getBaseMint()).quoteMint(policy.getQuoteMint())
                    .feeRate(policy.getFeeRate()).feeType("both").isActive(true).build();
        }
        throw new IllegalStateException("거래쌍에 적용할 수수료 정책이 없습니다");
    }

    /** 현행 디비 적용률 정밀도와 동일하게 검증한다. 할인·음수 리베이트는 아직 지원하지 않는다. */
    public static BigDecimal requireRate(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) >= 0
                || value.stripTrailingZeros().scale() > 6) {
            throw new IllegalArgumentException("수수료율은 소수 여섯 자리 이내의 0 이상 1 미만이어야 합니다");
        }
        return value.stripTrailingZeros();
    }

    private static String key(String base, String quote) {
        return (base == null ? "" : base) + ":" + (quote == null ? "" : quote);
    }

    public void refreshFeeConfigs() { loadFeeConfigs(); }
}
