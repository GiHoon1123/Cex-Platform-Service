package dustin.cex.domains.security;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 변경 요청·완료의 최소 이력만 제공한다. 이 조회로 주소나 현재 권한을 바꾸지 않는다. */
@RestController
@RequestMapping("/api/customer/security/email-change-history")
public class CustomerEmailChangeHistoryController {
    private final CustomerEmailChangeHistory history;
    public CustomerEmailChangeHistoryController(CustomerEmailChangeHistory history){this.history=history;}
    @GetMapping public CustomerEmailChangeHistory.Page own(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestParam(required=false) Instant afterAt,@RequestParam(required=false) UUID afterId){
        return history.own(userId,sessionId,afterAt==null&&afterId==null?null:new CustomerEmailChangeHistory.Cursor(afterAt,afterId));
    }
}
