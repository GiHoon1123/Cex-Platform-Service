package dustin.cex.domains.security;

import java.time.Instant;
import java.util.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

/** 요청 DTO에는 행위자·역할을 받지 않는다. 통지·신원 증적은 원문 대신 업무 기록 참조만 받는다. */
@RestController
@RequestMapping("/api/admin/operator-security")
public class OperatorLifecycleController {
    private final OperatorLifecycleService service;
    public OperatorLifecycleController(OperatorLifecycleService service) {this.service=service;}
    public record Recovery(@NotNull @Positive Long targetId,@NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max=200) String reason,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,100}") String identityEvidence,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,100}") String notificationEvidence) {}
    public record Review(@NotNull @Positive Long targetId,@NotNull @PositiveOrZero Long expectedVersion,@NotNull Instant accessUntil,
        @NotBlank @Size(max=200) String reason,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,100}") String identityEvidence,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,100}") String notificationEvidence) {}
    public record Confirmation(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash) {}
    public record Decision(@NotBlank @Pattern(regexp="[0-9a-f]{64}") String payloadHash,@NotNull OperatorMfaService.AuthorizationProof proof) {
        @Override public String toString() {return "Decision[서명 생략]";}
    }
    public record Revocation(@NotNull @PositiveOrZero Long expectedVersion,@NotBlank @Size(max=200) String reason,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,100}") String notificationEvidence) {}
    @PostMapping("/recoveries") public OperatorLifecycleService.Request recover(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@Valid @RequestBody Recovery b) {
        return service.request(userId,sessionId,b.targetId(),b.expectedVersion(),OperatorLifecycleService.Kind.RECOVER_MFA,null,b.reason(),b.identityEvidence(),b.notificationEvidence());
    }
    @PostMapping("/access-reviews") public OperatorLifecycleService.Request review(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@Valid @RequestBody Review b) {
        return service.request(userId,sessionId,b.targetId(),b.expectedVersion(),OperatorLifecycleService.Kind.REVIEW_ACCESS,b.accessUntil(),b.reason(),b.identityEvidence(),b.notificationEvidence());
    }
    @PostMapping("/requests/{id}/approve") public OperatorLifecycleService.Decision approve(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b) {
        return service.decide(userId,sessionId,id,b.payloadHash(),true,b.proof());
    }
    @PostMapping("/requests/{id}/reject") public OperatorLifecycleService.Decision reject(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Decision b) {
        return service.decide(userId,sessionId,id,b.payloadHash(),false,b.proof());
    }
    @PostMapping("/requests/{id}/approve/options") public OperatorLifecycleService.Confirmation approvalOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b) {
        return service.options(userId,sessionId,id,b.payloadHash(),true);
    }
    @PostMapping("/requests/{id}/reject/options") public OperatorLifecycleService.Confirmation rejectionOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@Valid @RequestBody Confirmation b) {
        return service.options(userId,sessionId,id,b.payloadHash(),false);
    }
    @PostMapping("/operators/{id}/revoke") public void revoke(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable long id,@Valid @RequestBody Revocation b) {
        service.revokeAccess(userId,sessionId,id,b.expectedVersion(),b.reason(),b.notificationEvidence());
    }
    @GetMapping("/operators/{id}") public OperatorLifecycleService.OperatorStatus operator(@PathVariable long id) {return service.operator(id);}
    @GetMapping("/requests") public List<OperatorLifecycleService.Request> requests(@RequestParam(defaultValue="0") int page) {return service.page(page);}
}
