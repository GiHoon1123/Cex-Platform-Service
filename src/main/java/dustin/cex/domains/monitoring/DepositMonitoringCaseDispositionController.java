package dustin.cex.domains.monitoring;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 고객에게 조사·보고 내부 상태를 노출하지 않는다. 요청과 실제 실행에 서로 다른 역할 및 별도 내용 결합 인증을 요구한다. */
@RestController
@RequestMapping("/api/admin/monitoring/case-actions")
public class DepositMonitoringCaseDispositionController {
    private final DepositMonitoringCaseDispositions service;
    public DepositMonitoringCaseDispositionController(DepositMonitoringCaseDispositions service){this.service=service;}
    public record Proposal(@NotNull UUID reviewId,@NotNull DepositMonitoringCaseDispositions.Action action){}
    public record Confirmation(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash){}
    public record Decision(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof){@Override public String toString(){return "CaseActionDecision[서명 생략]";}}
    @PostMapping public DepositMonitoringCaseDispositions.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal b){return service.propose(userId,sessionId,b.reviewId(),b.action(),key);}
    @GetMapping("/report-referrals") public List<DepositMonitoringCaseDispositions.Referral> referrals(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(defaultValue="0") int page){return service.referrals(userId,sessionId,page);}
    @PostMapping("/{id}/approve/options") public DepositMonitoringCaseDispositions.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),true);}
    @PostMapping("/{id}/reject/options") public DepositMonitoringCaseDispositions.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),false);}
    @PostMapping("/{id}/approve") public DepositMonitoringCaseDispositions.Request approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());}
    @PostMapping("/{id}/reject") public DepositMonitoringCaseDispositions.Request reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());}
}
