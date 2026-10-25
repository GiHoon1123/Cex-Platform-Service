package dustin.cex.domains.monitoring;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 계산과 조사 열람이 같은 불변 입력 창을 읽도록 한다. 고객 잠금과 접근 권한은 호출 업무가 먼저 확보한다. */
final class DepositMonitoringInputs {
    private DepositMonitoringInputs(){}
    static List<SmallDepositMonitoringPolicy.Input> read(JdbcTemplate jdbc,long owner,long cursor,Instant reference){
        // 상한을 넘으면 한 건을 더 읽어 불완전 판정을 내린다. 실제 입력을 최신 시세로 다시 평가하지 않는다.
        // 기준 시각보다 미래인 과거 순번도 숨기지 않고 읽어 시계 역행을 미완료로 판정할 수 있게 한다.
        var rows=jdbc.queryForList("""
            SELECT * FROM deposit_monitoring_inputs WHERE user_id=? AND input_cursor<=? AND recognized_at>=?
             ORDER BY input_cursor DESC LIMIT 1001
            """,owner,cursor,Timestamp.from(reference.minusSeconds(86400)));
        var inputs=new ArrayList<SmallDepositMonitoringPolicy.Input>();
        for(var row:rows)inputs.add(new SmallDepositMonitoringPolicy.Input(((Number)row.get("input_cursor")).longValue(),(String)row.get("operation_key"),(UUID)row.get("receipt_id"),(String)row.get("asset"),(String)row.get("network"),(String)row.get("txid"),((Timestamp)row.get("recognized_at")).toInstant(),(String)row.get("currency"),(String)row.get("valuation_policy"),(String)row.get("valuation_status"),row.get("price_attempt_id")==null?null:((Number)row.get("price_attempt_id")).longValue(),(BigDecimal)row.get("krw_value")));
        Collections.reverse(inputs);return List.copyOf(inputs);
    }
}
