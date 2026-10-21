package dustin.cex.domains.bot.model;
import java.util.List;
import lombok.Data;

/** BTC 부분 호가 스냅샷 스트림의 실제 계약만 사용한다. 증분 스트림용 별칭은 제공하지 않는다. */
@Data
public class BinanceOrderbookUpdate {
    private Long lastUpdateId;
    private List<List<String>> bids;
    private List<List<String>> asks;
}
