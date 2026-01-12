// =====================================================
// MatchResult - 매칭 결과
// =====================================================
// 역할: 두 주문이 매칭되어 체결될 때의 정보를 담는 구조체입니다.
// 매칭 알고리즘(Matcher)이 생성하고, 체결 실행기(Executor)가 처리합니다.
// 
// 필드:
// - buyOrderId: 매수 주문 ID
// - sellOrderId: 매도 주문 ID
// - buyerId: 매수자 ID
// - sellerId: 매도자 ID
// - price: 체결 가격 (기존 주문의 가격 사용, Price Priority)
// - amount: 체결 수량 (baseMint 기준)
// - baseMint: 기준 자산
// - quoteMint: 기준 통화
// =====================================================

package dustin.cex.domains.cex.engine;

import java.math.BigDecimal;

/**
 * 매칭 결과
 * Match Result
 * 
 * 두 주문이 매칭되어 체결될 때의 정보를 담는 구조체입니다.
 * 매칭 알고리즘(Matcher)이 생성하고, 체결 실행기(Executor)가 처리합니다.
 * 
 * 예시:
 * <pre>
 * // 매수자 A와 매도자 B의 주문이 매칭됨
 * MatchResult result = new MatchResult();
 * result.setBuyOrderId(1);   // 매수자 A의 주문
 * result.setSellOrderId(2);  // 매도자 B의 주문
 * result.setBuyerId(100);
 * result.setSellerId(200);
 * result.setPrice(new BigDecimal("100.00"));  // 100 USDT에 체결
 * result.setAmount(new BigDecimal("1.00"));   // 1 SOL 체결
 * result.setBaseMint("SOL");
 * result.setQuoteMint("USDT");
 * // 이 결과는 Executor가 받아서 실제 체결 처리(잔고 업데이트 등)
 * </pre>
 */
public final class MatchResult {
    /**
     * 매수 주문 ID
     * Buy order ID
     */
    private long buyOrderId;
    
    /**
     * 매도 주문 ID
     * Sell order ID
     */
    private long sellOrderId;
    
    /**
     * 매수자 ID
     * Buyer user ID
     */
    private long buyerId;
    
    /**
     * 매도자 ID
     * Seller user ID
     */
    private long sellerId;
    
    /**
     * 체결 가격 (USDT 기준)
     * Execution price (in USDT)
     * 
     * Price Priority:
     * - 기존에 오더북에 있던 주문의 가격 사용
     * - 새로 들어온 주문이 아닌, 먼저 있던 주문의 가격으로 체결
     */
    private BigDecimal price;
    
    /**
     * 체결 수량 (baseMint 기준)
     * Execution amount (in baseMint)
     * 
     * 부분 체결 가능:
     * - min(buyOrder.remaining, sellOrder.remaining)
     */
    private BigDecimal amount;
    
    /**
     * 기준 자산
     * Base asset
     */
    private String baseMint;
    
    /**
     * 기준 통화
     * Quote currency
     */
    private String quoteMint;
    
    // Getters and Setters
    
    public long getBuyOrderId() {
        return buyOrderId;
    }
    
    public void setBuyOrderId(long buyOrderId) {
        this.buyOrderId = buyOrderId;
    }
    
    public long getSellOrderId() {
        return sellOrderId;
    }
    
    public void setSellOrderId(long sellOrderId) {
        this.sellOrderId = sellOrderId;
    }
    
    public long getBuyerId() {
        return buyerId;
    }
    
    public void setBuyerId(long buyerId) {
        this.buyerId = buyerId;
    }
    
    public long getSellerId() {
        return sellerId;
    }
    
    public void setSellerId(long sellerId) {
        this.sellerId = sellerId;
    }
    
    public BigDecimal getPrice() {
        return price;
    }
    
    public void setPrice(BigDecimal price) {
        this.price = price;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
    
    public String getBaseMint() {
        return baseMint;
    }
    
    public void setBaseMint(String baseMint) {
        this.baseMint = baseMint;
    }
    
    public String getQuoteMint() {
        return quoteMint;
    }
    
    public void setQuoteMint(String quoteMint) {
        this.quoteMint = quoteMint;
    }
    
    /**
     * 체결 총액 계산 (price * amount)
     * 
     * @return 체결 총액 (USDT 기준)
     * 
     * 예시:
     * <pre>
     * MatchResult result = new MatchResult();
     * result.setPrice(new BigDecimal("100.00"));  // 100 USDT
     * result.setAmount(new BigDecimal("2.00"));   // 2 SOL
     * BigDecimal total = result.getTotalValue(); // 200 USDT
     * </pre>
     */
    public BigDecimal getTotalValue() {
        if (price == null || amount == null) {
            return BigDecimal.ZERO;
        }
        return price.multiply(amount);
    }
}

