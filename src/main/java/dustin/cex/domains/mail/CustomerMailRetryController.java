package dustin.cex.domains.mail;
import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 원문을 입력받지 않고 기존 작업에 결합된 제한적 재시도만 독립 승인한다. */
@RestController
@RequestMapping("/api/admin/operations")
public class CustomerMailRetryController {
    public record Proposal(@Positive long checkerId) {}
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash) {}
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof){@Override public String toString(){return "Decision[서명 생략]";}}
    private final CustomerMailRetryService service;
    public CustomerMailRetryController(CustomerMailRetryService service){this.service=service;}
    @PostMapping("/customer-mail/{job}/retry-requests") public CustomerMailRetryService.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID job,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal body){return service.propose(userId,sessionId,job,key,body.checkerId());}
    @PostMapping("/customer-mail-retries/{id}/inspect") public CustomerMailRetryService.Request inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
    @GetMapping("/customer-mail-retries/{id}") public CustomerMailRetryService.Request status(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.status(userId,sessionId,id);}
    @PostMapping("/customer-mail-retries/{id}/approve/options") public CustomerMailRetryService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body){return service.options(userId,sessionId,id,body.payloadHash(),true);}
    @PostMapping("/customer-mail-retries/{id}/reject/options") public CustomerMailRetryService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body){return service.options(userId,sessionId,id,body.payloadHash(),false);}
    @PostMapping("/customer-mail-retries/{id}/approve") public CustomerMailRetryService.Request approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body){return service.decide(userId,sessionId,id,body.payloadHash(),true,body.proof());}
    @PostMapping("/customer-mail-retries/{id}/reject") public CustomerMailRetryService.Request reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body){return service.decide(userId,sessionId,id,body.payloadHash(),false,body.proof());}
}
