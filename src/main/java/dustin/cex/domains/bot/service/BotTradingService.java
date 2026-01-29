package dustin.cex.domains.bot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.bot.model.BotConfig;
import dustin.cex.domains.bot.model.BotTradingFrequency;
import dustin.cex.domains.bot.model.BotTradingStrategy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 봇 거래 서비스
 * Bot Trading Service
 * 
 * 역할:
 * - 각 봇별로 독립적인 거래 스케줄러 실행
 * - 봇의 빈도에 따라 주기적으로 거래 생성
 * - 다양한 거래 전략 적용 (시장가, 지정가, 스프레드)
 * 
 * 처리 흐름:
 * 1. 서버 시작 시 각 봇별 스케줄러 시작
 * 2. 봇의 빈도에 따라 주기적으로 거래 생성
 * 3. 랜덤으로 전략 선택 (MARKET 30%, LIMIT 50%, SPREAD 20%)
 * 4. 매수 봇은 매수만, 매도 봇은 매도만
 * 
 * 봇 구성:
 * - 매수 봇: 홀수 ID (1, 3, 5, 7, 9, 11, 13, 15, 17, 19)
 * - 매도 봇: 짝수 ID (2, 4, 6, 8, 10, 12, 14, 16, 18, 20)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotTradingService {
    
    private final BotConfig botConfig;
    private final BotManagerService botManagerService;
    private final BotService botService;
    private final BinancePriceService binancePriceService;
    
    /**
     * 스케줄러 풀 (각 봇별 독립적인 스레드)
     * Scheduler pool (independent thread per bot)
     */
    private ScheduledExecutorService scheduler;
    
    /**
     * 봇별 스케줄러 Future (취소용)
     * Bot scheduler futures (for cancellation)
     */
    private final Map<Long, ScheduledFuture<?>> botSchedulers = new HashMap<>();
    
    /**
     * 주문 수량 범위 (최소 ~ 최대)
     * Order quantity range (min ~ max)
     */
    private static final BigDecimal MIN_QUANTITY = BigDecimal.valueOf(0.1);
    private static final BigDecimal MAX_QUANTITY = BigDecimal.valueOf(10.0);
    
    /**
     * 시장가 주문 금액 (USDT)
     * Market order amount (USDT)
     */
    private static final BigDecimal MARKET_ORDER_AMOUNT = BigDecimal.valueOf(100.0);
    
    /**
     * 서버 시작 시 봇 거래 스케줄러 시작
     * Start bot trading schedulers on server startup
     */
    @PostConstruct
    public void start() {
        log.info("[BotTradingService] 봇 거래 스케줄러 시작...");
        
        // 스케줄러 풀 생성 (봇 수만큼 스레드)
        scheduler = Executors.newScheduledThreadPool(botConfig.getBotCount());
        
        // 각 봇별로 스케줄러 시작
        for (long botId = 1; botId <= botConfig.getBotCount(); botId++) {
            startBotScheduler(botId);
        }
        
        log.info("[BotTradingService] 봇 거래 스케줄러 시작 완료: {}명", botConfig.getBotCount());
    }
    
    /**
     * 봇별 스케줄러 시작
     * Start bot scheduler
     * 
     * @param botId 봇 ID
     */
    private void startBotScheduler(Long botId) {
        User botUser = botManagerService.getBotUser(botId);
        if (botUser == null) {
            log.warn("[BotTradingService] Bot{} 사용자를 찾을 수 없습니다", botId);
            return;
        }
        
        BotTradingFrequency frequency = botConfig.getBotFrequency(botId);
        boolean isBuyBot = botConfig.isBuyBot(botId);
        
        log.info("[BotTradingService] Bot{} 스케줄러 시작: 빈도={}, 타입={}", 
                botId, frequency, isBuyBot ? "매수" : "매도");
        
        // 첫 실행 지연 시간 (랜덤)
        int initialDelay = frequency.getRandomInterval();
        
        // 재귀적으로 랜덤 간격으로 실행
        scheduleNextTrade(botId, botUser.getId(), isBuyBot, frequency, initialDelay);
    }
    
    /**
     * 다음 거래 스케줄링 (재귀적)
     * Schedule next trade (recursive)
     * 
     * 매번 랜덤 간격으로 다음 거래를 스케줄링합니다.
     * 
     * @param botId 봇 ID
     * @param botUserId 봇 사용자 ID
     * @param isBuyBot 매수 봇 여부
     * @param frequency 거래 빈도
     * @param delayMs 지연 시간 (밀리초)
     */
    private void scheduleNextTrade(Long botId, Long botUserId, boolean isBuyBot, 
                                   BotTradingFrequency frequency, long delayMs) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            // 거래 실행
            executeBotTrade(botId, botUserId, isBuyBot);
            
            // 다음 거래 스케줄링 (랜덤 간격)
            int nextDelay = frequency.getRandomInterval();
            scheduleNextTrade(botId, botUserId, isBuyBot, frequency, nextDelay);
        }, delayMs, TimeUnit.MILLISECONDS);
        
        // 기존 스케줄러 취소 후 새로 등록
        ScheduledFuture<?> oldFuture = botSchedulers.put(botId, future);
        if (oldFuture != null) {
            oldFuture.cancel(false);
        }
    }
    
    /**
     * 봇 거래 실행
     * Execute bot trade
     * 
     * @param botId 봇 ID
     * @param botUserId 봇 사용자 ID
     * @param isBuyBot 매수 봇 여부
     */
    private void executeBotTrade(Long botId, Long botUserId, boolean isBuyBot) {
        try {
            // 랜덤 전략 선택
            BotTradingStrategy strategy = BotTradingStrategy.selectRandom();
            String baseMint = botConfig.getBotBaseMint(botId);
            
            log.info("[BotTradingService] Bot{} 거래 실행: 전략={}, baseMint={}, 타입={}", 
                     botId, strategy, baseMint, isBuyBot ? "매수" : "매도");
            
            switch (strategy) {
                case MARKET:
                    executeMarketOrder(botId, botUserId, baseMint, isBuyBot);
                    break;
                case LIMIT:
                    executeLimitOrder(botId, botUserId, baseMint, isBuyBot);
                    break;
                case SPREAD:
                    executeSpreadOrder(botId, botUserId, baseMint);
                    break;
            }
        } catch (Exception e) {
            log.error("[BotTradingService] Bot{} 거래 실행 실패", botId, e);
        }
    }
    
    /**
     * 시장가 주문 실행
     * Execute market order
     * 
     * @param botId 봇 ID
     * @param botUserId 봇 사용자 ID
     * @param baseMint 기준 자산
     * @param isBuyBot 매수 봇 여부
     */
    private void executeMarketOrder(Long botId, Long botUserId, String baseMint, boolean isBuyBot) {
        try {
            if (isBuyBot) {
                // 매수 봇: 시장가 매수
                botService.createMarketBuyOrder(botUserId, baseMint, MARKET_ORDER_AMOUNT);
                log.info("[BotTradingService] Bot{} 시장가 매수 주문 생성", botId);
            } else {
                // 매도 봇: 시장가 매도
                BigDecimal amount = getRandomQuantity();
                botService.createMarketSellOrder(botUserId, baseMint, amount);
                log.info("[BotTradingService] Bot{} 시장가 매도 주문 생성: amount={}", botId, amount);
            }
        } catch (Exception e) {
            log.error("[BotTradingService] Bot{} 시장가 주문 실패", botId, e);
        }
    }
    
    /**
     * 지정가 주문 실행
     * Execute limit order
     * 
     * @param botId 봇 ID
     * @param botUserId 봇 사용자 ID
     * @param baseMint 기준 자산
     * @param isBuyBot 매수 봇 여부
     */
    private void executeLimitOrder(Long botId, Long botUserId, String baseMint, boolean isBuyBot) {
        try {
            // 바이낸스 실시간 가격 사용
            BigDecimal marketPrice = binancePriceService.getMarketPrice(isBuyBot);
            BigDecimal price = getRandomPrice(marketPrice, isBuyBot);
            BigDecimal amount = getRandomQuantity();
            
            if (isBuyBot) {
                // 매수 봇: 지정가 매수 (현재가보다 낮은 가격)
                botService.createLimitBuyOrder(botUserId, baseMint, price, amount);
                log.debug("[BotTradingService] Bot{} 지정가 매수 주문 생성: price={}, amount={}, marketPrice={}", 
                         botId, price, amount, marketPrice);
            } else {
                // 매도 봇: 지정가 매도 (현재가보다 높은 가격)
                botService.createLimitSellOrder(botUserId, baseMint, price, amount);
                log.info("[BotTradingService] Bot{} 지정가 매도 주문 생성: price={}, amount={}, marketPrice={}", 
                         botId, price, amount, marketPrice);
            }
        } catch (Exception e) {
            log.error("[BotTradingService] Bot{} 지정가 주문 실패", botId, e);
        }
    }
    
    /**
     * 스프레드 거래 실행
     * Execute spread trading
     * 
     * 스프레드 거래는 매수/매도 동시 주문을 생성합니다.
     * 매수 봇과 매도 봇 모두 스프레드 거래를 할 수 있지만,
     * 실제로는 매수 봇이 매수 주문, 매도 봇이 매도 주문만 생성합니다.
     * 
     * @param botId 봇 ID
     * @param botUserId 봇 사용자 ID
     * @param baseMint 기준 자산
     */
    private void executeSpreadOrder(Long botId, Long botUserId, String baseMint) {
        try {
            boolean isBuyBot = botConfig.isBuyBot(botId);
            BigDecimal amount = getRandomQuantity();
            
            // 바이낸스 실시간 중간 가격 사용
            BigDecimal midPrice = binancePriceService.getMidPrice();
            
            if (isBuyBot) {
                // 매수 봇: 낮은 가격에 매수 주문 (중간가의 99%)
                BigDecimal buyPrice = midPrice.multiply(BigDecimal.valueOf(0.99));
                buyPrice = buyPrice.setScale(2, RoundingMode.HALF_UP);
                botService.createLimitBuyOrder(botUserId, baseMint, buyPrice, amount);
                log.info("[BotTradingService] Bot{} 스프레드 매수 주문 생성: price={}, amount={}, midPrice={}", 
                         botId, buyPrice, amount, midPrice);
            } else {
                // 매도 봇: 높은 가격에 매도 주문 (중간가의 101%)
                BigDecimal sellPrice = midPrice.multiply(BigDecimal.valueOf(1.01));
                sellPrice = sellPrice.setScale(2, RoundingMode.HALF_UP);
                botService.createLimitSellOrder(botUserId, baseMint, sellPrice, amount);
                log.info("[BotTradingService] Bot{} 스프레드 매도 주문 생성: price={}, amount={}, midPrice={}", 
                         botId, sellPrice, amount, midPrice);
            }
        } catch (Exception e) {
            log.error("[BotTradingService] Bot{} 스프레드 거래 실패", botId, e);
        }
    }
    
    /**
     * 랜덤 가격 생성
     * Generate random price
     * 
     * @param marketPrice 현재 시장 가격
     * @param isBuyBot 매수 봇 여부
     * @return 랜덤 가격
     */
    private BigDecimal getRandomPrice(BigDecimal marketPrice, boolean isBuyBot) {
        // 현재가 기준 ±2% 범위에서 랜덤 가격 생성
        double variation = (Math.random() - 0.5) * 0.04; // -2% ~ +2%
        
        if (isBuyBot) {
            // 매수 봇: 현재가보다 낮은 가격 (현재가의 98% ~ 100%)
            variation = -Math.abs(variation);
        } else {
            // 매도 봇: 현재가보다 높은 가격 (현재가의 100% ~ 102%)
            variation = Math.abs(variation);
        }
        
        BigDecimal price = marketPrice.multiply(BigDecimal.valueOf(1.0 + variation));
        return price.setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * 랜덤 수량 생성
     * Generate random quantity
     * 
     * @return 랜덤 수량
     */
    private BigDecimal getRandomQuantity() {
        double random = Math.random();
        BigDecimal quantity = MIN_QUANTITY.add(
                BigDecimal.valueOf(random).multiply(MAX_QUANTITY.subtract(MIN_QUANTITY))
        );
        return quantity.setScale(4, RoundingMode.HALF_UP);
    }
    
    /**
     * 서버 종료 시 봇 거래 스케줄러 중지
     * Stop bot trading schedulers on server shutdown
     */
    @PreDestroy
    public void stop() {
        log.info("[BotTradingService] 봇 거래 스케줄러 중지...");
        
        // 모든 봇 스케줄러 취소
        for (Map.Entry<Long, ScheduledFuture<?>> entry : botSchedulers.entrySet()) {
            entry.getValue().cancel(false);
        }
        botSchedulers.clear();
        
        // 스케줄러 풀 종료
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        log.info("[BotTradingService] 봇 거래 스케줄러 중지 완료");
    }
}
