package dustin.cex.domains.edd;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 제출 대기와 외부 관찰은 지정된 운영 인물만 조회한다. 실제 외부 전송·자동 완료 경로는 없다. */
@RestController
@RequestMapping("/api/admin/compliance")
public class ReportingFollowupController {
    private final ReportingFollowupService service;
    public ReportingFollowupController(ReportingFollowupService service) {this.service=service;}
    public record Observation(@NotNull ReportingFollowupService.Facts facts) {
        @Override public String toString() {return "Observation[외부 참조·메모 생략]";}
    }
    @GetMapping("/reporting-queue") public ReportingFollowupService.Page queue(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestParam(required=false) Instant afterDecision,@RequestParam(required=false) UUID afterReview) {
        return service.queue(userId,sessionId,afterDecision==null&&afterReview==null?null:new ReportingFollowupService.Cursor(afterDecision,afterReview));
    }
    @PostMapping("/reviews/{review}/followups") public ReportingFollowupService.Observation record(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID review,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Observation body) {return service.record(userId,sessionId,review,key,body.facts());}
    @GetMapping("/reviews/{review}/followups") public List<ReportingFollowupService.Observation> history(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID review,@RequestParam(defaultValue="0") int page) {return service.history(userId,sessionId,review,page);}
    @PostMapping("/followups/{id}/inspect") public ReportingFollowupService.Detail inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @PathVariable UUID id) {return service.inspect(userId,sessionId,id);}
}
