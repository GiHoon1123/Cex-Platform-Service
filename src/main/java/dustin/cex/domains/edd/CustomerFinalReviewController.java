package dustin.cex.domains.edd;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 고객 공개 예정 문구는 작성·검토 경로에서만 받고 승인 전 고객 조회로 노출하지 않는다. */
@RestController
@RequestMapping("/api/admin/compliance")
public class CustomerFinalReviewController {
    public record Proposal(@NotNull UUID latestNoteId,@NotNull @Valid CustomerFinalReviewService.Explanation explanation) {
        @Override public String toString(){return "Proposal[공개 예정 문구 생략]";}
    }
    private final CustomerFinalReviewService service;
    public CustomerFinalReviewController(CustomerFinalReviewService service){this.service=service;}
    @PostMapping("/customer-review-requests/{review}/final-proposals")
    public CustomerFinalReviewService.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID review,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal p){return service.propose(userId,sessionId,review,key,p.latestNoteId(),p.explanation());}
    @PostMapping("/customer-final-requests/{id}/inspect")
    public CustomerFinalReviewService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash) {}
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull dustin.cex.domains.security.OperatorMfaService.AuthorizationProof proof){@Override public String toString(){return "Decision[서명 생략]";}}
    @PostMapping("/customer-final-requests/{id}/approve/options")
    public CustomerFinalReviewService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),true);}
    @PostMapping("/customer-final-requests/{id}/reject/options")
    public CustomerFinalReviewService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),false);}
    @PostMapping("/customer-final-requests/{id}/approve")
    public CustomerFinalReviewService.Request approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());}
    @PostMapping("/customer-final-requests/{id}/reject")
    public CustomerFinalReviewService.Request reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());}
    @GetMapping("/customer-final-requests/{id}")
    public CustomerFinalReviewService.Request status(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.status(userId,sessionId,id);}
}
