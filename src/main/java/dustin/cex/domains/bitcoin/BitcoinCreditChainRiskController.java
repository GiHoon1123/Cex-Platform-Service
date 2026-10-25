package dustin.cex.domains.bitcoin;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 현재 운영 인증 아래 위험 입금의 최소 목록만 제공한다. 제한 해제 명령을 받지 않는다. */
@RestController
@RequestMapping("/api/admin/deposits/BTC/chain-risks")
public class BitcoinCreditChainRiskController {
    private final BitcoinCreditChainRiskReview review;
    public BitcoinCreditChainRiskController(BitcoinCreditChainRiskReview review){this.review=review;}
    @GetMapping public BitcoinCreditChainRiskReview.Page read(@RequestAttribute long userId,
        @RequestAttribute UUID sessionId,@RequestParam(defaultValue="0") int page){return review.read(userId,sessionId,page);}
}
