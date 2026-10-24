package dustin.cex.domains.edd;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 종류·식별자·시각만 제공한다. 진행 회신에 최종 결과 확인 버튼을 연결하지 않는다. */
@RestController
@RequestMapping("/api/customer/relationship/review-notices")
public class CustomerReviewNoticeController {
    private final CustomerReviewNoticeService service;
    public CustomerReviewNoticeController(CustomerReviewNoticeService service){this.service=service;}
    @GetMapping public CustomerReviewNoticeService.Page list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(required=false) Instant afterDecision,@RequestParam(required=false) CustomerReviewNoticeService.Kind afterKind,@RequestParam(required=false) UUID afterNotice){return service.list(userId,sessionId,afterDecision==null&&afterKind==null&&afterNotice==null?null:new CustomerReviewNoticeService.Cursor(afterDecision,afterKind,afterNotice));}
}
