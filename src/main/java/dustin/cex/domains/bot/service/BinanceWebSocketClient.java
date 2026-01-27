package dustin.cex.domains.bot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dustin.cex.domains.bot.model.BinanceOrderbookEntry;
import dustin.cex.domains.bot.model.BinanceOrderbookUpdate;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * 바이낸스 WebSocket 클라이언트
 * Binance WebSocket Client
 * 
 * 역할:
 * - 바이낸스 depth stream WebSocket 연결
 * - 오더북 업데이트 수신 및 파싱
 * - 업데이트를 콜백으로 전달
 * 
 * 처리 흐름:
 * 1. 바이낸스 WebSocket 연결
 * 2. 오더북 업데이트 수신
 * 3. JSON 파싱
 * 4. 콜백으로 전달
 * 
 * 재연결:
 * - 연결이 끊어지면 자동으로 재연결 시도
 * - 5초 대기 후 재연결
 */
@Slf4j
@Component
public class BinanceWebSocketClient {
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocketClient webSocketClient;
    private String wsUrl;
    private Consumer<BinanceOrderbookUpdate> updateCallback;
    private volatile boolean isRunning = false;
    
    /**
     * 바이낸스 WebSocket 연결 시작
     * Start Binance WebSocket connection
     * 
     * 백그라운드에서 WebSocket 연결을 유지하고,
     * 오더북 업데이트를 콜백으로 전달합니다.
     * 
     * @param wsUrl 바이낸스 WebSocket URL
     * @param callback 오더북 업데이트 콜백 함수
     * 
     * 처리 과정:
     * 1. WebSocket URL 파싱
     * 2. WebSocket 연결
     * 3. 메시지 수신 루프
     * 4. JSON 파싱 및 콜백 호출
     */
    public void start(String wsUrl, Consumer<BinanceOrderbookUpdate> callback) {
        this.wsUrl = wsUrl;
        this.updateCallback = callback;
        this.isRunning = true;
        
        connect();
    }
    
    /**
     * WebSocket 연결
     * Connect to WebSocket
     */
    private void connect() {
        try {
            URI serverUri = URI.create(wsUrl);
            
            webSocketClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.info("[BinanceWebSocketClient] WebSocket 연결 성공: {}", wsUrl);
                }
                
                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }
                
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("[BinanceWebSocketClient] WebSocket 연결 종료: code={}, reason={}, remote={}", 
                             code, reason, remote);
                    
                    // 재연결 시도
                    if (isRunning) {
                        log.info("[BinanceWebSocketClient] 5초 후 재연결 시도...");
                        try {
                            Thread.sleep(5000);
                            connect();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.error("[BinanceWebSocketClient] 재연결 대기 중 인터럽트", e);
                        }
                    }
                }
                
                @Override
                public void onError(Exception ex) {
                    log.error("[BinanceWebSocketClient] WebSocket 오류", ex);
                }
            };
            
            // 연결 시작
            webSocketClient.connect();
            
        } catch (Exception e) {
            log.error("[BinanceWebSocketClient] WebSocket 연결 실패", e);
            
            // 재연결 시도
            if (isRunning) {
                try {
                    Thread.sleep(5000);
                    connect();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("[BinanceWebSocketClient] 재연결 대기 중 인터럽트", ie);
                }
            }
        }
    }
    
    /**
     * 메시지 처리
     * Handle WebSocket message
     * 
     * @param message WebSocket 메시지 (JSON 문자열)
     */
    private void handleMessage(String message) {
        try {
            // JSON 파싱
            BinanceOrderbookUpdate update = objectMapper.readValue(message, BinanceOrderbookUpdate.class);
            
            // "depthUpdate" 이벤트만 처리 (초기 스냅샷 등은 무시)
            int bidsCount = update.getBids() != null ? update.getBids().size() : 0;
            int asksCount = update.getAsks() != null ? update.getAsks().size() : 0;
            
            if ("depthUpdate".equals(update.getEventType())) {
                // bids나 asks가 있어야 처리
                if (bidsCount > 0 || asksCount > 0) {
                    // 콜백으로 전달
                    if (updateCallback != null) {
                        updateCallback.accept(update);
                    }
                }
                // bids/asks가 비어있으면 무시 (정상 동작)
            } else if (update.getEventType() == null) {
                // event_type이 없으면 초기 스냅샷이거나 다른 형식
                // bids/asks가 있으면 처리 (초기 스냅샷)
                if (bidsCount > 0 || asksCount > 0) {
                    // 초기 스냅샷도 처리 (depthUpdate로 변환)
                    update.setEventType("depthUpdate");
                    update.setEventTime(System.currentTimeMillis());
                    update.setSymbol("SOLUSDT");
                    
                    // 콜백으로 전달
                    if (updateCallback != null) {
                        updateCallback.accept(update);
                    }
                }
                // bids/asks가 비어있으면 무시 (정상 동작)
            }
            
        } catch (Exception e) {
            // 파싱 실패는 무시 (다른 형식의 메시지일 수 있음)
            log.debug("[BinanceWebSocketClient] 메시지 파싱 실패 (무시): {}", e.getMessage());
        }
    }
    
    /**
     * WebSocket 연결 종료
     * Stop WebSocket connection
     */
    public void stop() {
        isRunning = false;
        if (webSocketClient != null) {
            webSocketClient.close();
        }
    }
    
    /**
     * 바이낸스 오더북 업데이트를 파싱된 엔트리로 변환
     * Parse Binance orderbook update to entries
     * 
     * @param update 바이낸스 오더북 업데이트
     * @return 파싱된 매수/매도 호가 (bids, asks) - 배열의 첫 번째 요소가 bids, 두 번째가 asks
     */
    @SuppressWarnings("unchecked")
    public static List<BinanceOrderbookEntry>[] parseOrderbookUpdate(BinanceOrderbookUpdate update) {
        List<BinanceOrderbookEntry> bids = new ArrayList<>();
        List<BinanceOrderbookEntry> asks = new ArrayList<>();
        
        // 매수 호가 파싱
        if (update.getBids() != null) {
            for (List<String> entry : update.getBids()) {
                if (entry.size() >= 2) {
                    try {
                        java.math.BigDecimal price = new java.math.BigDecimal(entry.get(0));
                        java.math.BigDecimal quantity = new java.math.BigDecimal(entry.get(1));
                        bids.add(BinanceOrderbookEntry.builder()
                                .price(price)
                                .quantity(quantity)
                                .build());
                    } catch (NumberFormatException e) {
                        // 파싱 실패는 무시
                    }
                }
            }
        }
        
        // 매도 호가 파싱
        if (update.getAsks() != null) {
            for (List<String> entry : update.getAsks()) {
                if (entry.size() >= 2) {
                    try {
                        java.math.BigDecimal price = new java.math.BigDecimal(entry.get(0));
                        java.math.BigDecimal quantity = new java.math.BigDecimal(entry.get(1));
                        asks.add(BinanceOrderbookEntry.builder()
                                .price(price)
                                .quantity(quantity)
                                .build());
                    } catch (NumberFormatException e) {
                        // 파싱 실패는 무시
                    }
                }
            }
        }
        
        // 배열로 반환 (bids, asks)
        List<BinanceOrderbookEntry>[] result = new List[2];
        result[0] = bids;
        result[1] = asks;
        return result;
    }
}
