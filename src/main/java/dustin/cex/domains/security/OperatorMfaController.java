package dustin.cex.domains.security;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** 호출자 ID·세션은 서명과 영속 상태를 확인한 필터에서만 가져온다. */
@RestController
@RequestMapping("/api/auth/operator-mfa")
public class OperatorMfaController {
    private final OperatorMfaService service;
    private final OperatorDeviceService devices;
    public OperatorMfaController(OperatorMfaService service,OperatorDeviceService devices) {this.service=service;this.devices=devices;}
    public record Enrollment(@Size(max=256) String enrollmentToken) {
        @Override public String toString() {return "Enrollment[비밀 생략]";}
    }
    public record Proof(@NotNull UUID challengeId,@NotNull JsonNode publicKey) {
        @Override public String toString() {return "Proof[인증 자료 생략]";}
    }
    @PostMapping("/register/options") public OperatorMfaService.Options registrationOptions(
            @RequestAttribute long userId,@RequestAttribute UUID sessionId,@Valid @RequestBody Enrollment body) {
        return service.registrationOptions(userId,sessionId,body.enrollmentToken());
    }
    @PostMapping("/register") public void register(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @Valid @RequestBody Proof body) {
        service.register(userId,sessionId,body.challengeId(),body.publicKey());
    }
    @PostMapping("/authenticate/options") public OperatorMfaService.Options authenticationOptions(
            @RequestAttribute long userId,@RequestAttribute UUID sessionId) {
        return service.authenticationOptions(userId,sessionId);
    }
    @PostMapping("/authenticate") public OperatorMfaService.Verified authenticate(
            @RequestAttribute long userId,@RequestAttribute UUID sessionId,@Valid @RequestBody Proof body) {
        return service.authenticate(userId,sessionId,body.challengeId(),body.publicKey());
    }
    @GetMapping("/credentials") public java.util.List<OperatorDeviceService.Device> devices(@RequestAttribute long userId,@RequestAttribute UUID sessionId) {
        return devices.devices(userId,sessionId);
    }
    @DeleteMapping("/credentials/{fingerprint}") public void revoke(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable String fingerprint) {
        devices.revoke(userId,sessionId,fingerprint);
    }

}
