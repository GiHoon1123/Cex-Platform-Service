package dustin.cex.domains.market;

import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

/** 확정 이벤트를 순서대로 평가한다. 신호는 조사 시작점이며 위반 확정이나 자동 고객 제한이 아니다. */
@Service
@DependsOn("marketSurveillanceSchemaInitializer")
public class MarketSurveillanceService {
    public static final String POLICY="SPOT_MARKET_SURVEILLANCE_V1";
    public record Assessment(UUID id,long inputEvent,String outcome,String reason,String sourceHash){}
    private record Decision(String outcome,String reason,List<Long> evidence){}
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final AuditService audit;private final TransactionTemplate tx;
    public MarketSurveillanceService(JdbcTemplate jdbc,ObjectMapper json,AuditService audit,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.json=json;this.audit=audit;this.tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }

    public Optional<Assessment> processNext(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("시장 감시는 독립 트랜잭션으로 실행하세요");
        return tx.execute(status->process());
    }
    private Optional<Assessment> process(){
        long cursor=jdbc.queryForObject("SELECT observed_event FROM market_surveillance_state WHERE singleton=true FOR UPDATE",Long.class);
        var events=jdbc.queryForList("SELECT * FROM market_activity_events WHERE id>? ORDER BY id LIMIT 1",cursor);
        if(events.isEmpty())return Optional.empty();var event=events.getFirst();long id=number(event,"id");Decision decision=assess(event);
        var source=new LinkedHashMap<String,Object>();source.put("inputEvent",id);source.put("eventKind",event.get("event_kind"));source.put("evidenceEvents",decision.evidence());
        source.put("policy",POLICY);String document;
        try{document=json.writeValueAsString(source);}catch(Exception impossible){throw new IllegalStateException("시장 감시 근거 직렬화 실패",impossible);}
        String hash=sha(document);UUID assessment=UUID.randomUUID();
        jdbc.update("INSERT INTO market_surveillance_assessments(id,input_event,policy_id,outcome,reason,source_document,source_hash) VALUES(?,?,?,?,?,?,?)",
            assessment,id,POLICY,decision.outcome(),decision.reason(),document,hash);
        if("REVIEW_REQUIRED".equals(decision.outcome())){
            UUID signal=UUID.randomUUID(),investigation=UUID.randomUUID();
            jdbc.update("INSERT INTO market_surveillance_signals(id,assessment_id) VALUES(?,?)",signal,assessment);
            jdbc.update("INSERT INTO market_surveillance_cases(id,signal_id,status) VALUES(?,?,'OPEN')",investigation,signal);
            jdbc.update("INSERT INTO market_surveillance_case_events(id,case_id,event_type,next_status,detail) VALUES(?,?,'OPENED','OPEN',?)",UUID.randomUUID(),investigation,"POLICY:"+POLICY+":SIGNAL:"+decision.reason());
            audit.record(null,"MARKET_SURVEILLANCE_CASE_OPENED","market-surveillance-case:"+investigation,"SUCCESS","SIGNAL:"+signal);
        }
        jdbc.update("UPDATE market_surveillance_state SET observed_event=?,updated_at=clock_timestamp() WHERE singleton=true",id);
        audit.record(null,"MARKET_SURVEILLANCE_ASSESSED","market-event:"+id,"SUCCESS","POLICY:"+POLICY+":OUTCOME:"+decision.outcome()+":REASON:"+decision.reason());
        return Optional.of(new Assessment(assessment,id,decision.outcome(),decision.reason(),hash));
    }
    private Decision assess(Map<String,Object> event){
        long id=number(event,"id");String kind=(String)event.get("event_kind");
        if("TRADE".equals(kind)){
            if(number(event,"buyer_id")==number(event,"seller_id"))return review("SELF_MATCH_INVARIANT_BREACH",id);
            var previous=jdbc.queryForList("SELECT id,price FROM market_activity_events WHERE event_kind='TRADE' AND base_asset=? AND quote_asset=? AND id<? ORDER BY id DESC LIMIT 1",event.get("base_asset"),event.get("quote_asset"),id);
            if(!previous.isEmpty()){
                BigDecimal old=(BigDecimal)previous.getFirst().get("price"),current=(BigDecimal)event.get("price");
                if(current.subtract(old).abs().divide(old,8,java.math.RoundingMode.HALF_UP).compareTo(new BigDecimal("0.10"))>=0)
                    return new Decision("REVIEW_REQUIRED","ABRUPT_EXECUTION_PRICE",List.of(number(previous.getFirst(),"id"),id));
            }
        }else{
            Instant at=((Timestamp)event.get("event_at")).toInstant();
            var rows=jdbc.queryForList("""
                SELECT id FROM market_activity_events WHERE event_kind='CANCEL' AND user_id=? AND base_asset=? AND quote_asset=? AND side=?
                 AND filled_amount=0 AND event_at BETWEEN ? AND ? AND id<=? ORDER BY id
                """,event.get("user_id"),event.get("base_asset"),event.get("quote_asset"),event.get("side"),Timestamp.from(at.minusSeconds(300)),Timestamp.from(at),id);
            if(rows.size()>=5)return new Decision("REVIEW_REQUIRED","RAPID_UNFILLED_CANCELLATION",rows.stream().map(r->number(r,"id")).toList());
        }
        return new Decision("NO_SIGNAL","NO_SIGNAL_IN_THIS_POLICY",List.of(id));
    }
    private static Decision review(String reason,long id){return new Decision("REVIEW_REQUIRED",reason,List.of(id));}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static String sha(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
