package dustin.cex.domains.bot.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

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
 * - 기존 봇 주문 모두 취소
 * - 새로운 봇 주문 생성 (바이낸스와 동일한 지정가 주문)
 * 
 * 처리 흐름:
 * 1. 바이낸스 오더북 업데이트 수신
 * 2. 기존 봇 주문 모두 취소
 * 3. 새로운 봇 주문 생성 (상위 N개만)
 * 
 * 주의사항:
 * - bot1은 매수 전용, bot2는 매도 전용
 * - 바이낸스 오더북의 각 호가에 대해 고정 수량으로 주문 생성
 * - 오더북 업데이트마다 기존 주문을 모두 취소하고 새로 생성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderbookSyncService {
    
    private final BotConfig botConfig;
    private final BotManagerService botManagerService;
    private final BotService botService;
    private final BinanceWebSocketClient binanceWebSocketClient;
    
    /**
     * 봇 1 (매수)의 활성 주문 추적
     * Bot 1 (buy) active orders tracking
     * Key: 가격 (String), Value: 주문 ID
     */
    private final Map<String, Long> bot1Orders = new HashMap<>();
    
    /**
     * 봇 2 (매도)의 활성 주문 추적
     * Bot 2 (sell) active orders tracking
     * Key: 가격 (String), Value: 주문 ID
     */
    private final Map<String, Long> bot2Orders = new HashMap<>();
    
    /**
     * 서버 시작 시 오더북 동기화 시작
     * Start orderbook synchronization on server startup
     * 
     * @PostConstruct로 서버 시작 시 자동 실행
     */
    @PostConstruct
    public void start() {
        log.info("[OrderbookSyncService] 오더북 동기화 시작...");
        log.info("  - 바이낸스 WebSocket URL: {}", botConfig.getBinanceWsUrl());
        log.info("  - 오더북 깊이: {}", botConfig.getOrderbookDepth());
        log.info("  - 주문 수량: {}", botConfig.getOrderQuantity());
        
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
        log.info("[OrderbookSyncService] 오더북 동기화 중지...");
        binanceWebSocketClient.stop();
        log.info("[OrderbookSyncService] 오더북 동기화 중지 완료");
    }
    
    /**
     * 오더북 업데이트 처리
     * Handle orderbook update
     * 
     * 바이낸스 오더북 업데이트를 받아서 봇 주문을 동기화합니다.
     * 
     * 처리 과정:
     * 1. 바이낸스 오더북 파싱
     * 2. 기존 봇 주문 모두 취소
     * 3. 새로운 봇 주문 생성 (상위 N개만)
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
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 2. 기존 봇 주문 모두 취소
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            Long bot1UserId = botManagerService.getBot1UserId();
            Long bot2UserId = botManagerService.getBot2UserId();
            
            // Bot 1 (매수) 주문 취소
            if (bot1UserId != null) {
                List<Long> bot1OrderIds = new java.util.ArrayList<>(bot1Orders.values());
                for (Long orderId : bot1OrderIds) {
                    try {
                        botService.cancelBotOrder(bot1UserId, orderId);
                    } catch (Exception e) {
                        // 주문이 이미 체결되었거나 취소되었을 수 있으므로 에러는 무시
                        log.debug("[OrderbookSyncService] Bot1 주문 취소 실패 (무시): orderId={}, error={}", 
                                 orderId, e.getMessage());
                    }
                }
                bot1Orders.clear();
            }
            
            // Bot 2 (매도) 주문 취소
            if (bot2UserId != null) {
                List<Long> bot2OrderIds = new java.util.ArrayList<>(bot2Orders.values());
                for (Long orderId : bot2OrderIds) {
                    try {
                        botService.cancelBotOrder(bot2UserId, orderId);
                    } catch (Exception e) {
                        // 주문이 이미 체결되었거나 취소되었을 수 있으므로 에러는 무시
                        log.debug("[OrderbookSyncService] Bot2 주문 취소 실패 (무시): orderId={}, error={}", 
                                 orderId, e.getMessage());
                    }
                }
                bot2Orders.clear();
            }
            
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // 3. 새로운 봇 주문 생성
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // Bot 1 (매수): 바이낸스 매수 호가와 동일한 지정가 매수 주문
            // WebSocket 스레드에서 트랜잭션 사용 시 문제 발생하므로 비동기로 실행
            if (bot1UserId != null) {
                final Long finalBot1UserId = bot1UserId;
                final List<BinanceOrderbookEntry> finalTopBids = new ArrayList<>(topBids);
                final BigDecimal finalOrderQuantity = orderQuantity;
                
                CompletableFuture.runAsync(() -> {
                    for (BinanceOrderbookEntry bid : finalTopBids) {
                        try {
                            OrderResponse response = botService.createLimitBuyOrder(
                                    finalBot1UserId,
                                    "SOL",
                                    bid.getPrice(),
                                    finalOrderQuantity
                            );
                            
                            // 주문 맵에 추가 (동기화 필요)
                            synchronized (bot1Orders) {
                                String priceKey = bid.getPrice().toString();
                                bot1Orders.put(priceKey, Long.parseLong(response.getOrder().getId()));
                            }
                        } catch (Exception e) {
                            // 주문 생성 실패 (잔고 부족 등) - 로그만 출력하고 계속 진행
                            log.warn("[OrderbookSyncService] Bot1 주문 생성 실패: price={}, amount={}, error={}", 
                                    bid.getPrice(), finalOrderQuantity, e.getMessage());
                        }
                    }
                });
            }
            
            // Bot 2 (매도): 바이낸스 매도 호가와 동일한 지정가 매도 주문
            // WebSocket 스레드에서 트랜잭션 사용 시 문제 발생하므로 비동기로 실행
            if (bot2UserId != null) {
                final Long finalBot2UserId = bot2UserId;
                final List<BinanceOrderbookEntry> finalTopAsks = new ArrayList<>(topAsks);
                final BigDecimal finalOrderQuantity = orderQuantity;
                
                CompletableFuture.runAsync(() -> {
                    for (BinanceOrderbookEntry ask : finalTopAsks) {
                        try {
                            OrderResponse response = botService.createLimitSellOrder(
                                    finalBot2UserId,
                                    "SOL",
                                    ask.getPrice(),
                                    finalOrderQuantity
                            );
                            
                            // 주문 맵에 추가 (동기화 필요)
                            synchronized (bot2Orders) {
                                String priceKey = ask.getPrice().toString();
                                bot2Orders.put(priceKey, Long.parseLong(response.getOrder().getId()));
                            }
                        } catch (Exception e) {
                            // 주문 생성 실패 (잔고 부족 등) - 로그만 출력하고 계속 진행
                            log.warn("[OrderbookSyncService] Bot2 주문 생성 실패: price={}, amount={}, error={}", 
                                    ask.getPrice(), finalOrderQuantity, e.getMessage());
                        }
                    }
                });
            }
            
            log.debug("[OrderbookSyncService] 오더북 동기화 완료: bids={}, asks={}", 
                     topBids.size(), topAsks.size());
            
        } catch (Exception e) {
            log.error("[OrderbookSyncService] 오더북 업데이트 처리 실패", e);
        }
    }
}
