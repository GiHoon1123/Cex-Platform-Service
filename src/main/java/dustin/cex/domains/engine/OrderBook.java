// =====================================================
// OrderBook - 인메모리 호가창
// =====================================================
// 역할: 매수/매도 주문을 가격별로 관리하는 자료구조
// 
// 핵심 설계:
// 1. TreeMap으로 가격별 정렬 (O(log n))
// 2. ArrayDeque로 같은 가격 내 Time Priority (FIFO)
// 3. 매수/매도 분리하여 best bid/ask 빠른 조회
//
// Price-Time Priority:
// - 먼저 가격으로 매칭 (높은 매수 vs 낮은 매도)
// - 같은 가격이면 시간 순서 (먼저 온 주문 우선)
//
// 자료구조:
// 1. TreeMap<BigDecimal, ArrayDeque<OrderEntry>>
//    - TreeMap: 가격별 정렬 (BTreeMap과 유사한 성능)
//      * Key: BigDecimal (가격)
//      * Value: ArrayDeque<OrderEntry> (같은 가격의 주문들)
//      * 장점: O(log n) 조회, 자동 정렬 (가격 순서 유지)
//      * 최선가 조회: firstKey() / lastKey() → O(log n) (실제로는 트리 순회 필요)
//      * 단점:
//        - Rust BTreeMap보다 약간 느림 (Red-Black Tree vs B-Tree)
//        - 참조 기반 (Entry 객체가 힙에 분산, 캐시 미스 가능)
//        - BigDecimal 비교 비용 (객체 비교, Rust Decimal은 더 효율적)
//    
// 2. ArrayDeque<OrderEntry>
//    - 같은 가격 레벨의 주문들을 FIFO 순서로 관리
//    * front() / pollFirst(): 가장 오래된 주문 (O(1))
//    * addLast() / offerLast(): 새 주문 추가 (O(1))
//    * 순회: O(n) where n=해당 가격의 주문 수
//    * 장점: Rust VecDeque와 유사한 인터페이스, O(1) 접근
//    * 단점/한계:
//      - 참조 배열만 연속 (실제 OrderEntry 객체는 힙에 분산)
//      - Pointer chasing 발생 (참조를 따라 객체 접근)
//      - 캐시 미스 가능성 높음 (객체가 멀리 떨어져 있음)
//      - Rust VecDeque: 구조체가 배열에 직접 저장 (더 캐시 친화적)
//
// 메모리 레이아웃 (Java):
// - TreeMap: 참조 배열 (연속), 실제 Entry 객체는 힙에 분산
// - ArrayDeque: 참조 배열 (연속), 실제 OrderEntry 객체는 힙에 분산
// - 캐시 친화성: Rust보다 낮음 (pointer chasing 발생)
//
// 작동 방식:
// 1. 주문 추가: 가격 레벨을 찾아 해당 가격의 큐 맨 뒤에 추가
// 2. 주문 조회: TreeMap의 firstKey()/lastKey()로 최선가 조회
// 3. 주문 매칭: Matcher가 최선가부터 순회하며 매칭 (FIFO)
// 4. 주문 제거: 가격 레벨에서 주문을 찾아 제거 (큐가 비면 레벨 제거)
//
// 성능:
// - 주문 추가: O(log n + 1) where n=가격 레벨 수
// - 최선가 조회: O(log n) (TreeMap 트리 순회)
// - 주문 제거: O(log n + m) where m=해당 가격의 주문 수
// - 주문 매칭: O(m) where m=매칭 가능한 주문 수
// =====================================================

package dustin.cex.domains.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 완전한 호가창 (매수 + 매도)
 * 
 * 구조:
 * buyOrders: { 100.5 -> [매수1], 100.0 -> [매수2] }  (높은 가격 우선)
 * sellOrders: { 101.0 -> [매도1], 101.5 -> [매도2] }  (낮은 가격 우선)
 * Spread = 101.0 - 100.5 = 0.5 USDT
 */
public class OrderBook {
    /**
     * 거래 쌍 (예: SOL/USDT)
     */
    private final TradingPair tradingPair;
    
    /**
     * 매수 호가 (가격 내림차순)
     */
    private final OrderBookSide buyOrders;
    
    /**
     * 매도 호가 (가격 오름차순)
     */
    private final OrderBookSide sellOrders;
    
    /**
     * 새 OrderBook 생성
     * 
     * @param tradingPair 거래 쌍
     */
    public OrderBook(TradingPair tradingPair) {
        this.tradingPair = tradingPair;
        this.buyOrders = new OrderBookSide();
        this.sellOrders = new OrderBookSide();
    }
    
    /**
     * 주문 추가 - 매수/매도에 따라 적절한 side에 추가
     * 
     * @param order 주문 엔트리
     */
    public void addOrder(OrderEntry order) {
        if ("buy".equals(order.getOrderType())) {
            buyOrders.addOrder(order);
        } else if ("sell".equals(order.getOrderType())) {
            sellOrders.addOrder(order);
        }
        // 잘못된 타입 무시
    }
    
    /**
     * 주문 제거
     * 
     * @param orderId 주문 ID
     * @param orderType 주문 타입 ("buy" 또는 "sell")
     * @param price 가격
     * @return 제거된 주문, 없으면 null
     */
    public OrderEntry removeOrder(long orderId, String orderType, BigDecimal price) {
        if ("buy".equals(orderType)) {
            return buyOrders.removeOrder(orderId, price);
        } else if ("sell".equals(orderType)) {
            return sellOrders.removeOrder(orderId, price);
        }
        return null;
    }
    
    /**
     * 최선 매수 가격 (Best Bid) - 가장 높은 매수 가격
     * 
     * @return 최선 매수 가격, 없으면 null
     */
    public BigDecimal getBestBid() {
        return buyOrders.getBestPrice(true);
    }
    
    /**
     * 최선 매도 가격 (Best Ask) - 가장 낮은 매도 가격
     * 
     * @return 최선 매도 가격, 없으면 null
     */
    public BigDecimal getBestAsk() {
        return sellOrders.getBestPrice(false);
    }
    
    /**
     * 스프레드 (Best Ask - Best Bid)
     * 
     * @return 스프레드, 없으면 null
     */
    public BigDecimal getSpread() {
        BigDecimal ask = getBestAsk();
        BigDecimal bid = getBestBid();
        if (ask == null || bid == null) {
            return null;
        }
        return ask.subtract(bid);
    }
    
    /**
     * 중간 가격 (Mid Price) - (Best Ask + Best Bid) / 2
     * 
     * @return 중간 가격, 없으면 null
     */
    public BigDecimal getMidPrice() {
        BigDecimal ask = getBestAsk();
        BigDecimal bid = getBestBid();
        if (ask == null || bid == null) {
            return null;
        }
        return ask.add(bid).divide(new BigDecimal("2"), 18, RoundingMode.HALF_UP);
    }
    
    /**
     * 매수 호가 조회 (상위 N개)
     * 
     * @param depth 조회할 가격 레벨 수
     * @return (가격, 수량) 리스트 (높은 가격부터)
     */
    public List<PriceAmount> getBuyOrders(int depth) {
        List<PriceAmount> result = new ArrayList<>();
        int count = 0;
        
        // 높은 가격부터 (descending order)
        TreeMap<BigDecimal, ArrayDeque<OrderEntry>> buyOrdersMap = buyOrders.getOrders();
        for (BigDecimal price : buyOrdersMap.descendingKeySet()) {
            if (count >= depth) break;
            
            ArrayDeque<OrderEntry> queue = buyOrdersMap.get(price);
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (OrderEntry order : queue) {
                totalAmount = totalAmount.add(order.getRemainingAmount());
            }
            
            result.add(new PriceAmount(price, totalAmount));
            count++;
        }
        
        return result;
    }
    
    /**
     * 매도 호가 조회 (상위 N개)
     * 
     * @param depth 조회할 가격 레벨 수
     * @return (가격, 수량) 리스트 (낮은 가격부터)
     */
    public List<PriceAmount> getSellOrders(int depth) {
        List<PriceAmount> result = new ArrayList<>();
        int count = 0;
        
        // 낮은 가격부터 (ascending order)
        TreeMap<BigDecimal, ArrayDeque<OrderEntry>> sellOrdersMap = sellOrders.getOrders();
        for (BigDecimal price : sellOrdersMap.keySet()) {
            if (count >= depth) break;
            
            ArrayDeque<OrderEntry> queue = sellOrdersMap.get(price);
            BigDecimal totalAmount = BigDecimal.ZERO;
            for (OrderEntry order : queue) {
                totalAmount = totalAmount.add(order.getRemainingAmount());
            }
            
            result.add(new PriceAmount(price, totalAmount));
            count++;
        }
        
        return result;
    }
    
    /**
     * 거래 쌍
     * 
     * @return 거래 쌍
     */
    public TradingPair getTradingPair() {
        return tradingPair;
    }
    
    /**
     * 전체 매수 주문 수
     * 
     * @return 전체 매수 주문 수
     */
    public int getTotalBuyOrders() {
        return buyOrders.getTotalOrders();
    }
    
    /**
     * 전체 매도 주문 수
     * 
     * @return 전체 매도 주문 수
     */
    public int getTotalSellOrders() {
        return sellOrders.getTotalOrders();
    }
    
    /**
     * 호가창이 비었는지 확인
     * 
     * @return true - 비어있음, false - 주문 있음
     */
    public boolean isEmpty() {
        return buyOrders.isEmpty() && sellOrders.isEmpty();
    }
    
    /**
     * 매수 호가 접근 (Matcher에서 사용)
     */
    public OrderBookSide getBuyOrders() {
        return buyOrders;
    }
    
    /**
     * 매도 호가 접근 (Matcher에서 사용)
     */
    public OrderBookSide getSellOrders() {
        return sellOrders;
    }
    
    /**
     * 가격-수량 쌍
     */
    public static class PriceAmount {
        private final BigDecimal price;
        private final BigDecimal amount;
        
        public PriceAmount(BigDecimal price, BigDecimal amount) {
            this.price = price;
            this.amount = amount;
        }
        
        public BigDecimal getPrice() {
            return price;
        }
        
        public BigDecimal getAmount() {
            return amount;
        }
    }
}
