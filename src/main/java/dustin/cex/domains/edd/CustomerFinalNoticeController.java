package dustin.cex.domains.edd;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 본인 알림에는 대상 식별자만 제공한다. 문구 조회·명시적 확인은 기존 목적별 API를 사용한다. */
@RestController
@RequestMapping("/api/customer/relationship/final-notices")
public class CustomerFinalNoticeController {
    private final CustomerFinalNoticeService service;
    public CustomerFinalNoticeController(CustomerFinalNoticeService service){this.service=service;}
    @GetMapping public CustomerFinalNoticeService.Page list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(required=false) Instant afterDecision,@RequestParam(required=false) UUID afterFinal){return service.list(userId,sessionId,afterDecision==null&&afterFinal==null?null:new CustomerFinalNoticeService.Cursor(afterDecision,afterFinal));}
}
