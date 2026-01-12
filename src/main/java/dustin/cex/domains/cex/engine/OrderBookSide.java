// =====================================================
// OrderBookSide - 호가창 한쪽 방향 (매수 또는 매도)
// =====================================================
// 역할: 매수 또는 매도 주문을 가격별로 관리하는 자료구조
// 
// 핵심 설계:
// 1. TreeMap으로 가격별 정렬 (O(log n))
// 2. ArrayDeque로 같은 가격 내 Time Priority (FIFO)
//
// 자료구조:
// 1. TreeMap<BigDecimal, ArrayDeque<OrderEntry>>
//    - TreeMap: 가격별 정렬 (BTreeMap과 유사한 성능)
//      * Key: BigDecimal (가격)
//      * Value: ArrayDeque<OrderEntry> (같은 가격의 주문들)
//      * 장점: O(log n) 조회, 자동 정렬 (가격 순서 유지)
//      * 최선가 조회: firstKey() / lastKey() → O(log n)
//      * 단점:
//        - Rust BTreeMap보다 약간 느림 (Red-Black Tree vs B-Tree)
//        - 참조 기반 (Entry 객체가 힙에 분산, 캐시 미스 가능)
//        - BigDecimal 비교 비용
//    
// 2. ArrayDeque<OrderEntry>
//    - 같은 가격 레벨의 주문들을 FIFO 순서로 관리
//    * pollFirst(): 가장 오래된 주문 (O(1))
//    * addLast(): 새 주문 추가 (O(1))
//    * 순회: O(n) where n=해당 가격의 주문 수
//    * 단점/한계:
//      - 참조 배열만 연속 (실제 OrderEntry 객체는 힙에 분산)
//      - Pointer chasing 발생
//      - 캐시 미스 가능성 높음
//
// 작동 방식:
// 1. 주문 추가: 가격 레벨을 찾아 해당 가격의 큐 맨 뒤에 추가
// 2. 주문 제거: 가격 레벨에서 주문을 찾아 제거 (큐가 비면 레벨 제거)
// 3. 최선가 조회: TreeMap의 firstKey()/lastKey()로 조회
// =====================================================

package dustin.cex.domains.cex.engine;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.TreeMap;

/**
 * 호가창 한쪽 방향 (매수 또는 매도)
 * 
 * 구조:
 * TreeMap { 100.5 -> [주문1, 주문2], 100.0 -> [주문3], 99.5 -> [주문4] }
 * 
 * ArrayDeque 사용 이유:
 * - pollFirst()로 가장 오래된 주문 (FIFO)
 * - addLast()로 추가 (O(1) 삽입)
 * - O(1) 삭제 (front에서 제거)
 */
public class OrderBookSide {
    /**
     * 가격별 주문 큐
     * Key: BigDecimal (가격)
     * Value: ArrayDeque<OrderEntry> (같은 가격의 주문들)
     */
    private final TreeMap<BigDecimal, ArrayDeque<OrderEntry>> orders;
    
    /**
     * 전체 주문 수 (캐싱)
     * O(1) 조회를 위해 별도 저장
     */
    private int totalOrders;
    
    /**
     * 새로운 OrderBookSide 생성
     */
    public OrderBookSide() {
        this.orders = new TreeMap<>();
        this.totalOrders = 0;
    }
    
    /**
     * 주문 추가 - 주문 가격의 큐 맨 뒤에 추가 (Time Priority)
     * 
     * @param order 주문 엔트리
     */
    public void addOrder(OrderEntry order) {
        BigDecimal price = order.getPrice();
        if (price == null) {
            throw new IllegalArgumentException("Limit order must have price");
        }
        
        // 가격 레벨이 없으면 새로 생성, 있으면 기존 큐에 추가
        orders.computeIfAbsent(price, k -> new ArrayDeque<>()).addLast(order);
        totalOrders++;
    }
    
    /**
     * 주문 제거 (주문 ID로)
     * 
     * 시간 복잡도: O(log n + m) where n=가격 레벨 수, m=해당 가격의 주문 수
     * 
     * @param orderId 주문 ID
     * @param price 가격
     * @return 제거된 주문, 없으면 null
     */
    public OrderEntry removeOrder(long orderId, BigDecimal price) {
        ArrayDeque<OrderEntry> queue = orders.get(price);
        if (queue == null) {
            return null;
        }
        
        // 주문 ID로 찾아서 제거
        for (OrderEntry order : queue) {
            if (order.getId() == orderId) {
                queue.remove(order);
                totalOrders--;
                
                // 큐가 비었으면 가격 레벨 제거
                if (queue.isEmpty()) {
                    orders.remove(price);
                }
                
                return order;
            }
        }
        
        return null;
    }
    
    /**
     * 최선 가격 조회 (매수: 최고가, 매도: 최저가)
     * 
     * 시간 복잡도: O(log n) where n=가격 레벨 수
     * 
     * @param isBuy 매수 여부 (true: 매수, false: 매도)
     * @return 최선 가격, 없으면 null
     */
    public BigDecimal getBestPrice(boolean isBuy) {
        if (orders.isEmpty()) {
            return null;
        }
        
        if (isBuy) {
            // 매수: 가장 높은 가격 (TreeMap의 lastKey)
            return orders.lastKey();
        } else {
            // 매도: 가장 낮은 가격 (TreeMap의 firstKey)
            return orders.firstKey();
        }
    }
    
    /**
     * 특정 가격의 주문들 조회 (불변)
     * 
     * @param price 가격
     * @return 주문 큐, 없으면 null
     */
    public ArrayDeque<OrderEntry> getOrdersAtPrice(BigDecimal price) {
        return orders.get(price);
    }
    
    /**
     * 전체 주문 수
     * 
     * @return 전체 주문 수
     */
    public int getTotalOrders() {
        return totalOrders;
    }
    
    /**
     * 가격 레벨 수
     * 
     * @return 가격 레벨 수
     */
    public int getPriceLevels() {
        return orders.size();
    }
    
    /**
     * 호가창이 비었는지 확인
     * 
     * @return true - 비어있음, false - 주문 있음
     */
    public boolean isEmpty() {
        return orders.isEmpty();
    }
    
    /**
     * 가격 레벨 제거
     * 
     * @param price 가격
     */
    public void removePriceLevel(BigDecimal price) {
        ArrayDeque<OrderEntry> queue = orders.remove(price);
        if (queue != null) {
            totalOrders -= queue.size();
        }
    }
    
    /**
     * TreeMap 직접 접근 (OrderBook에서 사용)
     * Matcher에서 가격 레벨 순회가 필요하므로 노출
     */
    public TreeMap<BigDecimal, ArrayDeque<OrderEntry>> getOrders() {
        return orders;
    }
}

