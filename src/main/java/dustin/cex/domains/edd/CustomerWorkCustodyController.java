package dustin.cex.domains.edd;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 일반 업무 조회와 독립 인계 승인을 분리한다. 원문은 현재 후속 담당자에게만 별도로 제공한다. */
@RestController
@RequestMapping("/api/admin/compliance")
public class CustomerWorkCustodyController {
    private final CustomerWorkCustodyService service;
    public CustomerWorkCustodyController(CustomerWorkCustodyService service){this.service=service;}
    public record Proposal(@NotNull UUID reviewId,@NotBlank @Pattern(regexp="[0-9a-f]{64}") String expectedStamp,@Positive long primaryId,@Positive long checkerId,@NotBlank String reason) {
        @Override public String toString(){return "Proposal[인계 사유 생략]";}
    }
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash) {}
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof) {
        @Override public String toString(){return "Decision[서명 생략]";}
    }
    @GetMapping("/customer-review-orphans") public CustomerWorkCustodyService.Orphans orphans(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(required=false) Instant afterReceived,@RequestParam(required=false) UUID afterRequest){return service.orphans(userId,sessionId,afterReceived==null&&afterRequest==null?null:new CustomerWorkCustodyService.Cursor(afterReceived,afterRequest));}
    @GetMapping("/customer-review-requests/{review}/custody") public CustomerWorkCustodyService.Context context(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID review) {return service.context(userId,sessionId,review);}
    @PostMapping("/customer-custody-requests") public CustomerWorkCustodyService.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal b) {return service.propose(userId,sessionId,b.reviewId(),b.expectedStamp(),b.primaryId(),b.checkerId(),key,b.reason());}
    @PostMapping("/customer-custody-requests/{id}/inspect") public CustomerWorkCustodyService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
    @PostMapping("/customer-custody-requests/{id}/approve/options") public CustomerWorkCustodyService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),true);}
    @PostMapping("/customer-custody-requests/{id}/reject/options") public CustomerWorkCustodyService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),false);}
    @PostMapping("/customer-custody-requests/{id}/approve") public CustomerWorkCustodyService.Request approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());}
    @PostMapping("/customer-custody-requests/{id}/reject") public CustomerWorkCustodyService.Request reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());}
    @GetMapping("/customer-custody-requests/{id}") public CustomerWorkCustodyService.Request status(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.status(userId,sessionId,id);}

}
