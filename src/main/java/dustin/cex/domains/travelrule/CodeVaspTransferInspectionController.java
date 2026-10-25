package dustin.cex.domains.travelrule;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 개인정보 조회마다 접근 증적을 생성하므로 POST를 사용한다. 세션/역할·대상·무결성은 업무 서비스에서 다시 검증한다. */
@RestController
@RequestMapping("/api/admin/deposits/BTC")
public class CodeVaspTransferInspectionController {
    private final CodeVaspTransferInspection service;
    public CodeVaspTransferInspectionController(CodeVaspTransferInspection service){this.service=service;}
    @PostMapping("/{id}/origin-transfer/{attempt}/inspect")
    public CodeVaspTransferInspection.Inspection inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@PathVariable UUID attempt){return service.inspect(userId,sessionId,id,attempt);}
}
