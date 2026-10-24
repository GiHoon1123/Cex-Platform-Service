package dustin.cex.domains.edd;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 일반 업무 조회·고립 복구·독립 인계 승인을 분리한다. 원문은 현재 후속 담당자에게만 별도로 제공한다. */
@RestController
@RequestMapping("/api/admin/compliance")
public class ReportingCustodyController {
    private final ReportingCustodyService service;
    public ReportingCustodyController(ReportingCustodyService service){this.service=service;}
    public record Proposal(@NotNull UUID reviewId,@NotBlank @Pattern(regexp="[0-9a-f]{64}") String expectedStamp,@Positive long primaryId,@Positive long checkerId,@NotBlank String reason) {
        @Override public String toString(){return "Proposal[인계 사유 생략]";}
    }
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash) {}
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof) {
        @Override public String toString(){return "Decision[서명 생략]";}
    }
    @GetMapping("/reporting-orphans") public ReportingCustodyService.Orphans orphans(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestParam(required=false) Instant afterDecision,@RequestParam(required=false) UUID afterReview) {return service.orphans(userId,sessionId,afterDecision==null&&afterReview==null?null:new ReportingCustodyService.Cursor(afterDecision,afterReview));}
    @GetMapping("/reviews/{review}/custody") public ReportingCustodyService.Context context(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID review) {return service.context(userId,sessionId,review);}
    @PostMapping("/custody-requests") public ReportingCustodyService.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal b) {return service.propose(userId,sessionId,b.reviewId(),b.expectedStamp(),b.primaryId(),b.checkerId(),key,b.reason());}
    @PostMapping("/custody-requests/{id}/inspect") public ReportingCustodyService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
    @PostMapping("/custody-requests/{id}/approve/options") public ReportingCustodyService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),true);}
    @PostMapping("/custody-requests/{id}/reject/options") public ReportingCustodyService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),false);}
    @PostMapping("/custody-requests/{id}/approve") public ReportingCustodyService.Request approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());}
    @PostMapping("/custody-requests/{id}/reject") public ReportingCustodyService.Request reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());}
    @PostMapping("/reviews/{review}/custody-context") public ReportingReviewService.Inspection origin(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID review,@RequestParam(defaultValue="0") int page){return service.origin(userId,sessionId,review,page);}
    @GetMapping("/reviews/{review}/custody-history") public List<ReportingCustodyService.Request> history(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID review,@RequestParam(defaultValue="0") int page){return service.history(userId,sessionId,review,page);}
    @PostMapping("/custody-requests/{id}/history") public ReportingCustodyService.Inspection historyDetail(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.historyDetail(userId,sessionId,id);}

    @GetMapping("/custody-requests/{id}") public ReportingCustodyService.Request status(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.status(userId,sessionId,id);}

}
