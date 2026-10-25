package dustin.cex.domains.monitoring;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 인가된 운영 조회 안에서 고객의 현재 탐지 위치와 과거 신호 수만 읽는다. 단일 규칙의 결과는 금융 허가가 아니다. */
@Service
@DependsOn("depositMonitoringSchemaInitializer")
public class DepositMonitoringReview {
    public record Summary(UUID assessmentId,String policy,Long inputCursor,Instant referenceAt,String outcome,String reason,
        int smallTransfers,String smallTotalKrw,int unknownTransfers,long historicalSignals,boolean newerInputsPending,Instant recordedAt,UUID caseId,String assurance){}
    private final JdbcTemplate jdbc;
    public DepositMonitoringReview(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public Summary read(long owner){
        var row=jdbc.queryForMap("""
            SELECT a.*, c.case_id, (SELECT max(input_cursor) FROM deposit_monitoring_inputs WHERE user_id=?) latest_input,
             (SELECT count(*) FROM deposit_monitoring_assessments WHERE user_id=? AND outcome='REVIEW_REQUIRED') historical_signals
             FROM(SELECT ?::bigint owner) input LEFT JOIN deposit_monitoring_heads h ON h.user_id=input.owner
             LEFT JOIN deposit_monitoring_assessments a ON a.id=h.assessment_id LEFT JOIN deposit_monitoring_case_heads c ON c.user_id=input.owner
            """,owner,owner,owner);
        Long cursor=row.get("input_cursor")==null?null:((Number)row.get("input_cursor")).longValue();Long latest=row.get("latest_input")==null?null:((Number)row.get("latest_input")).longValue();
        boolean missing=row.get("id")==null;
        return new Summary((UUID)row.get("id"),(String)row.get("policy_id"),cursor,at(row.get("reference_at")),missing?"NOT_ASSESSED":(String)row.get("outcome"),
            missing?(latest==null?"NO_CUSTODY_INPUT":"AWAITING_PROCESSING"):(String)row.get("reason"),missing?0:((Number)row.get("small_transfers")).intValue(),missing?null:((BigDecimal)row.get("small_total_krw")).toPlainString(),missing?0:((Number)row.get("unknown_transfers")).intValue(),((Number)row.get("historical_signals")).longValue(),latest!=null&&(cursor==null||latest>cursor),at(row.get("recorded_at")),(UUID)row.get("case_id"),"ONE_RULE_REFERENCE_ONLY_NOT_FINANCIAL_PERMISSION");
    }
    private static Instant at(Object value){return value==null?null:((Timestamp)value).toInstant();}
}
