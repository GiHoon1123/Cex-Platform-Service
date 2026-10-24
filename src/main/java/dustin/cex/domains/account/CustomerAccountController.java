package dustin.cex.domains.account;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 고객은 본인 식별자를 입력하지 않는다. 인증 필터가 확정한 계정·세션으로만 실행한다. */
@RestController
@RequestMapping("/api/customer/security")
public class CustomerAccountController {
    private final CustomerSessionControl sessions;
    private final CustomerPasswordChange passwords;
    public CustomerAccountController(CustomerSessionControl sessions,CustomerPasswordChange passwords){this.sessions=sessions;this.passwords=passwords;}
    @GetMapping("/sessions") public CustomerSessionControl.Page sessions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(required=false) LocalDateTime afterAt,@RequestParam(required=false) UUID afterId){return sessions.own(userId,sessionId,afterAt==null&&afterId==null?null:new CustomerSessionControl.Cursor(afterAt,afterId));}
    @DeleteMapping("/sessions/{id}") public CustomerSessionControl.Revocation revoke(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return sessions.revoke(userId,sessionId,id);}
    // 비밀 입력은 서비스에서 검증한다. 기본 필드 검증 오류에 거부된 원문을 복사하지 않는다.
    @PostMapping("/password") public CustomerPasswordChange.Receipt password(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestBody CustomerPasswordChange.Input body){return passwords.change(userId,sessionId,body);}

}
