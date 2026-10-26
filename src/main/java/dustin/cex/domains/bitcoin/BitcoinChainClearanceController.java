package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 해제 대상/구간을 요청하고 지정 승인자가 실제 열람·내용 서명한다. 일반 고객이나 감사자에게 변경 권한을 주지 않는다. */
@RestController
@RequestMapping("/api/admin/deposits/BTC/chain-clearances")
public class BitcoinChainClearanceController {
    private final BitcoinChainRecoveryClearances service;
    public BitcoinChainClearanceController(BitcoinChainRecoveryClearances service){this.service=service;}
    public record Proposal(@NotNull UUID proofId,@NotNull @Min(1) Long checkerId){}
    public record Confirmation(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash){}
    public record Decision(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof){@Override public String toString(){return "ChainClearanceDecision[서명 생략]";}}
    @PostMapping public BitcoinChainRecoveryClearances.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal b){return service.propose(userId,sessionId,b.proofId(),b.checkerId(),key);}
    @GetMapping("/{id}") public BitcoinChainRecoveryClearances.Request inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
    @PostMapping("/{id}/approve/options") public BitcoinChainRecoveryClearances.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),true);}
    @PostMapping("/{id}/reject/options") public BitcoinChainRecoveryClearances.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b){return service.options(userId,sessionId,id,b.payloadHash(),false);}
    @PostMapping("/{id}/approve") public BitcoinChainRecoveryClearances.Request approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());}
    @PostMapping("/{id}/reject") public BitcoinChainRecoveryClearances.Request reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b){return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());}
}
