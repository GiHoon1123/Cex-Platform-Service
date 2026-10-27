package dustin.cex.domains.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 개인정보 보존 정지의 제안·열람·독립 결정과 최소 상태 조회를 제공한다. */
@RestController
@RequestMapping("/api/admin/data-retention")
public class DataRetentionController {
    private final DataRetentionService service;
    public DataRetentionController(DataRetentionService service){this.service=service;}
    public record Proposal(@NotBlank @Pattern(regexp="HOLD|RELEASE") String action,@NotBlank @Pattern(regexp="USER|MAIL_JOB") String scopeType,@NotBlank @Size(max=64) String scopeId,UUID holdId,@NotNull @Min(1) Long checkerId,@NotBlank @Size(max=300) String reason,@NotBlank @Size(max=200) String authorityReference){}
    public record Confirmation(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash){}
    public record Decision(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof){@Override public String toString(){return "DataRetentionDecision[서명 생략]";}}
    @GetMapping("/holds") public List<DataRetentionService.Hold> holds(@RequestParam(defaultValue="0") int page){return service.holds(page);}
    @PostMapping("/requests") public DataRetentionService.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal body){return service.propose(userId,sessionId,body.action(),body.scopeType(),body.scopeId(),body.holdId(),body.checkerId(),body.reason(),body.authorityReference(),key);}
    @PostMapping("/requests/{id}/inspect") public DataRetentionService.Request inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
    @PostMapping("/requests/{id}/approve/options") public DataRetentionService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body){return service.options(userId,sessionId,id,body.payloadHash(),true);}
    @PostMapping("/requests/{id}/reject/options") public DataRetentionService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body){return service.options(userId,sessionId,id,body.payloadHash(),false);}
    @PostMapping("/requests/{id}/approve") public DataRetentionService.Request approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body){return service.decide(userId,sessionId,id,body.payloadHash(),true,body.proof());}
    @PostMapping("/requests/{id}/reject") public DataRetentionService.Request reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body){return service.decide(userId,sessionId,id,body.payloadHash(),false,body.proof());}
}
