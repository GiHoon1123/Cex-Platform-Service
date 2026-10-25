package dustin.cex.domains.monitoring;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 새 수탁 원장 반입과 모니터링 알림을 같은 트랜잭션으로 결합한다. 큐 위치는 금융 원본의 대체물이 아니다. */
@Service
@DependsOn("depositMonitoringSchemaInitializer")
public class DepositMonitoringQueue {
    private final JdbcTemplate jdbc;
    public DepositMonitoringQueue(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(long owner,String operation){
        Long cursor=jdbc.queryForObject("SELECT input_cursor FROM deposit_monitoring_inputs WHERE user_id=? AND operation_key=?",Long.class,owner,operation);
        jdbc.update("""
            INSERT INTO deposit_monitoring_jobs(user_id,target_cursor) VALUES(?,?)
             ON CONFLICT(user_id) DO UPDATE SET target_cursor=greatest(deposit_monitoring_jobs.target_cursor,excluded.target_cursor),updated_at=clock_timestamp()
            """,owner,cursor);
    }
}
