package dustin.cex.domains.security;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** 고객 패스키의 등록·서명·본인 키 관리 API다. 대기 세션과 검증 완료 세션의 권한은 구분한다. */
@RestController
@RequestMapping("/api/auth/customer-mfa")
public class CustomerMfaController {
    private final CustomerMfaService service;
    private final CustomerDeviceService devices;
    public CustomerMfaController(CustomerMfaService service,CustomerDeviceService devices){this.service=service;this.devices=devices;}
    // 비밀번호/인증 자료를 기본 검증 오류나 record 출력에 복사하지 않는다. 실제 검증은 서비스가 수행한다.
    public record Enrollment(String currentPassword){@Override public String toString(){return "Enrollment[비밀번호 생략]";}}
    public record Proof(UUID challengeId,JsonNode publicKey){@Override public String toString(){return "Proof[인증 자료 생략]";}}
    @PostMapping("/register/options")public CustomerMfaService.Options options(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody Enrollment input){return service.registrationOptions(userId,sessionId,input.currentPassword());}
    @PostMapping("/register")public CustomerMfaService.Enrolled register(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody Proof input){return service.register(userId,sessionId,input.challengeId(),input.publicKey());}
    @PostMapping("/authenticate/options")public CustomerMfaService.Options authentication(@RequestAttribute long userId,@RequestAttribute UUID sessionId){return service.authenticationOptions(userId,sessionId);}
    @PostMapping("/authenticate")public CustomerMfaService.Verified authenticate(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody Proof input){return service.authenticate(userId,sessionId,input.challengeId(),input.publicKey());}
    @GetMapping("/credentials")public java.util.List<CustomerDeviceService.Device> devices(@RequestAttribute long userId,@RequestAttribute UUID sessionId){return devices.own(userId,sessionId);}
    @DeleteMapping("/credentials/{fingerprint}")public void revoke(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable String fingerprint){devices.revoke(userId,sessionId,fingerprint);}
}
