package dustin.cex.domains.market;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 시장 상태 조회와 중단·재개의 독립 승인 요청만 제공한다. */
@RestController
@RequestMapping("/api/admin/market-controls")
public class MarketControlController {
    private final MarketControlService service;
    public MarketControlController(MarketControlService service){this.service=service;}
    public record Proposal(@NotBlank @Pattern(regexp="[A-Z0-9]{2,20}") String baseAsset,@NotBlank @Pattern(regexp="[A-Z0-9]{2,20}") String quoteAsset,
        @NotBlank @Pattern(regexp="OPEN|HALTED") String desiredState,UUID signalId,@NotNull @Min(1) Long checkerId,@NotBlank @Size(max=300) String reason){}
    public record Confirmation(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash){}
    public record Decision(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof){@Override public String toString(){return "MarketStateDecision[서명 생략]";}}
    @GetMapping("/{base}/{quote}") public MarketControlService.State state(@PathVariable String base,@PathVariable String quote){return service.state(base,quote);}
    @PostMapping public MarketControlService.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal body){return service.propose(userId,sessionId,body.baseAsset(),body.quoteAsset(),body.desiredState(),body.signalId(),body.checkerId(),body.reason(),key);}
    @PostMapping("/{id}/inspect") public MarketControlService.Request inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
    @PostMapping("/{id}/approve/options") public MarketControlService.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body){return service.options(userId,sessionId,id,body.payloadHash(),true);}
    @PostMapping("/{id}/reject/options") public MarketControlService.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body){return service.options(userId,sessionId,id,body.payloadHash(),false);}
    @PostMapping("/{id}/approve") public MarketControlService.Request approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body){return service.decide(userId,sessionId,id,body.payloadHash(),true,body.proof());}
    @PostMapping("/{id}/reject") public MarketControlService.Request reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body){return service.decide(userId,sessionId,id,body.payloadHash(),false,body.proof());}
}
