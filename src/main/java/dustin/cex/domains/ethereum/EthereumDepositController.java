package dustin.cex.domains.ethereum;

import java.util.UUID;import org.springframework.web.bind.annotation.*;

/** 고객과 세션 식별자는 검증된 요청 속성만 사용하고 주소나 체인은 입력받지 않는다. */
@RestController @RequestMapping("/api/customer/deposit-addresses/ETH")
public class EthereumDepositController {
    private final EthereumDepositAddressService service;public EthereumDepositController(EthereumDepositAddressService service){this.service=service;}
    @GetMapping public EthereumDepositAddressService.Address own(@RequestAttribute long userId,@RequestAttribute UUID sessionId){return service.own(userId,sessionId);}
    @PostMapping public EthereumDepositAddressService.Address issue(@RequestAttribute long userId,@RequestAttribute UUID sessionId){return service.issue(userId,sessionId);}
}
