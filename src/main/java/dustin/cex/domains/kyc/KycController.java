package dustin.cex.domains.kyc;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 고객 식별자는 Bearer 세션에서만 얻는다. 복귀 URL의 status나 user_id는 승인 근거로 받지 않는다. */
@RestController
public class KycController {
    private final KycService service;
    public KycController(KycService service) {this.service=service;}
    @PostMapping("/api/kyc/session") public KycService.Session start(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestHeader("Idempotency-Key") UUID id) {return service.start(userId,sessionId,id);}
    @GetMapping("/api/kyc/session") public KycService.Session status(@RequestAttribute long userId,@RequestAttribute UUID sessionId) {return service.ownSession(userId,sessionId);}
    @PostMapping("/api/kyc/sessions/{id}/reconcile") public KycService.Session reconcile(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id) {
        return service.reconcile(userId,sessionId,id);
    }
    @PostMapping("/api/kyc/webhook") public void webhook(HttpServletRequest request) throws java.io.IOException {
        // 요청 본문을 메모리에 무제한 적재하지 않는다. 원본 바이트는 로깅·DB 저장하지 않는다.
        byte[] body=request.getInputStream().readNBytes(1048577);
        if(body.length>1048576) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        service.webhook(body,request.getHeader("X-Signature-V2"),request.getHeader("X-Timestamp"));
    }
    @GetMapping("/api/admin/kyc/customers/{id}") public KycService.Status admin(@PathVariable long id) {return service.status(id);}
}
