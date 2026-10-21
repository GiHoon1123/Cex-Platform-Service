package dustin.cex.domains.market;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface MarketPrintRepository extends JpaRepository<MarketPrint,String> {
    List<MarketPrint> findTop100ByPairOrderByTimestampDescSequenceDescTradeIndexDesc(String pair);
    List<MarketPrint> findTop5000ByPairOrderByTimestampDescSequenceDescTradeIndexDesc(String pair);
}
