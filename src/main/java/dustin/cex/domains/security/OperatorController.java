package dustin.cex.domains.security;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

/** 실행자 식별자는 요청 본문이 아니라 검증한 세션에서만 가져온다. */
@RestController
@RequestMapping("/api/admin")
public class OperatorController {
    private final OperatorApprovalService approvals;private final AuditService audit;
    private final dustin.cex.domains.auth.repository.UserRepository users;
    public OperatorController(OperatorApprovalService approvals,AuditService audit,dustin.cex.domains.auth.repository.UserRepository users) {this.approvals=approvals;this.audit=audit;this.users=users;}
    public record Restriction(@NotNull @Positive Long targetUserId,@NotNull @PositiveOrZero Long expectedVersion,@NotNull Boolean restricted,
        @NotBlank @Size(max=300) String reason) {}
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash) {}
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,
        @NotNull OperatorMfaService.AuthorizationProof proof) {
        // 서명 원문은 요청 DTO의 디버깅 문자열에도 남기지 않는다.
        @Override public String toString() {return "Decision[서명 생략]";}
    }
    @PostMapping("/trading-restrictions")
    public OperatorApprovalService.Request request(@Valid @RequestBody Restriction body,HttpServletRequest req) {
        return approvals.request((Long)req.getAttribute("userId"),(UUID)req.getAttribute("sessionId"),body.targetUserId(),body.expectedVersion(),body.restricted(),body.reason());
    }
    @PostMapping("/requests/{id}/approve")
    public OperatorApprovalService.Request approve(@PathVariable UUID id,@Valid @RequestBody Decision body,HttpServletRequest req) {
        return approvals.decide((Long)req.getAttribute("userId"),(UUID)req.getAttribute("sessionId"),id,body.payloadHash(),true,body.proof());
    }
    @PostMapping("/requests/{id}/reject")
    public OperatorApprovalService.Request reject(@PathVariable UUID id,@Valid @RequestBody Decision body,HttpServletRequest req) {
        return approvals.decide((Long)req.getAttribute("userId"),(UUID)req.getAttribute("sessionId"),id,body.payloadHash(),false,body.proof());
    }
    @PostMapping("/requests/{id}/approve/options")
    public OperatorApprovalService.Confirmation approvalOptions(@PathVariable UUID id,@Valid @RequestBody Confirmation b,@RequestAttribute long userId,@RequestAttribute UUID sessionId) {
        return approvals.options(userId,sessionId,id,b.payloadHash(),true);
    }
    @PostMapping("/requests/{id}/reject/options")
    public OperatorApprovalService.Confirmation rejectionOptions(@PathVariable UUID id,@Valid @RequestBody Confirmation b,@RequestAttribute long userId,@RequestAttribute UUID sessionId) {
        return approvals.options(userId,sessionId,id,b.payloadHash(),false);
    }
    public record CustomerStatus(long id,long version,boolean enabled,boolean tradingRestricted) {}
    @GetMapping("/customers/{id}") public CustomerStatus customer(@PathVariable long id) {
        var user=users.findById(id).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        if(user.getRole()!=dustin.cex.domains.auth.model.entity.UserRole.CUSTOMER)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return new CustomerStatus(id,user.getVersion(),user.isEnabled(),user.isTradingRestricted());
    }
    @GetMapping("/requests") public List<OperatorApprovalService.Request> requests(@RequestParam(defaultValue="0") int page) {return approvals.page(page);}
    @GetMapping("/audit") public List<Map<String,Object>> audit(@RequestParam(defaultValue="0") int page) {return audit.page(page);}
}
