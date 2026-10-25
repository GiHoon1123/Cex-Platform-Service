package dustin.cex.domains.bitcoin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 조회용 복구 근거만 만든다. 제한 해제·자산 정정·엔진 금융 명령은 받지 않는다. */
@RestController
@RequestMapping("/api/admin/deposits/BTC")
public class BitcoinChainRecoveryController {
    public record Prepare(@NotNull UUID requestKey){}
    private final BitcoinChainRecoveryProofs proofs;
    public BitcoinChainRecoveryController(BitcoinChainRecoveryProofs proofs){this.proofs=proofs;}
    @PostMapping("/{id}/chain-recovery-probes") public BitcoinChainRecoveryProofs.Report prepare(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
        @PathVariable UUID id,@Valid @RequestBody Prepare request){return proofs.prepare(userId,sessionId,id,request.requestKey());}
    @GetMapping("/chain-recovery-probes/{id}") public BitcoinChainRecoveryProofs.Report read(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
        @PathVariable UUID id){return proofs.get(userId,sessionId,id);}
}
