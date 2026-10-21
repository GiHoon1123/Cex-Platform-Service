package dustin.cex.domains.market;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** 공개 데이터에는 사용자·잔액·개별 미체결 주문 식별자를 포함하지 않는다. */
public record MarketSnapshot(
    @JsonProperty("event_type") String eventType,
    @JsonProperty("event_id") String eventId,
    @JsonProperty("engine_id") String engineId,
    String sequence,
    @JsonProperty("previous_sequence") String previousSequence,
    @JsonProperty("base_mint") String baseMint,
    @JsonProperty("quote_mint") String quoteMint,
    Instant timestamp,
    List<List<String>> bids,
    List<List<String>> asks,
    List<Print> trades
) {
    public record Print(String id, String price, String amount, String side) {}
}
