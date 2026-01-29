package dustin.cex.domains.bot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import dustin.cex.domains.bot.model.BinanceOrderbookEntry;
import lombok.extern.slf4j.Slf4j;

/**
 * 바이낸스 가격 서비스
 * Binance Price Service
 * 
 * 역할:
 * - 바이낸스 오더북 데이터를 저장하고 공유
 * - 현재 시장 가격 제공 (매수/매도 최우선 호가)
 * - 중간 가격 (mid price) 계산
 * 
 * 처리 흐름:
 * 1. OrderbookSyncService에서 오더북 업데이트 수신
 * 2. 최우선 매수/매도 호가 저장
 * 3. BotTradingService에서 현재 가격 조회
 */
@Slf4j
@Service
public class BinancePriceService {
    
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
     * 오더북 업데이트 처리
     * Handle orderbook update
     * 
     * OrderbookSyncService에서 호출하여 최우선 호가를 업데이트합니다.
     * 
     * @param bids 매수 호가 목록
     * @param asks 매도 호가 목록
     */
    public void updateOrderbook(List<BinanceOrderbookEntry> bids, List<BinanceOrderbookEntry> asks) {
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
