// =====================================================
// OrderEntry - 엔진 내부 주문 엔트리
// =====================================================
// 역할: 엔진의 메모리 오더북에서 사용하는 주문 구조체입니다.
// DB 모델(Order)과 별도로 관리되며, 메모리 기반 처리에 최적화되어 있습니다.
// 
// 차이점:
// - DB Order: 영구 저장, 모든 필드 포함
// - OrderEntry: 메모리 전용, 매칭에 필요한 필드만 포함
// 
// 필드:
// - id: 주문 ID (DB와 동일)
// - userId: 주문한 사용자 ID
// - orderType: 매수("buy") 또는 매도("sell")
// - orderSide: 지정가("limit") 또는 시장가("market")
// - baseMint: 기준 자산
// - quoteMint: 기준 통화
// - price: 주문 가격 (시장가는 null)
// - amount: 주문 수량 (baseMint 기준)
// - filledAmount: 체결된 수량
// - remainingAmount: 남은 수량 (amount - filledAmount)
// - createdAt: 주문 생성 시간 (Time Priority에 사용)
// =====================================================

package dustin.cex.domains.cex.engine;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 엔진 내부 주문 엔트리
 * Engine Internal Order Entry
 * 
 * 엔진의 메모리 오더북에서 사용하는 주문 구조체입니다.
 * DB 모델(Order)과 별도로 관리되며, 메모리 기반 처리에 최적화되어 있습니다.
 * 
 * 차이점:
 * - DB Order: 영구 저장, 모든 필드 포함
 * - OrderEntry: 메모리 전용, 매칭에 필요한 필드만 포함
 * 
 * 예시:
 * <pre>
 * // 지정가 매수 주문
 * OrderEntry order = new OrderEntry();
 * order.setId(1);
 * order.setUserId(100);
 * order.setOrderType("buy");
 * order.setOrderSide("limit");
 * order.setBaseMint("SOL");
 * order.setQuoteMint("USDT");
 * order.setPrice(new BigDecimal("100.00")); // 100 USDT
 * order.setAmount(new BigDecimal("10.00")); // 10 SOL
 * order.setFilledAmount(BigDecimal.ZERO);
 * order.setRemainingAmount(new BigDecimal("10.00"));
 * order.setCreatedAt(Instant.now());
 * </pre>
 */
public final class OrderEntry {
    /**
     * 주문 고유 ID
     * Unique order ID (same as DB)
     */
    private long id;
    
    /**
     * 주문한 사용자 ID
     * User ID who placed the order
     */
    private long userId;
    
    /**
     * 주문 타입: "buy" (매수) 또는 "sell" (매도)
     * Order type: "buy" or "sell"
     */
    private String orderType;
    
    /**
     * 주문 방식: "limit" (지정가) 또는 "market" (시장가)
     * Order side: "limit" or "market"
     */
    private String orderSide;
    
    /**
     * 기준 자산 (예: "SOL")
     * Base asset
     */
    private String baseMint;
    
    /**
     * 기준 통화 (예: "USDT")
     * Quote currency
     */
    private String quoteMint;
    
    /**
     * 주문 가격 (지정가만, 시장가는 null)
     * Order price (limit orders only, null for market orders)
     */
    private BigDecimal price;
    
    /**
     * 주문 수량 (baseMint 기준)
     * Order amount (in baseMint)
     * 
     * Note: 시장가 매수 주문의 경우 (quoteAmount 기반), 
     *       이 값은 quoteAmount / price로 계산됨
     */
    private BigDecimal amount;
    
    /**
     * 금액 기반 주문 (시장가 매수만)
     * Quote amount (for market buy orders only)
     * 
     * Example: "1000 USDT worth of SOL"
     * 예: "1000 USDT어치 SOL 사기"
     * 
     * null이면 수량 기반, null이 아니면 시장가 매수 금액 기반
     */
    private BigDecimal quoteAmount;
    
    /**
     * 체결된 수량 (baseMint 기준)
     * Filled amount (in baseMint)
     */
    private BigDecimal filledAmount;
    
    /**
     * 남은 수량 (amount - filledAmount)
     * Remaining amount
     */
    private BigDecimal remainingAmount;
    
    /**
     * 남은 금액 (quoteAmount 기반 주문의 경우)
     * Remaining quote amount (for quoteAmount-based orders)
     * 
     * 시장가 매수 주문(quoteAmount 기반)의 경우, 남은 USDT 금액을 추적
     */
    private BigDecimal remainingQuoteAmount;
    
    /**
     * 주문 생성 시간 (Time Priority에 사용)
     * Order creation time (used for Time Priority)
     */
    private Instant createdAt;
    
    // Getters and Setters
    
    public long getId() {
        return id;
    }
    
    public void setId(long id) {
        this.id = id;
    }
    
    public long getUserId() {
        return userId;
    }
    
    public void setUserId(long userId) {
        this.userId = userId;
    }
    
    public String getOrderType() {
        return orderType;
    }
    
    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }
    
    public String getOrderSide() {
        return orderSide;
    }
    
    public void setOrderSide(String orderSide) {
        this.orderSide = orderSide;
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
    
    public BigDecimal getQuoteAmount() {
        return quoteAmount;
    }
    
    public void setQuoteAmount(BigDecimal quoteAmount) {
        this.quoteAmount = quoteAmount;
    }
    
    public BigDecimal getFilledAmount() {
        return filledAmount;
    }
    
    public void setFilledAmount(BigDecimal filledAmount) {
        this.filledAmount = filledAmount;
    }
    
    public BigDecimal getRemainingAmount() {
        return remainingAmount;
    }
    
    public void setRemainingAmount(BigDecimal remainingAmount) {
        this.remainingAmount = remainingAmount;
    }
    
    public BigDecimal getRemainingQuoteAmount() {
        return remainingQuoteAmount;
    }
    
    public void setRemainingQuoteAmount(BigDecimal remainingQuoteAmount) {
        this.remainingQuoteAmount = remainingQuoteAmount;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    /**
     * 주문이 완전 체결되었는지 확인
     * 
     * @return true - 주문이 완전히 체결됨 (remainingAmount == 0)
     *         false - 아직 미체결 수량 존재
     */
    public boolean isFullyFilled() {
        return remainingAmount != null && remainingAmount.compareTo(BigDecimal.ZERO) == 0;
    }
    
    /**
     * 주문이 매수 주문인지 확인
     * 
     * @return true - 매수 주문
     */
    public boolean isBuy() {
        return "buy".equals(orderType);
    }
    
    /**
     * 주문이 매도 주문인지 확인
     * 
     * @return true - 매도 주문
     */
    public boolean isSell() {
        return "sell".equals(orderType);
    }
    
    /**
     * 주문이 시장가 주문인지 확인
     * 
     * @return true - 시장가 주문
     */
    public boolean isMarket() {
        return "market".equals(orderSide);
    }
    
    /**
     * 주문이 지정가 주문인지 확인
     * 
     * @return true - 지정가 주문
     */
    public boolean isLimit() {
        return "limit".equals(orderSide);
    }
}

