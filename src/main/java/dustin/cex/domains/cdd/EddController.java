package dustin.cex.domains.cdd;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 고객 신원 원문 대신 실제 평가와 검토 사유를 보여준다. 요청의 행위자는 현재 인증 세션에서만 가져온다. */
@RestController
@RequestMapping("/api/admin/edd")
public class EddController {
    private final EddService service;
    public EddController(EddService service) {this.service=service;}
    public record Proposal(@NotNull UUID assessmentId,@NotNull EddService.Finding finding,Instant validUntil) {}
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash) {}
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof) {
        @Override public String toString() {return "Decision[서명 생략]";}
    }
    @PostMapping("/customers/{user}/reports") public EddService.Report propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable long user,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal b) {
        return service.propose(userId,sessionId,user,b.assessmentId(),key,b.finding(),b.validUntil());
    }
    @PostMapping("/reports/{id}/inspect") public EddService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@RequestParam(defaultValue="0") int page) {return service.inspect(userId,sessionId,id,page);}
    @GetMapping("/reports/{id}/dossier") public EddService.Inspection dossier(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@RequestParam(defaultValue="0") int page) {return service.dossier(userId,sessionId,id,page);}
    @PostMapping("/reports/{id}/approve/options") public EddService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Confirmation b) {return service.options(userId,sessionId,id,b.payloadHash(),true);}
    @PostMapping("/reports/{id}/reject/options") public EddService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Confirmation b) {return service.options(userId,sessionId,id,b.payloadHash(),false);}
    @PostMapping("/reports/{id}/approve") public EddService.Report approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Decision b) {return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());}
    @PostMapping("/reports/{id}/reject") public EddService.Report reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Decision b) {return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());}
    @GetMapping("/customers/{user}") public EddService.Case status(@PathVariable long user) {return service.status(user);}
    @GetMapping("/customers/{user}/reports") public List<EddService.Report> reports(@PathVariable long user,@RequestParam(defaultValue="0") int page) {return service.reports(user,page);}
}
