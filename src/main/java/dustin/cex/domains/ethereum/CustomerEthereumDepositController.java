package dustin.cex.domains.ethereum;

import java.util.*;import org.springframework.web.bind.annotation.*;

/** 확정·반영 상태를 본인 세션 범위에서만 조회한다. */
@RestController @RequestMapping("/api/customer/deposits/ETH")
public class CustomerEthereumDepositController {
    private final EthereumDepositService service;public CustomerEthereumDepositController(EthereumDepositService service){this.service=service;}
    @GetMapping public List<EthereumDepositService.Deposit> own(@RequestAttribute long userId,@RequestAttribute UUID sessionId){return service.own(userId,sessionId);}
}
