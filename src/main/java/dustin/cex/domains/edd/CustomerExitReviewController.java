package dustin.cex.domains.edd;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 고객 접수와 지정 담당자 열람의 경로를 나눈다. 고객 입력으로 내부 종료 번호나 담당자를 지정하지 않는다. */
@RestController
public class CustomerExitReviewController {
    private final CustomerExitReviewService service;
    public CustomerExitReviewController(CustomerExitReviewService service){this.service=service;}
    @PostMapping("/api/customer/relationship/review-requests")
    public CustomerExitReviewService.Receipt submit(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody CustomerExitReviewService.Submission body){return service.submit(userId,sessionId,key,body);}
    @GetMapping("/api/customer/relationship/review-requests")
    public List<CustomerExitReviewService.Receipt> ownList(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(defaultValue="0") int page){return service.ownList(userId,sessionId,page);}
    @GetMapping("/api/customer/relationship/review-requests/{id}")
    public CustomerExitReviewService.Receipt own(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.own(userId,sessionId,id);}
    @GetMapping("/api/admin/compliance/relationship-exits/{exit}/review-requests")
    public List<CustomerExitReviewService.Receipt> assigned(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID exit,@RequestParam(defaultValue="0") int page){return service.assignedList(userId,sessionId,exit,page);}
    @PostMapping("/api/admin/compliance/customer-review-requests/{id}/inspect")
    public CustomerExitReviewService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
}
