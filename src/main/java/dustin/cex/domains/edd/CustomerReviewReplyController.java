package dustin.cex.domains.edd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 직원은 근거 기록만 지정한다. 고객에게 보낼 상태나 자유 문구를 입력받지 않는다. */
@RestController
public class CustomerReviewReplyController {
    public record Request(@NotNull UUID latestNoteId) {}
    private final CustomerReviewReplyService service;
    public CustomerReviewReplyController(CustomerReviewReplyService service){this.service=service;}
    @PostMapping("/api/admin/compliance/customer-review-requests/{id}/replies") public CustomerReviewReplyService.Reply record(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Request body){return service.record(userId,sessionId,id,key,body.latestNoteId());}
    @GetMapping("/api/admin/compliance/customer-review-requests/{id}/replies") public List<CustomerReviewReplyService.Reply> assigned(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@RequestParam(defaultValue="0") int page){return service.assigned(userId,sessionId,id,page);}
    @GetMapping("/api/customer/relationship/review-requests/{id}/replies") public List<CustomerReviewReplyService.Reply> own(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@RequestParam(defaultValue="0") int page){return service.own(userId,sessionId,id,page);}
}
