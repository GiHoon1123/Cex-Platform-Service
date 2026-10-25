package dustin.cex.domains.monitoring;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 명시한 입금 원본의 탐지만 요청한다. 요청자로부터 금액·판정·고객 식별자를 받지 않는다. */
@RestController
@RequestMapping("/api/admin/deposits/BTC")
public class DepositMonitoringController {
    private final DepositMonitoringRequest service;
    public DepositMonitoringController(DepositMonitoringRequest service){this.service=service;}
    @PostMapping("/{id}/monitoring-requests")
    public DepositMonitoringRequest.Requested request(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.request(userId,sessionId,id);}
}
