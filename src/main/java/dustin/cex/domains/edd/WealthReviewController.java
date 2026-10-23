package dustin.cex.domains.edd;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 개인 이름·재산 정보가 포함된 확인 응답은 최근 인증과 열람 영수증을 확인하고 별도로 감사한다. */
@RestController
@RequestMapping("/api/admin/edd")
public class WealthReviewController {
    private final WealthReviewService service;
    public WealthReviewController(WealthReviewService service) {this.service=service;}
    public record Viewed(@NotNull @Valid WealthReviewService.Views views) {}
    public record ContextRequest(@NotNull UUID openingEvidence,@NotNull UUID changesEvidence,@NotNull UUID closingEvidence,@NotNull @Valid WealthReviewService.Views views) {}
    public record Proposal(@NotNull UUID openingEvidence,@NotNull UUID changesEvidence,@NotNull UUID closingEvidence,@NotNull @Valid WealthReviewService.Views views,@NotNull UUID assessmentId,@Positive long caseRevision,@NotNull WealthFacts facts) {
        @Override public String toString() {return "Proposal[개인정보 생략]";}
    }
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull @Valid WealthReviewService.Views views,WealthFacts observed) {
        @Override public String toString() {return "Confirmation[개인정보 생략]";}
    }
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull @Valid WealthReviewService.Views views,WealthFacts observed,@NotNull OperatorMfaService.AuthorizationProof proof) {
        @Override public String toString() {return "Decision[개인정보·서명 생략]";}
    }
    @PostMapping("/wealth-reviews/context")
    public WealthReviewService.Context context(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@Valid @RequestBody ContextRequest body) {return service.context(userId,sessionId,body.openingEvidence(),body.changesEvidence(),body.closingEvidence(),body.views());}
    @PostMapping("/wealth-reviews")
    public WealthReviewService.Review propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal body) {
        return service.propose(userId,sessionId,body.openingEvidence(),body.changesEvidence(),body.closingEvidence(),body.views(),body.assessmentId(),body.caseRevision(),key,body.facts());
    }
    @PostMapping("/wealth-reviews/{id}/inspect")
    public WealthReviewService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Viewed body) {return service.inspect(userId,sessionId,id,body.views());}
    @PostMapping("/wealth-reviews/{id}/approve/options")
    public WealthReviewService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body) {return service.options(userId,sessionId,id,body.payloadHash(),body.views(),true,body.observed());}
    @PostMapping("/wealth-reviews/{id}/reject/options")
    public WealthReviewService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body) {return service.options(userId,sessionId,id,body.payloadHash(),body.views(),false,body.observed());}
    @PostMapping("/wealth-reviews/{id}/approve")
    public WealthReviewService.Review approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body) {return service.decide(userId,sessionId,id,body.payloadHash(),body.views(),true,body.observed(),body.proof());}
    @PostMapping("/wealth-reviews/{id}/reject")
    public WealthReviewService.Review reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body) {return service.decide(userId,sessionId,id,body.payloadHash(),body.views(),false,body.observed(),body.proof());}
    @GetMapping("/customers/{user}/wealth-reviews")
    public List<WealthReviewService.Review> list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable long user,@RequestParam(defaultValue="0") int page) {return service.list(userId,sessionId,user,page);}
}
