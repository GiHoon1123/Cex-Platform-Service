package dustin.cex.domains.edd;

import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 비공개 처리 기록은 고객용 응답 경로와 분리한다. 일반 운영 역할만으로 원문을 읽지 못한다. */
@RestController
@RequestMapping("/api/admin/compliance")
public class CustomerReviewNoteController {
    private final CustomerReviewNoteService service;
    public CustomerReviewNoteController(CustomerReviewNoteService service){this.service=service;}
    @PostMapping("/customer-review-requests/{id}/notes") public CustomerReviewNoteService.Note record(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@RequestBody CustomerReviewNoteService.Facts body){return service.record(userId,sessionId,id,key,body);}
    @GetMapping("/customer-review-requests/{id}/notes") public List<CustomerReviewNoteService.Note> list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@RequestParam(defaultValue="0") int page){return service.list(userId,sessionId,id,page);}
    @PostMapping("/customer-review-notes/{id}/inspect") public CustomerReviewNoteService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
}
