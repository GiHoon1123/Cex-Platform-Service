package dustin.cex.domains.edd;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 본인의 확정 답변 조회와 명시적 확인을 분리한다. 확인은 동의나 권리 포기 입력이 아니다. */
@RestController
@RequestMapping("/api/customer/relationship/review-requests/{review}/final")
public class CustomerFinalReceiptController {
    public record Confirmation(@NotNull UUID finalId) {}
    private final CustomerFinalReviewService service;
    public CustomerFinalReceiptController(CustomerFinalReviewService service){this.service=service;}
    @GetMapping public CustomerFinalReviewService.FinalReceipt own(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID review){return service.ownFinal(userId,sessionId,review);}
    @PostMapping("/acknowledgments") public CustomerFinalReviewService.Acknowledgment acknowledge(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID review,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Confirmation b){return service.acknowledge(userId,sessionId,review,b.finalId(),key);}
}
