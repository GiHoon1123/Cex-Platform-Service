package dustin.cex.domains.market;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 운영 화면에는 판정이 아닌 검토 신호와 고정된 근거만 제공한다. */
@RestController
@RequestMapping("/api/admin/market-surveillance")
public class MarketSurveillanceController {
    private final JdbcTemplate jdbc;private final MarketSurveillanceCases cases;
    public MarketSurveillanceController(JdbcTemplate jdbc,MarketSurveillanceCases cases){this.jdbc=jdbc;this.cases=cases;}
    @GetMapping("/signals") public List<Map<String,Object>> signals(@RequestParam(defaultValue="0") int page){
        if(page<0||page>10000)throw new IllegalArgumentException("조회 페이지가 범위를 벗어났습니다");
        return jdbc.queryForList("""
            SELECT s.id,s.status,s.created_at,a.input_event,a.policy_id,a.reason,a.recorded_at
              FROM market_surveillance_signals s JOIN market_surveillance_assessments a ON a.id=s.assessment_id
             ORDER BY s.created_at DESC,s.id DESC LIMIT 50 OFFSET ?
            """,page*50);
    }
    @GetMapping("/signals/{id}") public Map<String,Object> signal(@PathVariable UUID id){
        var rows=jdbc.queryForList("""
            SELECT s.id,s.status,s.created_at,a.input_event,a.policy_id,a.reason,a.source_document,a.source_hash,a.recorded_at
              FROM market_surveillance_signals s JOIN market_surveillance_assessments a ON a.id=s.assessment_id WHERE s.id=?
            """,id);
        if(rows.size()!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    public record Note(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=1000) String detail){}
    public record Closure(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp="CLOSED_NO_ACTION|ESCALATED") String outcome,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=1000) String detail){}
    @GetMapping("/cases") public List<MarketSurveillanceCases.Case> cases(@RequestParam(defaultValue="0") int page){return cases.page(page);}
    @GetMapping("/cases/{id}") public MarketSurveillanceCases.Case caseDetail(@PathVariable UUID id){return cases.detail(id);}
    @GetMapping("/cases/{id}/history") public List<MarketSurveillanceCases.Event> history(@PathVariable UUID id,@RequestParam(defaultValue="0") int page){return cases.history(id,page);}
    @PostMapping("/cases/{id}/claims") public MarketSurveillanceCases.Case claim(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id){return cases.claim(userId,sessionId,id);}
    @PostMapping("/cases/{id}/observations") public MarketSurveillanceCases.Event observe(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@jakarta.validation.Valid @RequestBody Note body){return cases.observe(userId,sessionId,id,body.detail());}
    @PostMapping("/cases/{id}/closures") public MarketSurveillanceCases.Case close(@RequestAttribute long userId,@RequestAttribute UUID sessionId,@PathVariable UUID id,@jakarta.validation.Valid @RequestBody Closure body){return cases.close(userId,sessionId,id,body.outcome(),body.detail());}
}
