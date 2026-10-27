package dustin.cex.domains.market;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** 운영 화면에는 판정이 아닌 검토 신호와 고정된 근거만 제공한다. */
@RestController
@RequestMapping("/api/admin/market-surveillance")
public class MarketSurveillanceController {
    private final JdbcTemplate jdbc;
    public MarketSurveillanceController(JdbcTemplate jdbc){this.jdbc=jdbc;}
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
}
