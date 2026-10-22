package dustin.cex.domains.security;

import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import dustin.cex.domains.auth.model.entity.UserRole;

/** 행위자와 세션은 검증한 헤더에서만 가져온다. 요청 본문의 임의 행위자 필드는 권한이 되지 않는다. */
@RestController
@RequestMapping("/api/admin/operator-security")
public class OperatorRoleController {
    private final OperatorRoleService service;
    public OperatorRoleController(OperatorRoleService service) {this.service=service;}
    public record RoleChange(@NotNull @Positive Long targetId,@NotNull @PositiveOrZero Long expectedVersion,
        @NotNull UUID personId,@NotNull UserRole role,@NotNull Instant accessUntil,
        @NotBlank @Size(max=200) String reason,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,100}") String identityEvidence,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,100}") String notificationEvidence) {}
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash) {}
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,
        @NotNull OperatorMfaService.AuthorizationProof proof) {
        @Override public String toString() {return "Decision[서명 생략]";}
    }
    @PostMapping("/role-requests") public OperatorRoleService.Request request(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @Valid @RequestBody RoleChange b) {
        return service.request(userId,sessionId,b.targetId(),b.expectedVersion(),b.personId(),b.role(),b.accessUntil(),b.reason(),b.identityEvidence(),b.notificationEvidence());
    }
    @PostMapping("/role-requests/{id}/approve/options") public OperatorRoleService.Confirmation approvalOptions(@RequestAttribute long userId,
            @RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b) {
        return service.options(userId,sessionId,id,b.payloadHash(),true);
    }
    @PostMapping("/role-requests/{id}/reject/options") public OperatorRoleService.Confirmation rejectionOptions(@RequestAttribute long userId,
            @RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b) {
        return service.options(userId,sessionId,id,b.payloadHash(),false);
    }
    @PostMapping("/role-requests/{id}/approve") public OperatorRoleService.Decision approve(@RequestAttribute long userId,
            @RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b) {
        return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());
    }
    @PostMapping("/role-requests/{id}/reject") public OperatorRoleService.Decision reject(@RequestAttribute long userId,
            @RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b) {
        return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());
    }
    @GetMapping("/role-requests") public List<OperatorRoleService.Request> page(@RequestParam(defaultValue="0") int page) {return service.page(page);}
    @GetMapping("/role-targets/{id}") public OperatorRoleService.Target target(@PathVariable long id) {return service.target(id);}
}
