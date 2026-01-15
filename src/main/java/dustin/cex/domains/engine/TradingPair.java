// =====================================================
// TradingPair - 거래쌍
// =====================================================
// 역할: 거래소에서 거래되는 자산 쌍을 나타냅니다.
// 
// 예시: SOL/USDT, USDC/USDT 등
// 
// 필드:
// - baseMint: 기준 자산 (예: "SOL", "USDC")
// - quoteMint: 기준 통화 (항상 "USDT")
// =====================================================

package dustin.cex.domains.engine;

import java.util.Objects;

/**
 * 거래쌍
 * Trading Pair
 * 
 * 거래소에서 거래되는 자산 쌍을 나타냅니다.
 * 예: SOL/USDT, USDC/USDT 등
 * 
 * @param baseMint 기준 자산 (예: "SOL", "USDC")
 * @param quoteMint 기준 통화 (항상 "USDT")
 * 
 * 예시:
 * <pre>
 * TradingPair pair = new TradingPair("SOL", "USDT");
 * // SOL/USDT 거래쌍을 나타냄
 * </pre>
 */
public final class TradingPair {
    /**
     * 기준 자산 (Base Asset)
     * 예: "SOL", "USDC", "RAY"
     */
    private final String baseMint;
    
    /**
     * 기준 통화 (Quote Currency)
     * 항상 "USDT"
     */
    private final String quoteMint;
    
    /**
     * 새 거래쌍 생성
     * 
     * @param baseMint 기준 자산
     * @param quoteMint 기준 통화
     */
    public TradingPair(String baseMint, String quoteMint) {
        this.baseMint = baseMint;
        this.quoteMint = quoteMint;
    }
    
    /**
     * 기준 자산
     */
    public String getBaseMint() {
        return baseMint;
    }
    
    /**
     * 기준 통화
     */
    public String getQuoteMint() {
        return quoteMint;
    }
    
    /**
     * 거래쌍 문자열 표현 (예: "SOL/USDT")
     * 
     * @return 거래쌍 문자열 (예: "SOL/USDT")
     */
    @Override
    public String toString() {
        return baseMint + "/" + quoteMint;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TradingPair that = (TradingPair) o;
        return Objects.equals(baseMint, that.baseMint) &&
               Objects.equals(quoteMint, that.quoteMint);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(baseMint, quoteMint);
    }
}
