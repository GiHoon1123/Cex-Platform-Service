package dustin.cex.domains.market;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 공개 호가·최근 체결·차트의 진입점은 플랫폼 한 곳이다. */
@RestController @RequestMapping("/api/market") @RequiredArgsConstructor
public class MarketController {
    private final MarketService service;
    private final BinanceReferenceCandleService referenceCandles;
    @GetMapping("/{base}/{quote}")
    public MarketService.View snapshot(@PathVariable String base,@PathVariable String quote) throws Exception {
        return service.view(base,quote);
    }
    @GetMapping("/{base}/{quote}/reference-candles")
    public BinanceReferenceCandleService.ReferenceCandles referenceCandles(@PathVariable String base,@PathVariable String quote,@RequestParam String interval) {
        return referenceCandles.candles(base,quote,interval);
    }
}
