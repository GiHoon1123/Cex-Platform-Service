package dustin.cex.domains.bot.service;

import tools.jackson.databind.ObjectMapper;
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
            // 설정된 부분 호가 스트림은 매번 양쪽 상위 호가를 완전히 보낸다.
            // 증분 이벤트로 변환하거나 SOL 심볼을 임의로 넣는 경로를 제거한다.
            var update = objectMapper.readValue(message, BinanceOrderbookUpdate.class);
            if (updateCallback != null) updateCallback.accept(update);
        } catch (Exception error) {
            // 잘못된 메시지를 가격으로 사용하지 않는다. 계속 실패하면 가격 서비스의
            // 마지막 정상 수신 시간이 만료되어 자동 주문이 차단된다.
            log.warn("봇 참조 호가 해독 실패: {}", error.getMessage());
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
        if(update.getLastUpdateId()==null || update.getLastUpdateId()<=0) throw new IllegalArgumentException("잘못된 외부 호가 순번입니다");
        return new List[]{parseSide(update.getBids(),true),parseSide(update.getAsks(),false)};
    }
    private static List<BinanceOrderbookEntry> parseSide(List<List<String>> values,boolean buy) {
        if(values==null || values.isEmpty() || values.size()>20) throw new IllegalArgumentException("잘못된 외부 호가 깊이입니다");
        List<BinanceOrderbookEntry> result=new ArrayList<>();
        java.math.BigDecimal previous=null;
        for(var row:values) {
            if(row.size()!=2) throw new IllegalArgumentException("잘못된 외부 호가 행입니다");
            var price=new java.math.BigDecimal(row.get(0));var quantity=new java.math.BigDecimal(row.get(1));
            if(price.signum()<=0 || quantity.signum()<=0 || (previous!=null && (buy?previous.compareTo(price)<=0:previous.compareTo(price)>=0))) throw new IllegalArgumentException("잘못된 외부 호가 가격·수량입니다");
            result.add(BinanceOrderbookEntry.builder().price(price).quantity(quantity).build());previous=price;
        }
        return result;
    }
}
