package dustin.cex.domains.edd;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 개인 이름·자금 정보가 포함된 확인 응답은 최근 인증과 열람 영수증을 확인하고 별도로 감사한다. */
@RestController
@RequestMapping("/api/admin/edd")
public class FundsReviewController {
    private final FundsReviewService service;
    public FundsReviewController(FundsReviewService service) {this.service=service;}
    public record Viewed(@NotNull @Valid FundsReviewService.Views views) {}
    public record ContextRequest(@NotNull UUID originEvidence,@NotNull UUID receiptEvidence,@NotNull @Valid FundsReviewService.Views views,@NotNull @Valid FundsTradeLink.Target target) {}
    public record Proposal(@NotNull UUID originEvidence,@NotNull UUID receiptEvidence,@NotNull @Valid FundsReviewService.Views views,@NotNull UUID assessmentId,@Positive long caseRevision,@NotNull FundsFacts facts,@NotNull @Valid FundsTradeLink.Target target) {
        @Override public String toString() {return "Proposal[개인정보 생략]";}
    }
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull @Valid FundsReviewService.Views views,FundsFacts observed) {
        @Override public String toString() {return "Confirmation[개인정보 생략]";}
    }
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull @Valid FundsReviewService.Views views,FundsFacts observed,@NotNull OperatorMfaService.AuthorizationProof proof) {
        @Override public String toString() {return "Decision[개인정보·서명 생략]";}
    }
    @PostMapping("/funds-reviews/context")
    public FundsReviewService.Context context(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@Valid @RequestBody ContextRequest body) {return service.context(userId,sessionId,body.originEvidence(),body.receiptEvidence(),body.views(),body.target());}
    @PostMapping("/funds-reviews")
    public FundsReviewService.Review propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal body) {
        return service.propose(userId,sessionId,body.originEvidence(),body.receiptEvidence(),body.views(),body.assessmentId(),body.caseRevision(),key,body.facts(),body.target());
    }
    @PostMapping("/funds-reviews/{id}/inspect")
    public FundsReviewService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Viewed body) {return service.inspect(userId,sessionId,id,body.views());}
    @PostMapping("/funds-reviews/{id}/approve/options")
    public FundsReviewService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body) {return service.options(userId,sessionId,id,body.payloadHash(),body.views(),true,body.observed());}
    @PostMapping("/funds-reviews/{id}/reject/options")
    public FundsReviewService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body) {return service.options(userId,sessionId,id,body.payloadHash(),body.views(),false,body.observed());}
    @PostMapping("/funds-reviews/{id}/approve")
    public FundsReviewService.Review approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body) {return service.decide(userId,sessionId,id,body.payloadHash(),body.views(),true,body.observed(),body.proof());}
    @PostMapping("/funds-reviews/{id}/reject")
    public FundsReviewService.Review reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body) {return service.decide(userId,sessionId,id,body.payloadHash(),body.views(),false,body.observed(),body.proof());}
    @GetMapping("/customers/{user}/funds-reviews")
    public List<FundsReviewService.Review> list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable long user,@RequestParam(defaultValue="0") int page) {return service.list(userId,sessionId,user,page);}
}
