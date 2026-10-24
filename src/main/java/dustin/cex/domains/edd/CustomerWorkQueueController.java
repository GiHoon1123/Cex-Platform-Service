package dustin.cex.domains.edd;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 운영자 개인 목록은 현재 배정만 제공하고 민감 원문과 승인 동작은 기존 목적별 경로로 분리한다. */
@RestController
@RequestMapping("/api/admin/compliance/customer-review-work")
public class CustomerWorkQueueController {
    private final CustomerWorkQueueService service;
    public CustomerWorkQueueController(CustomerWorkQueueService service){this.service=service;}
    @GetMapping
    public CustomerWorkQueueService.Page queue(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestParam(required=false) Instant afterReceived,@RequestParam(required=false) UUID afterRequest) {
        // 커서 한쪽만 있으면 서비스가 거부한다. 입력 누락을 첫 페이지로 우회시키지 않는다.
        return service.queue(userId,sessionId,afterReceived==null&&afterRequest==null?null:new CustomerWorkQueueService.Cursor(afterReceived,afterRequest));
    }
}
