package dustin.cex.domains.monitoring;

import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 사건 목록에서 실제 입금·탐지 원본·담당자 의견으로 이동할 최소 식별자를 제공한다. 종결·신고·금융 실행은 하지 않는다. */
@RestController
@RequestMapping("/api/admin/monitoring-cases")
public class DepositMonitoringCaseController {
    private final DepositMonitoringCases cases;
    public DepositMonitoringCaseController(DepositMonitoringCases cases){this.cases=cases;}
    @GetMapping public List<DepositMonitoringCases.Case> list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@RequestParam(defaultValue="0") int page){return cases.list(userId,sessionId,page);}
    @GetMapping("/{id}") public DepositMonitoringCases.Members members(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@RequestParam(required=false) Long before){return cases.members(userId,sessionId,id,before);}
}
