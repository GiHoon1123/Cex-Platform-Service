package dustin.cex.domains.bot.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dustin.cex.domains.bot.model.BinanceOrderbookEntry;
import dustin.cex.domains.bot.model.BinanceOrderbookUpdate;
import dustin.cex.domains.bot.model.BotConfig;
import dustin.cex.domains.order.model.dto.OrderResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 오더북 동기화 서비스
 * Orderbook Synchronization Service
 * 
 * 역할:
 * - 바이낸스 오더북을 실시간으로 수신
 * - 변경된 호가만 업데이트 (취소/생성)
 * - 새로운 봇 주문 생성 (바이낸스와 동일한 지정가 주문)
 * 
 * 처리 흐름:
 * 1. 바이낸스 오더북 업데이트 수신
 * 2. 기존 주문과 새 호가 비교:
 *    - 가격이 같은 주문 → 유지
 *    - 가격이 다른 주문 → 취소 후 새로 생성
 *    - 새로운 가격 → 새로 생성
 *    - 사라진 가격 → 취소
 * 
 * 주의사항:
 * - bot1은 매수 전용, bot2는 매도 전용
 * - 바이낸스 오더북의 각 호가에 대해 고정 수량으로 주문 생성
 * - 변경된 호가만 업데이트하여 불필요한 취소 이벤트 최소화
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderbookSyncService {
    
    private final BotConfig botConfig;
    private final BotManagerService botManagerService;
    private final BotService botService;
    private final BinanceWebSocketClient binanceWebSocketClient;
    private final PlatformTransactionManager transactionManager;
    private final BinancePriceService binancePriceService;
    
    /**
     * 트랜잭션 템플릿 (비동기 스레드에서 트랜잭션 사용)
     * Transaction Template for async threads
     */
    private TransactionTemplate transactionTemplate;
    
    /**
     * 봇별 활성 주문 추적 (봇 ID -> 가격 -> 주문 ID)
     * Bot active orders tracking (bot ID -> price -> order ID)
     */
    private final Map<Long, Map<String, Long>> botOrdersMap = new HashMap<>();
    
    /**
     * 서버 시작 시 오더북 동기화 시작
     * Start orderbook synchronization on server startup
     * 
     * @PostConstruct로 서버 시작 시 자동 실행
     */
    @PostConstruct
    public void start() {
        // TransactionTemplate 초기화
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        
        // 봇별 주문 맵 초기화
        int botCount = botConfig.getBotCount();
        for (long i = 1; i <= botCount; i++) {
            botOrdersMap.put(i, new HashMap<>());
        }
        
        log.info("[OrderbookSyncService] 오더북 동기화 시작...");
        log.info("  - 바이낸스 WebSocket URL: {}", botConfig.getBinanceWsUrl());
        log.info("  - 오더북 깊이: {}", botConfig.getOrderbookDepth());
        log.info("  - 주문 수량: {}", botConfig.getOrderQuantity());
        log.info("  - 봇 수: {}", botCount);
        
        // 바이낸스 WebSocket 연결 시작
        binanceWebSocketClient.start(botConfig.getBinanceWsUrl(), this::handleOrderbookUpdate);
        
        log.info("[OrderbookSyncService] 오더북 동기화 시작 완료");
    }
    
    /**
     * 서버 종료 시 오더북 동기화 중지
     * Stop orderbook synchronization on server shutdown
     */
    @PreDestroy
    public void stop() {
        // log.info("[OrderbookSyncService] 오더북 동기화 중지...");
        binanceWebSocketClient.stop();
        // log.info("[OrderbookSyncService] 오더북 동기화 중지 완료");
    }
    
    /**
     * 오더북 업데이트 처리
     * Handle orderbook update
     * 
     * 바이낸스 오더북 업데이트를 받아서 봇 주문을 동기화합니다.
     * 
     * 처리 과정:
     * 1. 바이낸스 오더북 파싱
     * 2. 기존 주문과 새 호가 비교하여 변경된 것만 업데이트:
     *    - 가격이 같은 주문 → 유지
     *    - 가격이 다른 주문 → 취소 후 새로 생성
     *    - 새로운 가격 → 새로 생성
     *    - 사라진 가격 → 취소
     * 
     * 봇 분배 전략:
     * - 홀수 봇 (1, 3, 5, ...): 매수 그룹
     * - 짝수 봇 (2, 4, 6, ...): 매도 그룹
     * - 각 봇은 BotConfig에서 설정한 baseMint 사용
     * 
     * @param update 바이낸스 오더북 업데이트
     */
    private void handleOrderbookUpdate(BinanceOrderbookUpdate update) {
        try {
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 1. 바이낸스 오더북 파싱
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            List<BinanceOrderbookEntry>[] parsed = BinanceWebSocketClient.parseOrderbookUpdate(update);
            List<BinanceOrderbookEntry> bids = parsed[0];
            List<BinanceOrderbookEntry> asks = parsed[1];
            
            // 바이낸스 가격 서비스에 오더북 업데이트 전달
            binancePriceService.updateOrderbook(bids, asks);
            
            int depth = botConfig.getOrderbookDepth();
            BigDecimal orderQuantity = botConfig.getOrderQuantity();
            
            // 상위 N개만 사용
            List<BinanceOrderbookEntry> topBids = new ArrayList<>();
            if (bids != null) {
                topBids = bids.stream().limit(depth).toList();
            }
            
            List<BinanceOrderbookEntry> topAsks = new ArrayList<>();
            if (asks != null) {
                topAsks = asks.stream().limit(depth).toList();
            }
            
            int botCount = botConfig.getBotCount();
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 2. 홀수 봇들 (매수) 주문 업데이트
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            for (long botId = 1; botId <= botCount; botId += 2) {
                Long botUserId = botManagerService.getBotUserId(botId);
                if (botUserId != null) {
                    Map<String, Long> botOrders = botOrdersMap.get(botId);
                    updateBotOrders(botId, botUserId, botOrders, topBids, orderQuantity, true);
                }
            }
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 3. 짝수 봇들 (매도) 주문 업데이트
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            for (long botId = 2; botId <= botCount; botId += 2) {
                Long botUserId = botManagerService.getBotUserId(botId);
                if (botUserId != null) {
                    Map<String, Long> botOrders = botOrdersMap.get(botId);
                    updateBotOrders(botId, botUserId, botOrders, topAsks, orderQuantity, false);
                }
            }
            
        } catch (Exception e) {
            log.error("[OrderbookSyncService] 오더북 업데이트 처리 실패", e);
        }
    }
    
    /**
     * 봇 주문 업데이트 (변경된 호가만 처리)
     * Update bot orders (only changed prices)
     * 
     * 처리 로직:
     * 1. 새 호가의 가격 목록 생성
     * 2. 기존 주문과 비교:
     *    - 새 호가에 없는 가격 → 취소
     *    - 새 호가에 있는 가격 → 유지 (이미 주문이 있으면) 또는 생성 (없으면)
     * 
     * @param botId 봇 ID (BotConfig에서 baseMint 가져오기 위해 필요)
     * @param botUserId 봇 사용자 ID
     * @param existingOrders 기존 주문 맵 (가격 -> 주문 ID)
     * @param newEntries 새로운 호가 목록
     * @param orderQuantity 주문 수량
     * @param isBuy 매수 여부 (true: 매수, false: 매도)
     */
    private void updateBotOrders(Long botId, Long botUserId, Map<String, Long> existingOrders, 
                                 List<BinanceOrderbookEntry> newEntries, 
                                 BigDecimal orderQuantity, boolean isBuy) {
        // 새 호가의 가격 목록 생성
        Map<String, BinanceOrderbookEntry> newPrices = new HashMap<>();
        for (BinanceOrderbookEntry entry : newEntries) {
            String priceKey = entry.getPrice().toString();
            newPrices.put(priceKey, entry);
        }
        
        // 기존 주문 맵 복사 (동시성 문제 방지)
        Map<String, Long> existingOrdersCopy;
        synchronized (existingOrders) {
            existingOrdersCopy = new HashMap<>(existingOrders);
        }
        
        final Long finalBotUserId = botUserId;
        final BigDecimal finalOrderQuantity = orderQuantity;
        final TransactionTemplate txTemplate = transactionTemplate;
        
        // 비동기로 처리
        CompletableFuture.runAsync(() -> {
            // 1. 사라진 가격의 주문 취소
            for (Map.Entry<String, Long> existingEntry : existingOrdersCopy.entrySet()) {
                String priceKey = existingEntry.getKey();
                Long orderId = existingEntry.getValue();
                
                // 새 호가에 없는 가격이면 취소
                if (!newPrices.containsKey(priceKey)) {
                    try {
                        botService.cancelBotOrder(finalBotUserId, orderId);
                        synchronized (existingOrders) {
                            existingOrders.remove(priceKey);
                        }
                    } catch (Exception e) {
                        // 주문이 이미 체결되었거나 취소되었을 수 있으므로 에러는 무시
                    }
                }
            }
            
            // 2. 새로운 호가에 대해 주문 생성 또는 유지
            for (BinanceOrderbookEntry entry : newEntries) {
                String priceKey = entry.getPrice().toString();
                
                // 이미 주문이 있으면 유지 (아무것도 안 함)
                synchronized (existingOrders) {
                    if (existingOrders.containsKey(priceKey)) {
                        continue; // 이미 주문이 있으므로 유지
                    }
                }
                
                // 주문이 없으면 새로 생성
                try {
                    // BotConfig에서 봇의 baseMint 가져오기
                    String baseMint = botConfig.getBotBaseMint(botId);
                    
                    final String finalBaseMint = baseMint;
                    OrderResponse response = txTemplate.execute(status -> {
                        if (isBuy) {
                            return botService.createLimitBuyOrder(
                                    finalBotUserId,
                                    finalBaseMint,
                                    entry.getPrice(),
                                    finalOrderQuantity
                            );
                        } else {
                            return botService.createLimitSellOrder(
                                    finalBotUserId,
                                    finalBaseMint,
                                    entry.getPrice(),
                                    finalOrderQuantity
                            );
                        }
                    });
                    
                    if (response != null) {
                        synchronized (existingOrders) {
                            existingOrders.put(priceKey, Long.parseLong(response.getOrder().getId()));
                        }
                    }
                } catch (Exception e) {
                    // 주문 생성 실패 (잔고 부족 등) - 에러만 로그
                    log.error("[OrderbookSyncService] Bot{} 주문 생성 실패: price={}, amount={}, error={}", 
                            botId, entry.getPrice(), finalOrderQuantity, e.getMessage());
                }
            }
        });
    }
}
