package dustin.cex.domains.edd;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 기존 공개 회신을 DB에서 집계한다. 전체 문구/이력을 JVM으로 복사하지 않고 논리 순서의 시각 역행도 찾는다. */
@Component
public class CustomerReviewUpdateTimeline {
    private final JdbcTemplate jdbc;
    public CustomerReviewUpdateTimeline(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public CustomerReviewUpdateTarget.Assessment assess(UUID review,Instant received,Instant finalized,Instant observed){
        // 최종 승인은 마지막 공개 응답이다. 시각 정렬을 쓰면 시계 역행을 숨길 수 있어 종류·논리 순번으로 정렬한다.
        var row=jdbc.queryForMap("""
            WITH events AS (
                SELECT 0 AS kind,0::bigint AS sequence,?::timestamptz AS occurred
                UNION ALL SELECT 1,sequence,(payload::jsonb->>'observedAt')::timestamptz FROM customer_exit_review_replies WHERE request_id=?
                UNION ALL SELECT 2,0,?::timestamptz WHERE ?::timestamptz IS NOT NULL
            ), gaps AS (SELECT kind,occurred,occurred-lag(occurred) OVER(ORDER BY kind,sequence) AS elapsed FROM events)
            SELECT max(occurred) FILTER(WHERE kind=1) AS latest_public,
                count(*) FILTER(WHERE elapsed>interval '24 hours') AS breached,
                coalesce(bool_or(elapsed<interval '0 seconds'),false) AS reversed
            FROM gaps
            """,Timestamp.from(received),review,finalized==null?null:Timestamp.from(finalized),finalized==null?null:Timestamp.from(finalized));
        var latest=(Timestamp)row.get("latest_public");
        return CustomerReviewUpdateTarget.assess(received,latest==null?null:latest.toInstant(),finalized,((Number)row.get("breached")).longValue(),Boolean.TRUE.equals(row.get("reversed")),observed);
    }
}
