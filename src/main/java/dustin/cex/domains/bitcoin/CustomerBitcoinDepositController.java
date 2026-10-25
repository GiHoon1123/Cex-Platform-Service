package dustin.cex.domains.bitcoin;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 고객 번호/노드 주소는 입력받지 않는다. 본인 식별자는 검증된 세션에서만 전달한다. */
@RestController
@RequestMapping("/api/customer/deposits/BTC")
public class CustomerBitcoinDepositController {
    private final CustomerBitcoinDeposits deposits;
    public CustomerBitcoinDepositController(CustomerBitcoinDeposits deposits){this.deposits=deposits;}
    @GetMapping public CustomerBitcoinDeposits.Page own(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
        @RequestParam(required=false) Instant afterAt,@RequestParam(required=false) UUID afterId){
        return deposits.own(userId,sessionId,afterAt==null&&afterId==null?null:new CustomerBitcoinDeposits.Cursor(afterAt,afterId));
    }
    @GetMapping("/{id}") public CustomerBitcoinDeposits.Deposit detail(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return deposits.detail(userId,sessionId,id);}
}
