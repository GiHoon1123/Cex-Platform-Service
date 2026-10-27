package dustin.cex.domains.security;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 공개 상태에는 최소 정보만 내고, 인증된 운영 조회에 금융 작업 적체의 집계만 제공한다. */
@Service
public class OperationalHealthService {
    public record Health(String status){}
    public record QueueState(String name,long pending,Long oldestSeconds){}
    public record Status(String status,Instant observedAt,List<QueueState> queues){}
    private final JdbcTemplate jdbc;private final Instant startedAt=Instant.now();
    public OperationalHealthService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Health live(){return new Health("UP");}
    public Health ready(){try{Boolean ready=jdbc.queryForObject("SELECT to_regclass('public.security_audit') IS NOT NULL AND to_regclass('public.ledger_entries') IS NOT NULL AND to_regclass('public.order_dispatches') IS NOT NULL",Boolean.class);return new Health(Boolean.TRUE.equals(ready)?"UP":"DOWN");}catch(RuntimeException unavailable){return new Health("DOWN");}}
    public Status status(){var queues=List.of(
        queue("ORDER_DISPATCH","SELECT count(*) pending,extract(epoch FROM clock_timestamp()-min(next_attempt_at))::bigint oldest_seconds FROM order_dispatches WHERE confirmed_at IS NULL"),
        queue("ORDER_CANCELLATION","SELECT count(*) pending,extract(epoch FROM clock_timestamp()-min(next_attempt_at))::bigint oldest_seconds FROM order_cancellations WHERE acknowledged_at IS NULL"),
        queue("BTC_CREDIT","SELECT count(*) pending,extract(epoch FROM clock_timestamp()-min(coalesce(next_attempt_at,updated_at)))::bigint oldest_seconds FROM bitcoin_credit_jobs WHERE state NOT IN ('SETTLED','ENGINE_REJECTED')"),
        queue("BTC_OBSERVATION","SELECT count(*) pending,extract(epoch FROM clock_timestamp()-min(next_attempt_at))::bigint oldest_seconds FROM bitcoin_deposit_observation_jobs"),
        queue("CUSTOMER_MAIL","SELECT count(*) pending,extract(epoch FROM clock_timestamp()-min(next_attempt_at))::bigint oldest_seconds FROM customer_mail_delivery WHERE status IN ('PENDING','RETRY','SENDING')"),
        queue("MARKET_SURVEILLANCE","SELECT count(*) pending,extract(epoch FROM clock_timestamp()-min(e.created_at))::bigint oldest_seconds FROM market_activity_events e LEFT JOIN market_surveillance_assessments a ON a.input_event=e.id WHERE a.id IS NULL")
    );return new Status("UP",jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(),queues);}
    public Instant startedAt(){return startedAt;}
    private QueueState queue(String name,String sql){var row=jdbc.queryForMap(sql);long pending=((Number)row.get("pending")).longValue();Object age=row.get("oldest_seconds");return new QueueState(name,pending,age==null?null:Math.max(0,((Number)age).longValue()));}
}
