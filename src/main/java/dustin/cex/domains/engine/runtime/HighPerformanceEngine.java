// =====================================================
// HighPerformanceEngine - 고성능 체결 엔진
// =====================================================
// 역할: 모든 주문 처리, 매칭, 체결을 담당하는 통합 엔진
// 
// 핵심 설계:
// 1. 싱글 스레드 엔진 - 모든 주문 순차 처리 (벤치마크 모드)
// 2. 메모리 기반 처리 - DB/WAL 제외
// 3. 직접 호출 - 채널/스레드 제외
//
// 벤치마크 모드 특징:
// - DB 저장 없음: 메모리만 사용
// - WAL 쓰기 없음: 디스크 I/O 제외
// - 직접 호출: 채널 없이 동기 호출 (bench_submit_direct)
//
// 자료구조:
// 1. HashMap<TradingPair, OrderBook>
//    - 거래쌍별 오더북 관리
//    * Key: TradingPair (예: SOL/USDT)
//    * Value: OrderBook (매수/매도 호가)
//    * 조회: O(1) average
//    * 장점: 매우 빠른 오더북 조회
//    * 단점/한계:
//      - 해시 충돌 시 O(n) (드물지만 발생 가능)
//      - 동적 리사이징 비용 (초기 크기 설정 중요)
//      - Rust와 동일하므로 큰 차이 없음
//
// 2. Matcher
//    - Price-Time Priority 매칭 알고리즘
//    * 상태 없음 (stateless)
//    * 성능: < 0.5ms per order
//
// 3. Executor
//    - BalanceCache 포함
//    * 잔고 이체: O(1) (HashMap 조회/업데이트)
//    * Rust와 유사한 성능
//
// 처리 흐름 (벤치마크 모드):
// 1. 주문 제출: bench_submit_direct() → 직접 처리
// 2. 잔고 잠금: BalanceCache.lockBalance()
// 3. OrderBook 추가: 지정가 주문만
// 4. 매칭 시도: Matcher.matchOrder()
// 5. 체결 실행: Executor.executeTrade()
// 6. 결과 반환: MatchResult 리스트
//
// 성능 (Java 한계):
// - 주문 처리: < 0.5ms (평균, Rust보다 약간 느림)
// - 체결 처리: < 0.2ms (평균, Rust보다 약간 느림)
// - TPS: 30,000-40,000 orders/sec (예상, Rust: 50,000+)
// - GC pause: 5-25ms (G1GC, Rust는 0ms)
// - P99 지연시간: 5-25ms (GC pause 포함, Rust: 0.5ms)
// =====================================================

package dustin.cex.domains.engine.runtime;

import dustin.cex.domains.engine.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;

/**
 * 고성능 체결 엔진 (벤치마크용)
 * 
 * 벤치마크 모드:
 * - DB 저장 없음: 메모리만 사용
 * - WAL 쓰기 없음: 디스크 I/O 제외
 * - 직접 호출: 채널 없이 동기 호출 (bench_submit_direct)
 */
public class HighPerformanceEngine {
    /**
     * 거래쌍별 오더북
     * Key: TradingPair (예: SOL/USDT)
     * Value: OrderBook (매수/매도 호가)
     */
    private final HashMap<TradingPair, OrderBook> orderbooks;
    
    /**
     * 매칭 엔진
     * Price-Time Priority 기반 매칭 알고리즘
     */
    private final Matcher matcher;
    
    /**
     * 체결 실행 엔진
     * MatchResult를 받아서 실제 체결 처리
     */
    private final Executor executor;
    
    /**
     * 새 엔진 생성 (벤치마크용)
     */
    public HighPerformanceEngine() {
        this.orderbooks = new HashMap<>();
        this.matcher = new Matcher();
        this.executor = new Executor();
    }
    
    /**
     * 벤치모드에서 직접 주문 처리 (큐/채널 우회)
     * 
     * @param order 주문 엔트리
     * @return 매칭 결과 리스트
     * @throws IllegalArgumentException 잔고 부족 시
     * 
     * 처리 과정:
     * 1. TradingPair 찾기/생성
     * 2. 잔고 잠금 (주문 제출 전에 잠금)
     * 3. 지정가 주문만 OrderBook에 추가
     * 4. 매칭 시도
     * 5. 체결 실행 (Executor.executeTrade)
     * 6. 남은 주문이 있으면 OrderBook에 추가
     */
    public List<MatchResult> benchSubmitDirect(OrderEntry order) {
        // 1. TradingPair 찾기
        TradingPair pair = new TradingPair(order.getBaseMint(), order.getQuoteMint());
        
        // 2. 잔고 잠금 (주문 제출 전에 잠금)
        BalanceCache balanceCache = executor.getBalanceCache();
        String lockMint;
        BigDecimal lockAmount;
        
        if ("buy".equals(order.getOrderType())) {
            // 매수: quote_mint 잠금
            // 지정가: price * amount
            // 시장가: quote_amount
            if ("market".equals(order.getOrderSide())) {
                // 시장가 매수: quote_amount 사용
                lockAmount = order.getQuoteAmount();
                if (lockAmount == null) {
                    lockAmount = BigDecimal.ZERO;
                }
            } else {
                // 지정가 매수: price * amount
                BigDecimal price = order.getPrice();
                if (price == null) {
                    throw new IllegalArgumentException("Limit order must have price");
                }
                lockAmount = price.multiply(order.getAmount());
            }
            lockMint = order.getQuoteMint();
        } else {
            // 매도: base_mint 잠금 (amount만큼)
            lockMint = order.getBaseMint();
            lockAmount = order.getAmount();
        }
        
        // 잔고 잠금
        balanceCache.lockBalance(order.getUserId(), lockMint, lockAmount);
        
        // 3. OrderBook 가져오기 또는 생성
        OrderBook orderbook = orderbooks.computeIfAbsent(pair, k -> new OrderBook(pair));
        
        // 4. 매칭 시도 (지정가 주문은 OrderBook에 추가하기 전에 매칭)
        List<MatchResult> matches = matcher.matchOrder(order, orderbook);
        
        // 5. 체결 실행
        for (MatchResult matchResult : matches) {
            executor.executeTrade(matchResult);
        }
        
        // 6. 지정가 주문이고 남은 수량이 있으면 OrderBook에 추가
        if ("limit".equals(order.getOrderSide()) && order.getPrice() != null) {
            // 주문이 완전히 체결되지 않았는지 확인
            boolean hasRemaining;
            if (order.getRemainingQuoteAmount() != null) {
                hasRemaining = order.getRemainingQuoteAmount().compareTo(BigDecimal.ZERO) > 0;
            } else {
                hasRemaining = order.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0;
            }
            
            if (hasRemaining) {
                orderbook.addOrder(order);
            }
        }
        // 시장가 주문은 OrderBook에 추가하지 않음 (즉시 체결되어야 하므로)
        
        return matches;
    }
    
    /**
     * 벤치모드에서 잔고 설정
     * 
     * @param userId 사용자 ID
     * @param mint 자산 종류
     * @param available 사용 가능 잔고
     * @param locked 잠긴 잔고
     */
    public void benchSetBalance(long userId, String mint, BigDecimal available, BigDecimal locked) {
        executor.getBalanceCache().setBalance(userId, mint, available, locked);
    }
    
    /**
     * 벤치모드에서 잔고 초기화
     */
    public void benchClearBalances() {
        executor.getBalanceCache().clear();
    }
    
    /**
     * 벤치모드에서 오더북 초기화
     */
    public void benchClearOrderbooks() {
        orderbooks.clear();
    }
    
    /**
     * Executor 접근 (테스트용)
     */
    public Executor getExecutor() {
        return executor;
    }
    
    /**
     * OrderBook 접근 (테스트용)
     */
    public OrderBook getOrderBook(TradingPair pair) {
        return orderbooks.get(pair);
    }
}
