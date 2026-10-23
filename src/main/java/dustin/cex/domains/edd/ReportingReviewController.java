package dustin.cex.domains.edd;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 비공개 판단은 별도 운영 경로로만 제공한다. 역할 검사 뒤에도 서비스에서 지정 계정·인물을 다시 확인한다. */
@RestController
@RequestMapping("/api/admin/compliance/reviews")
public class ReportingReviewController {
    private final ReportingReviewService service;
    public ReportingReviewController(ReportingReviewService service) {this.service=service;}
    public record Proposal(@NotNull UUID referralId,@Positive long checkerId,@NotNull ReportingReviewService.Facts facts) {
        @Override public String toString() {return "Proposal[비공개 판단 생략]";}
    }
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash) {}
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof) {
        @Override public String toString() {return "Decision[서명 생략]";}
    }
    @PostMapping public ReportingReviewService.Review propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal b) {return service.propose(userId,sessionId,b.referralId(),b.checkerId(),key,b.facts());}
    @GetMapping public List<ReportingReviewService.Review> list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestParam(defaultValue="0") int page) {return service.list(userId,sessionId,page);}
    @PostMapping("/{id}/inspect") public ReportingReviewService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@RequestParam(defaultValue="0") int page) {return service.inspect(userId,sessionId,id,page);}
    @PostMapping("/{id}/approve/options") public ReportingReviewService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Confirmation b) {return service.options(userId,sessionId,id,b.payloadHash(),true);}
    @PostMapping("/{id}/reject/options") public ReportingReviewService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Confirmation b) {return service.options(userId,sessionId,id,b.payloadHash(),false);}
    @PostMapping("/{id}/approve") public ReportingReviewService.Review approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Decision b) {return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());}
    @PostMapping("/{id}/reject") public ReportingReviewService.Review reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Decision b) {return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());}
}
