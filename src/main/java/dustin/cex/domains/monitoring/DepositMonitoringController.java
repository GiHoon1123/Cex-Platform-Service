package dustin.cex.domains.monitoring;

import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** 실제 입금 소유자의 탐지·조사 업무를 제공한다. 고객 식별자·금액·금융 허가를 요청에서 받아 확정하지 않는다. */
@RestController
@RequestMapping("/api/admin/deposits/BTC")
public class DepositMonitoringController {
    private final DepositMonitoringRequest service;
    private final DepositMonitoringInvestigations investigations;
    public DepositMonitoringController(DepositMonitoringRequest service,DepositMonitoringInvestigations investigations){this.service=service;this.investigations=investigations;}
    @PostMapping("/{id}/monitoring-requests")
    public DepositMonitoringRequest.Requested request(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return service.request(userId,sessionId,id);}
    // 계정의 분석 이력은 최소 목록으로, 실제 금융 입력과 조사 메모는 별도 명시적 열람으로 제공한다.
    @GetMapping("/{id}/monitoring-assessments")
    public DepositMonitoringInvestigations.Page list(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@RequestParam(required=false) Long before){return investigations.list(userId,sessionId,id,before);}
    @PostMapping("/{id}/monitoring-assessments/{assessment}/inspect")
    public DepositMonitoringInvestigations.Viewed inspect(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@PathVariable UUID assessment){return investigations.inspect(userId,sessionId,id,assessment);}
    @PostMapping("/{id}/monitoring-assessments/{assessment}/observations")
    public DepositMonitoringInvestigations.Observation record(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@PathVariable UUID assessment,@RequestBody DepositMonitoringInvestigations.Write request){return investigations.record(userId,sessionId,id,assessment,request);}
    @GetMapping("/{id}/monitoring-assessments/{assessment}/observations")
    public java.util.List<DepositMonitoringInvestigations.Observation> observations(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@PathVariable UUID assessment,@RequestParam(defaultValue="0") int page){return investigations.observations(userId,sessionId,id,assessment,page);}
    @PostMapping("/{id}/monitoring-assessments/{assessment}/observations/{observation}/inspect")
    public DepositMonitoringInvestigations.Detail inspectObservation(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@PathVariable UUID assessment,@PathVariable UUID observation){return investigations.inspectObservation(userId,sessionId,id,assessment,observation);}

}
