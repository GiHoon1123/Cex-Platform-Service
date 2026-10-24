package dustin.cex.domains.edd;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 원문 없는 목록만 제공한다. 불완전한 커서도 서비스로 넘겨 입력 오류로 거부한다. */
@RestController
@RequestMapping("/api/admin/compliance")
public class CustomerReviewSupervisionController {
    private final CustomerReviewSupervisionService service;
    public CustomerReviewSupervisionController(CustomerReviewSupervisionService service){this.service=service;}
    @GetMapping("/customer-review-supervision") public CustomerReviewSupervisionService.Page queue(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestParam(required=false) Instant afterReceived,@RequestParam(required=false) UUID afterRequest) {
        return service.queue(userId,sessionId,afterReceived==null&&afterRequest==null?null:new CustomerReviewSupervisionService.Cursor(afterReceived,afterRequest));
    }
    @GetMapping("/customer-review-attention")
    public CustomerReviewSupervisionService.Page attention(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(required=false) Instant afterReceived,@RequestParam(required=false) UUID afterRequest){return service.attention(userId,sessionId,afterReceived==null&&afterRequest==null?null:new CustomerReviewSupervisionService.Cursor(afterReceived,afterRequest));}
}
