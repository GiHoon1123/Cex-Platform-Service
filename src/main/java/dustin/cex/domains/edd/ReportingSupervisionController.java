package dustin.cex.domains.edd;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 원문 없는 전사 점검 목록이다. 승인 역할 확인과 최근 추가 인증은 필터와 서비스에서 함께 강제한다. */
@RestController
@RequestMapping("/api/admin/compliance")
public class ReportingSupervisionController {
    private final ReportingSupervisionService service;
    public ReportingSupervisionController(ReportingSupervisionService service) {this.service=service;}

    @GetMapping("/reporting-supervision")
    public ReportingSupervisionService.Page queue(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestParam(required=false) Instant afterDecision,@RequestParam(required=false) UUID afterReview) {
        // 두 매개변수 중 하나만 오면 서비스의 입력 검사에서 거절하도록 불완전한 위치도 전달한다.
        var after=afterDecision==null&&afterReview==null?null:new ReportingSupervisionService.Cursor(afterDecision,afterReview);
        return service.queue(userId,sessionId,after);
    }
}
