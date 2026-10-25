package dustin.cex.domains.bitcoin;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 심사 근거 조회만 제공한다. 요청 본문으로 고객/관찰/승인 결과를 주입하지 않는다. */
@RestController
@RequestMapping("/api/admin/deposits/BTC")
public class BitcoinDepositReviewController {
    private final BitcoinDepositReviewSource source;
    public BitcoinDepositReviewController(BitcoinDepositReviewSource source){this.source=source;}
    @GetMapping("/{id}") public BitcoinDepositReviewSource.Source read(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return source.read(userId,sessionId,id);}
}
