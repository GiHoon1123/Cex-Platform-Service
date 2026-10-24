package dustin.cex.domains.security;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 인증 자료는 본문으로만 받는다. 경로·로그 문자열에 복구 코드를 복사하지 않는다. */
@RestController
@RequestMapping("/api/auth/customer-recovery")
public class CustomerRecoveryController {
    private final CustomerRecoveryService service;
    private final CustomerRecoveryHistory history;
    public CustomerRecoveryController(CustomerRecoveryService service,CustomerRecoveryHistory history){this.service=service;this.history=history;}
    public record Password(String currentPassword){@Override public String toString(){return "Password[비밀번호 생략]";}}
    public record Recovery(String currentPassword,String recoveryCode){@Override public String toString(){return "Recovery[인증 자료 생략]";}}
    @GetMapping("/status")public CustomerRecoveryService.Status status(@RequestAttribute long userId,@RequestAttribute UUID sessionId){return service.status(userId,sessionId);}
    @PostMapping("/code/options")public CustomerRecoveryService.Options options(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody Password input){return service.codeOptions(userId,sessionId,input.currentPassword());}
    @PostMapping("/code")public CustomerRecoveryService.Code code(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody CustomerRecoveryService.Proof proof){return service.replaceCode(userId,sessionId,proof);}
    @PostMapping("/register/options")public CustomerRecoveryService.Options recoveryOptions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody Recovery input){return service.recoveryOptions(userId,sessionId,input.currentPassword(),input.recoveryCode());}
    @PostMapping("/register")public CustomerRecoveryService.Code recover(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody CustomerRecoveryService.Proof proof){return service.recover(userId,sessionId,proof);}
    @GetMapping("/changes")public CustomerRecoveryHistory.Page changes(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(required=false) java.time.Instant afterAt,@RequestParam(required=false) UUID afterId){return history.own(userId,sessionId,afterAt==null&&afterId==null?null:new CustomerRecoveryHistory.Cursor(afterAt,afterId));}

}
