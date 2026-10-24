package dustin.cex.domains.edd;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 미결 요청 감독은 판단 원문과 결정 API에서 분리한다. 커서 세 필드는 함께 검사한다. */
@RestController
@RequestMapping("/api/admin/compliance/exit-approval-supervision")
public class ExitApprovalSupervisionController {
    private final ExitApprovalSupervisionService service;
    public ExitApprovalSupervisionController(ExitApprovalSupervisionService service){this.service=service;}
    @GetMapping public ExitApprovalSupervisionService.Page queue(@RequestAttribute long userId,@RequestAttribute UUID sessionId,
            @RequestParam(required=false) Instant afterCreated,@RequestParam(required=false) ExitApprovalSupervisionService.Kind afterKind,@RequestParam(required=false) UUID afterRequest) {
        return service.queue(userId,sessionId,afterCreated==null&&afterKind==null&&afterRequest==null?null:new ExitApprovalSupervisionService.Cursor(afterCreated,afterKind,afterRequest));
    }
}
