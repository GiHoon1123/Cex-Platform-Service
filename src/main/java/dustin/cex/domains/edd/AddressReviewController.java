package dustin.cex.domains.edd;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 개인 이름·주소가 포함된 확인 응답은 최근 인증과 열람 영수증을 확인하고 별도로 감사한다. */
@RestController
@RequestMapping("/api/admin/edd")
public class AddressReviewController {
    private final AddressReviewService service;
    public AddressReviewController(AddressReviewService service) {this.service=service;}
    public record Viewed(@NotNull UUID viewId) {}
    public record Proposal(@NotNull UUID viewId,@NotNull UUID assessmentId,@Positive long caseRevision,@NotNull AddressFacts facts) {
        @Override public String toString() {return "Proposal[개인정보 생략]";}
    }
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull UUID viewId,AddressFacts observed) {
        @Override public String toString() {return "Confirmation[개인정보 생략]";}
    }
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull UUID viewId,AddressFacts observed,@NotNull OperatorMfaService.AuthorizationProof proof) {
        @Override public String toString() {return "Decision[개인정보·서명 생략]";}
    }
    @PostMapping("/evidence/{id}/address-review/context")
    public AddressReviewService.Context context(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Viewed body) {return service.context(userId,sessionId,id,body.viewId());}
    @PostMapping("/evidence/{id}/address-reviews")
    public AddressReviewService.Review propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal body) {
        return service.propose(userId,sessionId,id,body.viewId(),body.assessmentId(),body.caseRevision(),key,body.facts());
    }
    @PostMapping("/address-reviews/{id}/inspect")
    public AddressReviewService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Viewed body) {return service.inspect(userId,sessionId,id,body.viewId());}
    @PostMapping("/address-reviews/{id}/approve/options")
    public AddressReviewService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body) {return service.options(userId,sessionId,id,body.payloadHash(),body.viewId(),true,body.observed());}
    @PostMapping("/address-reviews/{id}/reject/options")
    public AddressReviewService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body) {return service.options(userId,sessionId,id,body.payloadHash(),body.viewId(),false,body.observed());}
    @PostMapping("/address-reviews/{id}/approve")
    public AddressReviewService.Review approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body) {return service.decide(userId,sessionId,id,body.payloadHash(),body.viewId(),true,body.observed(),body.proof());}
    @PostMapping("/address-reviews/{id}/reject")
    public AddressReviewService.Review reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body) {return service.decide(userId,sessionId,id,body.payloadHash(),body.viewId(),false,body.observed(),body.proof());}
    @GetMapping("/customers/{user}/address-reviews")
    public List<AddressReviewService.Review> list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable long user,@RequestParam(defaultValue="0") int page) {return service.list(userId,sessionId,user,page);}
}
