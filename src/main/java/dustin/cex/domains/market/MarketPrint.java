package dustin.cex.domains.market;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

/** 차트와 최근 체결의 공개 이력이다. 원장 정산 완료와는 별도 상태다. */
@Entity @Table(name="market_prints",indexes=@Index(name="market_print_pair_time",columnList="pair,timestamp"))
@Getter @NoArgsConstructor(access=AccessLevel.PROTECTED)
public class MarketPrint {
    @Id @Column(length=120) private String id;
    @Column(nullable=false,length=80) private String pair;
    @Column(nullable=false,precision=30,scale=9) private BigDecimal price;
    @Column(nullable=false,precision=30,scale=9) private BigDecimal amount;
    @Column(nullable=false,length=4) private String side;
    @Column(nullable=false) private Instant timestamp;
    @Column(name="engine_sequence",nullable=false) private long sequence;
    @Column(nullable=false) private int tradeIndex;
    public MarketPrint(String pair, MarketSnapshot.Print print, Instant timestamp) {
        this.id=print.id(); this.sequence=Long.parseLong(print.id().split(":")[1]);
        this.tradeIndex=Integer.parseInt(print.id().split(":")[2]); this.pair=pair; this.price=new BigDecimal(print.price());
        this.amount=new BigDecimal(print.amount()); this.side=print.side(); this.timestamp=timestamp;
    }
}
