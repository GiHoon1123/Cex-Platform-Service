package dustin.cex.domains.edd;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 증빙 원문은 JSON 본문·URL·파일명으로 받지 않는다. 고객은 자기 요청에 PNG 한 장을 제출한다. */
@RestController
public class EvidenceController {
    private final EvidenceService service;private final EvidenceImage images;
    public EvidenceController(EvidenceService service,EvidenceImage images) {this.service=service;this.images=images;}
    public record Proposal(@NotNull UUID assessmentId,@NotNull EvidenceService.Purpose purpose) {}
    public record Access(@NotNull EvidenceService.AccessReason reason) {}
    @PostMapping("/api/admin/edd/customers/{user}/evidence-requests")
    public EvidenceService.Request request(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable long user,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Proposal body) {
        return service.request(userId,sessionId,user,body.assessmentId(),body.purpose(),key);
    }
    @GetMapping("/api/kyc/edd-evidence/requests")
    public List<EvidenceService.Request> mine(@RequestAttribute long userId,@RequestAttribute UUID sessionId) {return service.customerRequests(userId,sessionId);}
    @GetMapping("/api/admin/edd/customers/{user}/evidence-requests")
    public List<EvidenceService.Request> list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable long user) {return service.operatorRequests(userId,sessionId,user);}
    @PostMapping(value="/api/kyc/edd-evidence/requests/{id}",consumes="image/png")
    public EvidenceService.Receipt upload(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,HttpServletRequest request) throws IOException {
        // 자동 멀티파트의 임시 디스크 저장을 쓰지 않는다. 헤더의 길이와 무관하게 실제 읽기를 제한한다.
        return service.submit(userId,sessionId,id,images.read(request.getInputStream()));
    }
    @PostMapping("/api/admin/edd/evidence/{id}/view")
    public ResponseEntity<byte[]> view(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Access body) {
        var view=service.view(userId,sessionId,id,body.reason());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
            .header("Pragma","no-cache").header("X-Content-Type-Options","nosniff")
            .header("Content-Security-Policy","default-src 'none'; sandbox")
            .header("Content-Disposition","attachment; filename=\""+view.id()+".png\"").body(view.bytes());
    }
}
