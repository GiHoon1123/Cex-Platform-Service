package dustin.cex.domains.bot.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * 바이낸스 오더북 업데이트 메시지
 * Binance Orderbook Update Message
 * 
 * 역할:
 * - 바이낸스 WebSocket에서 받는 depth stream 형식
 * - JSON 파싱을 위한 DTO
 * 
 * 참고:
 * - 바이낸스 depth stream은 항상 `e` 필드를 포함하지만,
 * - 초기 스냅샷이나 다른 형식의 메시지가 올 수 있으므로
 * - `e` 필드를 확인하여 "depthUpdate" 이벤트만 처리합니다.
 */
@Data
public class BinanceOrderbookUpdate {
    
    /**
     * 이벤트 타입 (항상 "depthUpdate")
     * Event Type
     * 
     * 초기 스냅샷 등에서는 없을 수 있음
     */
    @JsonProperty("e")
    private String eventType;
    
    /**
     * 이벤트 시간 (밀리초)
     * Event Time
     */
    @JsonProperty("E")
    private Long eventTime;
    
    /**
     * 심볼 (예: "SOLUSDT")
     * Symbol
     */
    @JsonProperty("s")
    private String symbol;
    
    /**
     * 첫 번째 업데이트 ID
     * First Update ID
     */
    @JsonProperty("U")
    private Long firstUpdateId;
    
    /**
     * 마지막 업데이트 ID
     * Last Update ID
     * 
     * 초기 스냅샷에서는 `lastUpdateId`로 올 수 있음
     */
    @JsonProperty("u")
    private Long lastUpdateId;
    
    @JsonProperty("lastUpdateId")
    private Long lastUpdateIdAlt;
    
    /**
     * 마지막 업데이트 ID 가져오기 (u 또는 lastUpdateId 지원)
     */
    public Long getLastUpdateId() {
        return lastUpdateId != null ? lastUpdateId : lastUpdateIdAlt;
    }
    
    /**
     * 매수 호가 (가격, 수량 쌍)
     * Bids: [[price, quantity], ...]
     * 
     * 주의: Binance는 List<List<String>> 형식을 사용합니다 (고정 배열이 아님)
     * 실제 JSON에서는 "bids"로 나오지만, depthUpdate 이벤트에서는 "b"로 나옵니다.
     * 두 가지 모두 지원하기 위해 getter에서 처리
     */
    @JsonProperty("bids")
    private List<List<String>> bids;
    
    @JsonProperty("b")
    private List<List<String>> bidsShort;
    
    /**
     * 매도 호가 (가격, 수량 쌍)
     * Asks: [[price, quantity], ...]
     * 
     * 주의: Binance는 List<List<String>> 형식을 사용합니다 (고정 배열이 아님)
     * 실제 JSON에서는 "asks"로 나오지만, depthUpdate 이벤트에서는 "a"로 나옵니다.
     * 두 가지 모두 지원하기 위해 getter에서 처리
     */
    @JsonProperty("asks")
    private List<List<String>> asks;
    
    @JsonProperty("a")
    private List<List<String>> asksShort;
    
    /**
     * 매수 호가 가져오기 (bids 또는 b 지원)
     */
    public List<List<String>> getBids() {
        return bids != null ? bids : bidsShort;
    }
    
    /**
     * 매도 호가 가져오기 (asks 또는 a 지원)
     */
    public List<List<String>> getAsks() {
        return asks != null ? asks : asksShort;
    }
}
