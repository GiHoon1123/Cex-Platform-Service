package dustin.cex.domains.market;

import jakarta.persistence.*;
import lombok.*;

/** 최신 호가만 보관한다. 금융 잔액의 권위나 엔진 복구 근거로 사용하지 않는다. */
@Entity @Table(name="market_states") @Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class MarketState {
    @Id @Column(length=80) private String pair;
    @Column(nullable=false,length=36) private String engineId;
    @Column(name="engine_sequence",nullable=false) private long sequence;
    @Column(nullable=false,columnDefinition="text") private String payload;
    public MarketState(String pair, String engineId, long sequence, String payload) {
        this.pair=pair; this.engineId=engineId; this.sequence=sequence; this.payload=payload;
    }
}
