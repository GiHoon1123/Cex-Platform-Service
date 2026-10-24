package dustin.cex.domains.edd;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 종료 개시 철회 판단은 별도 운영 경로로만 제공한다. 역할 검사 뒤에도 서비스에서 지정 계정·인물을 다시 확인한다. */
@RestController
@RequestMapping("/api/admin/compliance/exit-rescissions")
public class ExitRescissionController {
    private final ExitRescissionService service;
    public ExitRescissionController(ExitRescissionService service) {this.service=service;}
    public record Proposal(@NotNull UUID exitId,@Positive long checkerId,@NotNull ExitRescissionService.Facts facts) {
        @Override public String toString() {return "Proposal[종료 개시 철회 판단 생략]";}
    }
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash) {}
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof) {
        @Override public String toString() {return "Decision[서명 생략]";}
    }
    @PostMapping public ExitRescissionService.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal b) {return service.propose(userId,sessionId,b.exitId(),b.checkerId(),key,b.facts());}
    @GetMapping public List<ExitRescissionService.Request> list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestParam(defaultValue="0") int page) {return service.list(userId,sessionId,page);}
    @GetMapping("/{id}") public ExitRescissionService.Request status(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id) {return service.status(userId,sessionId,id);}
    @PostMapping("/{id}/inspect") public ExitRescissionService.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@RequestParam(defaultValue="CURRENT") ExitRescissionService.Basis basis,@RequestParam(defaultValue="0") int page) {return service.inspect(userId,sessionId,id,basis,page);}
    @PostMapping("/{id}/approve/options") public ExitRescissionService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Confirmation b) {return service.options(userId,sessionId,id,b.payloadHash(),true);}
    @PostMapping("/{id}/reject/options") public ExitRescissionService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Confirmation b) {return service.options(userId,sessionId,id,b.payloadHash(),false);}
    @PostMapping("/{id}/approve") public ExitRescissionService.Request approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Decision b) {return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());}
    @PostMapping("/{id}/reject") public ExitRescissionService.Request reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id,@Valid @RequestBody Decision b) {return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());}
}
