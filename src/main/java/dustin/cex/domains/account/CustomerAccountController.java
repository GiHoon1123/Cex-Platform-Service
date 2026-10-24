package dustin.cex.domains.account;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 고객은 본인 식별자를 입력하지 않는다. 인증 필터가 확정한 계정·세션으로만 실행한다. */
@RestController
@RequestMapping("/api/customer/security")
public class CustomerAccountController {
    private final CustomerSessionControl sessions;
    public CustomerAccountController(CustomerSessionControl sessions){this.sessions=sessions;}
    @GetMapping("/sessions") public CustomerSessionControl.Page sessions(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(required=false) LocalDateTime afterAt,@RequestParam(required=false) UUID afterId){return sessions.own(userId,sessionId,afterAt==null&&afterId==null?null:new CustomerSessionControl.Cursor(afterAt,afterId));}
    @DeleteMapping("/sessions/{id}") public CustomerSessionControl.Revocation revoke(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return sessions.revoke(userId,sessionId,id);}
}
