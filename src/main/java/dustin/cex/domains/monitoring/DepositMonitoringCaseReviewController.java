package dustin.cex.domains.monitoring;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 지정한 두 인물에게만 비공개 판단을 제공한다. 공개 사건 목록과 달리 역할만으로 다른 사람의 사유를 읽을 수 없다. */
@RestController
@RequestMapping("/api/admin/monitoring/case-reviews")
public class DepositMonitoringCaseReviewController {
    private final DepositMonitoringCaseReviews service;
    public DepositMonitoringCaseReviewController(DepositMonitoringCaseReviews service){this.service=service;}
    public record Proposal(@NotNull UUID caseId,@Positive long checkerId,@NotNull CaseReviewEvidence.Position expected,@NotNull DepositMonitoringCaseReviews.Facts facts){@Override public String toString(){return "CaseReviewProposal[비공개 판단 생략]";}}
    public record Confirmation(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash){}
    public record Decision(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof){@Override public String toString(){return "CaseReviewDecision[서명 생략]";}}
    @GetMapping("/context/{id}") public CaseReviewEvidence.Position context(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@RequestParam(required=false) Long through){return service.context(userId,sessionId,id,through);}
    @PostMapping public DepositMonitoringCaseReviews.Review propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal b){return service.propose(userId,sessionId,b.caseId(),b.checkerId(),key,b.expected(),b.facts());}
    @GetMapping public List<DepositMonitoringCaseReviews.Review> list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(defaultValue="0") int page){return service.list(userId,sessionId,page);}
    @PostMapping("/{id}/inspect") public DepositMonitoringCaseReviews.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
    @PostMapping("/{id}/approve/options") public DepositMonitoringCaseReviews.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),true);}
    @PostMapping("/{id}/reject/options") public DepositMonitoringCaseReviews.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),false);}
    @PostMapping("/{id}/approve") public DepositMonitoringCaseReviews.Review approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());}
    @PostMapping("/{id}/reject") public DepositMonitoringCaseReviews.Review reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());}
}
