package dustin.cex.domains.bot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import dustin.cex.domains.bot.model.BinanceOrderbookEntry;
import dustin.cex.domains.bot.model.BinanceOrderbookUpdate;
import dustin.cex.domains.bot.model.BotConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 바이낸스 가격 서비스
 * Binance Price Service
 * 
 * 역할:
 * - 바이낸스 WebSocket에서 가격 정보 수신
 * - 현재 시장 가격 제공 (매수/매도 최우선 호가)
 * - 중간 가격 (mid price) 계산
 * 
 * 처리 흐름:
 * 1. 바이낸스 WebSocket 연결하여 오더북 업데이트 수신
 * 2. 최우선 매수/매도 호가 저장
 * 3. BotTradingService에서 현재 가격 조회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BinancePriceService {
    
    private final BotConfig botConfig;
    private final BinanceWebSocketClient binanceWebSocketClient;
    
    /**
     * 최우선 매수 호가 (가장 높은 매수 가격)
     * Best bid price (highest buy price)
     */
    private final AtomicReference<BigDecimal> bestBid = new AtomicReference<>(BigDecimal.valueOf(150.0));
    
    /**
     * 최우선 매도 호가 (가장 낮은 매도 가격)
     * Best ask price (lowest sell price)
     */
    private final AtomicReference<BigDecimal> bestAsk = new AtomicReference<>(BigDecimal.valueOf(150.0));
    
    /**
     * 중간 가격 (mid price)
     * Mid price
     */
    private final AtomicReference<BigDecimal> midPrice = new AtomicReference<>(BigDecimal.valueOf(150.0));
    
    /**
     * 서버 시작 시 바이낸스 WebSocket 연결 시작
     * Start Binance WebSocket connection on server startup
     */
    @PostConstruct
    public void start() {
        log.info("[BinancePriceService] 바이낸스 가격 서비스 시작...");
        log.info("  - 바이낸스 WebSocket URL: {}", botConfig.getBinanceWsUrl());
        
        // 바이낸스 WebSocket 연결 시작 (가격 업데이트만 수신)
        binanceWebSocketClient.start(botConfig.getBinanceWsUrl(), this::handleOrderbookUpdate);
        
        log.info("[BinancePriceService] 바이낸스 가격 서비스 시작 완료");
    }
    
    /**
     * 서버 종료 시 바이낸스 WebSocket 연결 종료
     * Stop Binance WebSocket connection on server shutdown
     */
    @PreDestroy
    public void stop() {
        binanceWebSocketClient.stop();
    }
    
    /**
     * 오더북 업데이트 처리 (WebSocket 콜백)
     * Handle orderbook update (WebSocket callback)
     * 
     * 바이낸스 WebSocket에서 오더북 업데이트를 받아 최우선 호가를 업데이트합니다.
     * 
     * @param update 바이낸스 오더북 업데이트
     */
    private void handleOrderbookUpdate(BinanceOrderbookUpdate update) {
        try {
            // 오더북 파싱
            List<BinanceOrderbookEntry>[] parsed = BinanceWebSocketClient.parseOrderbookUpdate(update);
            List<BinanceOrderbookEntry> bids = parsed[0];
            List<BinanceOrderbookEntry> asks = parsed[1];
            
            // 최우선 호가 업데이트
            updateOrderbook(bids, asks);
        } catch (Exception e) {
            log.error("[BinancePriceService] 오더북 업데이트 처리 실패", e);
        }
    }
    
    /**
     * 오더북 업데이트 처리
     * Handle orderbook update
     * 
     * 최우선 호가를 업데이트합니다.
     * 
     * @param bids 매수 호가 목록
     * @param asks 매도 호가 목록
     */
    private void updateOrderbook(List<BinanceOrderbookEntry> bids, List<BinanceOrderbookEntry> asks) {
        try {
            // 최우선 매수 호가 (가장 높은 가격)
            if (bids != null && !bids.isEmpty()) {
                BigDecimal newBestBid = bids.get(0).getPrice();
                bestBid.set(newBestBid);
            }
            
            // 최우선 매도 호가 (가장 낮은 가격)
            if (asks != null && !asks.isEmpty()) {
                BigDecimal newBestAsk = asks.get(0).getPrice();
                bestAsk.set(newBestAsk);
            }
            
            // 중간 가격 계산
            BigDecimal newMidPrice = bestBid.get().add(bestAsk.get())
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            midPrice.set(newMidPrice);
            
        } catch (Exception e) {
            log.error("[BinancePriceService] 오더북 업데이트 실패", e);
        }
    }
    
    /**
     * 최우선 매수 호가 가져오기
     * Get best bid price
     * 
     * @return 최우선 매수 호가 (없으면 기본값 150.0)
     */
    public BigDecimal getBestBid() {
        return bestBid.get();
    }
    
    /**
     * 최우선 매도 호가 가져오기
     * Get best ask price
     * 
     * @return 최우선 매도 호가 (없으면 기본값 150.0)
     */
    public BigDecimal getBestAsk() {
        return bestAsk.get();
    }
    
    /**
     * 중간 가격 가져오기
     * Get mid price
     * 
     * @return 중간 가격 (없으면 기본값 150.0)
     */
    public BigDecimal getMidPrice() {
        return midPrice.get();
    }
    
    /**
     * 현재 시장 가격 가져오기 (거래용)
     * Get current market price (for trading)
     * 
     * 매수 봇은 bestAsk 사용, 매도 봇은 bestBid 사용
     * 
     * @param isBuyBot 매수 봇 여부
     * @return 현재 시장 가격
     */
    public BigDecimal getMarketPrice(boolean isBuyBot) {
        if (isBuyBot) {
            // 매수 봇: 매도 호가 사용 (즉시 체결 가능한 가격)
            return bestAsk.get();
        } else {
            // 매도 봇: 매수 호가 사용 (즉시 체결 가능한 가격)
            return bestBid.get();
        }
    }
}
