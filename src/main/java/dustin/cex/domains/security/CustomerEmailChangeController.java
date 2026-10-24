package dustin.cex.domains.security;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 새 주소·비밀번호·토큰은 요청 본문으로만 받고 기본 문자열 출력에서 숨긴다. */
@RestController
@RequestMapping("/api/customer/security/email")
public class CustomerEmailChangeController {
    public record Prepare(String currentPassword,String newEmail){@Override public String toString(){return "Prepare[인증 자료 생략]";}}
    public record Request(String newEmail,CustomerEmailChangeService.Proof proof){@Override public String toString(){return "Request[변경 자료 생략]";}}
    public record Confirmation(UUID requestId,String token){@Override public String toString(){return "Confirmation[토큰 생략]";}}
    private final CustomerEmailChangeService service;
    public CustomerEmailChangeController(CustomerEmailChangeService service){this.service=service;}
    @PostMapping("/options")public CustomerEmailChangeService.Options options(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody Prepare input){return service.options(userId,sessionId,input.currentPassword(),input.newEmail());}
    @PostMapping("/requests")public dustin.cex.domains.mail.CustomerEmailChallenges.Receipt request(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody Request input){return service.request(userId,sessionId,input.newEmail(),input.proof());}
    @PostMapping("/confirmations")public CustomerEmailChangeService.Changed confirm(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody Confirmation input){return service.confirm(userId,sessionId,input.requestId(),input.token());}
}
