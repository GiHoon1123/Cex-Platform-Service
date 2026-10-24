package dustin.cex.domains.edd;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 원문 없는 목록만 제공한다. 불완전한 커서도 서비스로 넘겨 입력 오류로 거부한다. */
@RestController
@RequestMapping("/api/admin/compliance/exit-supervision")
public class ExitSupervisionController {
    private final ExitSupervisionService service;
    public ExitSupervisionController(ExitSupervisionService service){this.service=service;}
    @GetMapping public ExitSupervisionService.Page queue(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestParam(required=false) Instant afterDecision,@RequestParam(required=false) UUID afterReview) {
        return service.queue(userId,sessionId,afterDecision==null&&afterReview==null?null:new ExitSupervisionService.Cursor(afterDecision,afterReview));
    }
}
