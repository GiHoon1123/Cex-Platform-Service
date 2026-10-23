package dustin.cex.domains.edd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 후보 신원·조사 메모 응답은 원문 권한과 필수 감사를 거치며 고객에게 노출하지 않는다. */
@RestController
@RequestMapping("/api/admin/edd")
public class AmlInvestigationController {
    private final AmlInvestigationService service;
    public AmlInvestigationController(AmlInvestigationService service) {this.service=service;}
    public record Proposal(@NotNull UUID viewId,@NotNull UUID assessmentId,@Positive long caseRevision,@Min(-1) int screeningIndex,
            String hitId,@NotNull AmlInvestigationService.Finding finding,@NotBlank @Size(max=2000) String notes) {
        @Override public String toString() {return "Proposal[개인정보 생략]";}
    }
    @GetMapping("/customers/{user}/aml-sources") public List<AmlSource.Metadata> sources(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable long user,@RequestParam(defaultValue="0") int page) {return service.listSources(userId,sessionId,user,page);}
    @PostMapping("/aml-sources/{id}/inspect") public AmlInvestigationService.Viewed inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id) {return service.inspect(userId,sessionId,id);}
    @PostMapping("/aml-sources/{id}/investigations") public AmlInvestigationService.Investigation record(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal p) {
        return service.record(userId,sessionId,id,p.viewId(),p.assessmentId(),p.caseRevision(),key,p.screeningIndex(),p.hitId(),p.finding(),p.notes());
    }
    @PostMapping("/aml-investigations/{id}/inspect") public AmlInvestigationService.Detail detail(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id) {return service.inspectInvestigation(userId,sessionId,id);}
    @GetMapping("/customers/{user}/aml-investigations") public List<AmlInvestigationService.Investigation> list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable long user,@RequestParam(defaultValue="0") int page) {return service.list(userId,sessionId,user,page);}
}
