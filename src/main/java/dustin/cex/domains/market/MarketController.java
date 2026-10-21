package dustin.cex.domains.market;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 공개 호가·최근 체결·차트의 진입점은 플랫폼 한 곳이다. */
@RestController @RequestMapping("/api/market") @RequiredArgsConstructor
public class MarketController {
    private final MarketService service;
    @GetMapping("/{base}/{quote}")
    public MarketService.View snapshot(@PathVariable String base,@PathVariable String quote) throws Exception {
        return service.view(base,quote);
    }
    @GetMapping("/{base}/{quote}/candles")
    public List<MarketService.Candle> candles(@PathVariable String base,@PathVariable String quote) {
        return service.candles(base,quote);
    }
}
