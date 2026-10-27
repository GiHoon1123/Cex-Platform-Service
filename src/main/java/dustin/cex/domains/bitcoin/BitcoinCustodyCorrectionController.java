package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.OperatorMfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 수탁 정정 원본의 요청·열람·독립 승인만 노출한다. 실제 실행은 고객 HTTP와 분리한다. */
@RestController
@RequestMapping("/api/admin/custody/BTC/corrections")
public class BitcoinCustodyCorrectionController {
    private final BitcoinCustodyCorrections service;
    public BitcoinCustodyCorrectionController(BitcoinCustodyCorrections service){this.service=service;}
    public record Proposal(@NotNull UUID reconciliationRun,@NotNull @Min(1) Long checkerId){}
    public record Confirmation(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash){}
    public record Decision(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof){@Override public String toString(){return "CustodyCorrectionDecision[서명 생략]";}}
    @PostMapping public BitcoinCustodyCorrections.Request propose(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal body){return service.propose(userId,sessionId,body.reconciliationRun(),body.checkerId(),key);}
    // 열람 자체가 승인 선행 증적을 생성하므로 안전한 조회로 위장하지 않고 POST로만 노출한다.
    @PostMapping("/{id}/inspect") public BitcoinCustodyCorrections.Request inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.inspect(userId,sessionId,id);}
    @PostMapping("/{id}/approve/options") public BitcoinCustodyCorrections.Confirmation approveOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body){return service.options(userId,sessionId,id,body.payloadHash(),true);}
    @PostMapping("/{id}/reject/options") public BitcoinCustodyCorrections.Confirmation rejectOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation body){return service.options(userId,sessionId,id,body.payloadHash(),false);}
    @PostMapping("/{id}/approve") public BitcoinCustodyCorrections.Request approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body){return service.decide(userId,sessionId,id,body.payloadHash(),true,body.proof());}
    @PostMapping("/{id}/reject") public BitcoinCustodyCorrections.Request reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision body){return service.decide(userId,sessionId,id,body.payloadHash(),false,body.proof());}
}
