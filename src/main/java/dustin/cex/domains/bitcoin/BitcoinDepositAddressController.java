package dustin.cex.domains.bitcoin;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 고객 식별자는 검증된 세션에서만 받는다. 자산/체인/노드/타인의 고객 번호를 입력받지 않는다. */
@RestController
@RequestMapping("/api/customer/deposit-addresses/BTC")
public class BitcoinDepositAddressController {
    private final BitcoinDepositAddressService addresses;
    public BitcoinDepositAddressController(BitcoinDepositAddressService addresses){this.addresses=addresses;}
    @GetMapping public BitcoinDepositAddressService.Address own(@RequestAttribute long userId,@RequestAttribute UUID sessionId){return addresses.own(userId,sessionId);}
    @PostMapping public BitcoinDepositAddressService.Address issue(@RequestAttribute long userId,@RequestAttribute UUID sessionId){return addresses.issue(userId,sessionId);}
}
