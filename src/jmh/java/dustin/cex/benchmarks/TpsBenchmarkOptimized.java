// =====================================================
// TpsBenchmarkOptimized - TPS 벤치마크 (코드 최적화 후)
// =====================================================
// 역할: 엔진 성능 측정 (Rust 벤치마크와 동일한 기준)
// 최적화: BigDecimal 캐싱 (price table 사용)
// 
// 측정 항목:
// 1. limit_order_tps: 지정가 주문 TPS
// 2. market_buy_tps: 시장가 매수 주문 TPS
// 3. mixed_tps: 혼합 주문 TPS
//
// 측정 기준 (Rust와 동일):
// - ORDER_BATCHES: [1,000, 5,000, 10,000, 50,000]
// - NUM_TEST_USERS: 100
// - 초기 잔고: SOL 10,000, USDT 10,000,000
// - 시드 오더북: 100.00 USDT에 1,000,000 SOL 매도 호가
//
// JMH 설정:
// - Warmup: 5 iterations, 3 seconds each
// - Measurement: 5 iterations, 3 seconds each
// - Fork: 1 (단일 JVM에서 실행)
// - Mode: Average Time (TPS 측정)
// =====================================================

package dustin.cex.benchmarks;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import dustin.cex.domains.cex.engine.OrderEntry;
import dustin.cex.domains.cex.engine.runtime.HighPerformanceEngine;

/**
 * TPS 벤치마크 (코드 최적화 후)
 * 
 * 최적화 내용:
 * - BigDecimal price/amount 캐싱 (price table 사용)
 * - 반복적인 객체 생성 감소
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
public class TpsBenchmarkOptimized {
    
    private static final int NUM_TEST_USERS = 100;
    private static final BigDecimal INITIAL_SOL_BALANCE = new BigDecimal("10000");
    private static final BigDecimal INITIAL_USDT_BALANCE = new BigDecimal("10000000");
    private static final BigDecimal SEED_PRICE = new BigDecimal("100.00");
    private static final BigDecimal SEED_AMOUNT = new BigDecimal("1000000");
    private static final BigDecimal ORDER_AMOUNT = new BigDecimal("1.0");
    private static final BigDecimal MARKET_QUOTE_LOW = new BigDecimal("100.00");
    private static final BigDecimal BASE_PRICE = new BigDecimal("100.00");
    private static final BigDecimal PRICE_STEP = new BigDecimal("0.10");
    private static final BigDecimal[] LIMIT_PRICE_TABLE = buildPriceTable(50, BASE_PRICE, PRICE_STEP);
    private static final BigDecimal[] MIXED_PRICE_TABLE = buildPriceTable(20, BASE_PRICE, PRICE_STEP);
    
    private HighPerformanceEngine engine;
    
    /**
     * 주문 수 파라미터 (JMH가 자동으로 각 값에 대해 벤치마크 실행)
     */
    @Param({"1000", "5000", "10000", "50000"})
    public int orderCount;
    
    @Setup(Level.Trial)
    public void setup() {
        engine = new HighPerformanceEngine();
    }
    
    @TearDown(Level.Trial)
    public void tearDown() {
        // 엔진 정리
        engine.benchClearOrderbooks();
        engine.benchClearBalances();
    }
    
    /**
     * 잔고 초기화 (100명 사용자)
     */
    private void seedBalances() {
        for (long userId = 1; userId <= NUM_TEST_USERS; userId++) {
            engine.benchSetBalance(userId, "SOL", INITIAL_SOL_BALANCE, BigDecimal.ZERO);
            engine.benchSetBalance(userId, "USDT", INITIAL_USDT_BALANCE, BigDecimal.ZERO);
        }
    }
    
    /**
     * 오더북 시드 (매도 호가 추가)
     */
    private void seedOrderbook() {
        long orderId = 10_000_000L;
        long userId = 10_000L;
        
        // 시드용 사용자 잔고 설정
        engine.benchSetBalance(userId, "SOL", SEED_AMOUNT, BigDecimal.ZERO);
        
        // 매도 주문 추가 (100.00 USDT에 1,000,000 SOL)
        OrderEntry seedOrder = buildLimitOrder(orderId, userId, SEED_PRICE, SEED_AMOUNT, false);
        engine.benchSubmitDirect(seedOrder);
    }
    
    /**
     * 지정가 주문 TPS 벤치마크
     */
    @Benchmark
    public void limitOrderTps(Blackhole bh) {
        resetBenchState();
        
        for (int idx = 0; idx < orderCount; idx++) {
            long orderId = 1_000_000L + idx;
            long userId = (idx % NUM_TEST_USERS) + 1;
            BigDecimal amount = ORDER_AMOUNT;
            BigDecimal price = LIMIT_PRICE_TABLE[idx % LIMIT_PRICE_TABLE.length]; // 캐싱된 price 사용
            boolean isBuy = idx % 2 == 0;
            
            OrderEntry order = buildLimitOrder(orderId, userId, price, amount, isBuy);
            bh.consume(engine.benchSubmitDirect(order));
        }
    }
    
    /**
     * 시장가 매수 주문 TPS 벤치마크
     */
    @Benchmark
    public void marketBuyTps(Blackhole bh) {
        resetBenchState();
        
        for (int idx = 0; idx < orderCount; idx++) {
            long orderId = 2_000_000L + idx;
            long userId = (idx % NUM_TEST_USERS) + 1;
            BigDecimal quoteAmount = MARKET_QUOTE_LOW; // 캐싱된 상수 사용
            
            OrderEntry order = buildMarketBuyOrder(orderId, userId, quoteAmount);
            bh.consume(engine.benchSubmitDirect(order));
        }
    }
    
    /**
     * 혼합 주문 TPS 벤치마크 (지정가 매수 33% + 지정가 매도 33% + 시장가 매수 33%)
     */
    @Benchmark
    public void mixedTps(Blackhole bh) {
        resetBenchState();
        
        for (int idx = 0; idx < orderCount; idx++) {
            long orderId = 3_000_000L + idx;
            long userId = (idx % NUM_TEST_USERS) + 1;
            
            int remainder = idx % 3;
            if (remainder == 0) {
                // 지정가 매수 주문 (33%)
                BigDecimal amount = ORDER_AMOUNT;
                BigDecimal price = MIXED_PRICE_TABLE[idx % MIXED_PRICE_TABLE.length]; // 캐싱된 price 사용
                OrderEntry order = buildLimitOrder(orderId, userId, price, amount, true);
                bh.consume(engine.benchSubmitDirect(order));
            } else if (remainder == 1) {
                // 지정가 매도 주문 (33%)
                BigDecimal amount = ORDER_AMOUNT;
                BigDecimal price = MIXED_PRICE_TABLE[idx % MIXED_PRICE_TABLE.length]; // 캐싱된 price 사용
                OrderEntry order = buildLimitOrder(orderId, userId, price, amount, false);
                bh.consume(engine.benchSubmitDirect(order));
            } else {
                // 시장가 매수 주문 (33%)
                BigDecimal quoteAmount = MARKET_QUOTE_LOW; // 캐싱된 상수 사용
                OrderEntry order = buildMarketBuyOrder(orderId, userId, quoteAmount);
                bh.consume(engine.benchSubmitDirect(order));
            }
        }
    }
    
    /**
     * 벤치마크 상태 초기화
     */
    private void resetBenchState() {
        engine.benchClearOrderbooks();
        engine.benchClearBalances();
        seedBalances();
        seedOrderbook();
    }
    
    /**
     * 지정가 주문 생성
     */
    private OrderEntry buildLimitOrder(long orderId, long userId, BigDecimal price, BigDecimal amount, boolean isBuy) {
        OrderEntry order = new OrderEntry();
        order.setId(orderId);
        order.setUserId(userId);
        order.setOrderType(isBuy ? "buy" : "sell");
        order.setOrderSide("limit");
        order.setBaseMint("SOL");
        order.setQuoteMint("USDT");
        order.setPrice(price);
        order.setAmount(amount);
        order.setQuoteAmount(null);
        order.setFilledAmount(BigDecimal.ZERO);
        order.setRemainingAmount(amount);
        order.setRemainingQuoteAmount(null);
        order.setCreatedAt(Instant.now());
        return order;
    }
    
    /**
     * 시장가 매수 주문 생성
     */
    private OrderEntry buildMarketBuyOrder(long orderId, long userId, BigDecimal quoteAmount) {
        OrderEntry order = new OrderEntry();
        order.setId(orderId);
        order.setUserId(userId);
        order.setOrderType("buy");
        order.setOrderSide("market");
        order.setBaseMint("SOL");
        order.setQuoteMint("USDT");
        order.setPrice(null);
        order.setAmount(BigDecimal.ZERO);
        order.setQuoteAmount(quoteAmount);
        order.setFilledAmount(BigDecimal.ZERO);
        order.setRemainingAmount(BigDecimal.ZERO);
        order.setRemainingQuoteAmount(quoteAmount);
        order.setCreatedAt(Instant.now());
        return order;
    }

    /**
     * 가격 테이블 생성 (최적화: 미리 계산하여 캐싱)
     */
    private static BigDecimal[] buildPriceTable(int size, BigDecimal start, BigDecimal step) {
        BigDecimal[] table = new BigDecimal[size];
        for (int idx = 0; idx < size; idx++) {
            table[idx] = start.add(step.multiply(new BigDecimal(idx)));
        }
        return table;
    }
    
    /**
     * 메인 메서드 (직접 실행 가능)
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
            .include(TpsBenchmarkOptimized.class.getSimpleName())
            .build();
        
        new Runner(opt).run();
    }
}
