package dustin.cex.domains.fee.service;

import dustin.cex.domains.fee.model.entity.FeeConfig;
import dustin.cex.domains.fee.repository.FeeConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 수수료 설정 서비스
 * Fee Config Service
 * 
 * 역할:
 * - 서버 시작 시 모든 활성 수수료 설정을 메모리에 로드
 * - 거래쌍별 수수료율 조회 (메모리에서 빠르게 조회)
 * 
 * 성능:
 * - DB 조회 없이 메모리에서 O(1) 조회
 * - 서버 시작 시 한 번만 로드
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeeConfigService {
    
    private final FeeConfigRepository feeConfigRepository;
    
    /**
     * 수수료 설정 캐시 (메모리)
     * Key: "baseMint:quoteMint" 또는 "baseMint:" 또는 ":quoteMint" 또는 "default"
     */
    private final Map<String, FeeConfig> feeConfigCache = new ConcurrentHashMap<>();
    
    /**
     * 기본 수수료 설정 (모든 거래쌍에 적용)
     */
    private FeeConfig defaultFeeConfig;
    
    /**
     * 서버 시작 시 수수료 설정 로드
     * Load fee configs on server startup
     */
    @PostConstruct
    public void loadFeeConfigs() {
        log.info("[FeeConfigService] 수수료 설정 로드 시작");
        
        List<FeeConfig> activeConfigs = feeConfigRepository.findByIsActiveTrue();
        log.info("[FeeConfigService] 활성 수수료 설정 개수: {}", activeConfigs.size());
        
        // 캐시 초기화
        feeConfigCache.clear();
        
        for (FeeConfig config : activeConfigs) {
            String key = buildCacheKey(config.getBaseMint(), config.getQuoteMint());
            feeConfigCache.put(key, config);
            
            // 기본 설정 저장 (baseMint와 quoteMint가 모두 NULL인 경우)
            if (config.getBaseMint() == null && config.getQuoteMint() == null) {
                defaultFeeConfig = config;
                log.info("[FeeConfigService] 기본 수수료 설정: feeRate={}, feeType={}", 
                        config.getFeeRate(), config.getFeeType());
            } else {
                log.debug("[FeeConfigService] 수수료 설정 로드: key={}, feeRate={}, feeType={}", 
                        key, config.getFeeRate(), config.getFeeType());
            }
        }
        
        if (defaultFeeConfig == null) {
            log.warn("[FeeConfigService] 기본 수수료 설정이 없습니다. 기본값 사용: 0.0001 (0.01%)");
            // 기본값 설정
            defaultFeeConfig = FeeConfig.builder()
                    .feeRate(new BigDecimal("0.0001"))
                    .feeType("both")
                    .build();
        }
        
        log.info("[FeeConfigService] 수수료 설정 로드 완료: 총 {}개", feeConfigCache.size());
    }
    
    /**
     * 거래쌍별 수수료 설정 조회
     * Get fee config for trading pair
     * 
     * 우선순위:
     * 1. base_mint와 quote_mint가 정확히 일치하는 설정
     * 2. base_mint만 일치하는 설정
     * 3. quote_mint만 일치하는 설정
     * 4. 기본 설정 (모두 NULL)
     * 
     * @param baseMint 기준 자산 (예: "SOL")
     * @param quoteMint 기준 통화 (예: "USDT")
     * @return 수수료 설정
     */
    public FeeConfig getFeeConfig(String baseMint, String quoteMint) {
        // 1. 정확히 일치하는 설정 조회
        String exactKey = buildCacheKey(baseMint, quoteMint);
        FeeConfig config = feeConfigCache.get(exactKey);
        if (config != null) {
            return config;
        }
        
        // 2. baseMint만 일치하는 설정 조회
        String baseKey = buildCacheKey(baseMint, null);
        config = feeConfigCache.get(baseKey);
        if (config != null) {
            return config;
        }
        
        // 3. quoteMint만 일치하는 설정 조회
        String quoteKey = buildCacheKey(null, quoteMint);
        config = feeConfigCache.get(quoteKey);
        if (config != null) {
            return config;
        }
        
        // 4. 기본 설정 반환
        return defaultFeeConfig;
    }
    
    /**
     * 수수료 계산
     * Calculate fee amount
     * 
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     * @param tradeAmount 거래 금액 (quote currency 기준)
     * @return 수수료 금액
     */
    public BigDecimal calculateFee(String baseMint, String quoteMint, BigDecimal tradeAmount) {
        FeeConfig config = getFeeConfig(baseMint, quoteMint);
        return tradeAmount.multiply(config.getFeeRate());
    }
    
    /**
     * 캐시 키 생성
     * Build cache key
     */
    private String buildCacheKey(String baseMint, String quoteMint) {
        if (baseMint == null && quoteMint == null) {
            return "default";
        }
        if (baseMint == null) {
            return ":" + quoteMint;
        }
        if (quoteMint == null) {
            return baseMint + ":";
        }
        return baseMint + ":" + quoteMint;
    }
    
    /**
     * 수수료 설정 새로고침 (런타임에 수수료 변경 시 사용)
     * Refresh fee configs (for runtime updates)
     */
    public void refreshFeeConfigs() {
        log.info("[FeeConfigService] 수수료 설정 새로고침");
        loadFeeConfigs();
    }
}
