package dustin.cex.domains.market;

import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 자동 신호를 담당자 사건으로 배정하고 관찰·종결을 삭제 없는 사건 이력으로 남긴다. */
@Service
@DependsOn("marketSurveillanceSchemaInitializer")
public class MarketSurveillanceCases {
    public record Case(UUID id,UUID signalId,String status,Long assigneeId,long version,Instant createdAt,Instant updatedAt){}
    public record Event(UUID id,String type,Long actorId,String detail,Instant recordedAt){}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;private final AuditService audit;private final TransactionTemplate tx;
    public MarketSurveillanceCases(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,AuditService audit,PlatformTransactionManager manager){this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);}
    public List<Case> page(int page){if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);return jdbc.query("SELECT * FROM market_surveillance_cases ORDER BY updated_at DESC,id DESC LIMIT 50 OFFSET ?",(r,n)->map(r),page*50);}
    public Case detail(UUID id){return read(id,false);}
    public List<Event> history(UUID id,int page){if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);read(id,false);return jdbc.query("SELECT * FROM market_surveillance_case_events WHERE case_id=? ORDER BY recorded_at,id LIMIT 100 OFFSET ?",(r,n)->new Event(r.getObject("id",UUID.class),r.getString("event_type"),(Long)r.getObject("actor_id"),r.getString("detail"),r.getTimestamp("recorded_at").toInstant()),id,page*100);}
    public Case claim(long actor,UUID sid,UUID id){return work(actor,"MARKET_CASE_CLAIM",()->tx.execute(status->{var user=users.lockById(actor).orElse(null);sessions.requireFresh(user,sid,UserRole.OPERATOR);var current=read(id,true);if(!"OPEN".equals(current.status()))throw denied(HttpStatus.CONFLICT);event(current,actor,sid,"CLAIMED","UNDER_REVIEW",actor,"담당자가 시장 신호 조사를 시작했습니다");jdbc.update("UPDATE market_surveillance_cases SET status='UNDER_REVIEW',assignee_id=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",actor,id);audit.record(actor,"MARKET_CASE_CLAIMED","market-surveillance-case:"+id,"SUCCESS","VERSION:"+(current.version()+1));return read(id,false);}));}
    public Event observe(long actor,UUID sid,UUID id,String detail){return work(actor,"MARKET_CASE_OBSERVE",()->tx.execute(status->{var user=users.lockById(actor).orElse(null);sessions.requireFresh(user,sid,UserRole.OPERATOR);var current=read(id,true);assigned(current,actor);if(detail==null||detail.isBlank()||detail.length()>1000)throw denied(HttpStatus.BAD_REQUEST);UUID event=event(current,actor,sid,"OBSERVATION",null,null,detail);audit.record(actor,"MARKET_CASE_OBSERVED","market-surveillance-case:"+id,"SUCCESS","EVENT:"+event);return new Event(event,"OBSERVATION",actor,detail,now());}));}
    public Case close(long actor,UUID sid,UUID id,String outcome,String detail){return work(actor,"MARKET_CASE_CLOSE",()->tx.execute(status->{var user=users.lockById(actor).orElse(null);sessions.requireFresh(user,sid,UserRole.OPERATOR);var current=read(id,true);assigned(current,actor);if(!List.of("CLOSED_NO_ACTION","ESCALATED").contains(outcome)||detail==null||detail.isBlank()||detail.length()>1000)throw denied(HttpStatus.BAD_REQUEST);event(current,actor,sid,outcome,outcome,actor,detail);jdbc.update("UPDATE market_surveillance_cases SET status=?,version=version+1,updated_at=clock_timestamp() WHERE id=?",outcome,id);audit.record(actor,"MARKET_CASE_CLOSED","market-surveillance-case:"+id,"SUCCESS",outcome+":VERSION:"+(current.version()+1));return read(id,false);}));}
    private UUID event(Case current,long actor,UUID sid,String type,String next,Long assignee,String detail){var user=users.findById(actor).orElseThrow();UUID id=UUID.randomUUID();jdbc.update("INSERT INTO market_surveillance_case_events(id,case_id,event_type,actor_id,actor_person,actor_version,session_id,next_status,next_assignee,detail) VALUES(?,?,?,?,?,?,?,?,?,?)",id,current.id(),type,actor,user.getOperatorPersonId(),user.getVersion(),sid,next,assignee,detail);return id;}
    private void assigned(Case current,long actor){if(!"UNDER_REVIEW".equals(current.status())||!Objects.equals(current.assigneeId(),actor))throw denied(HttpStatus.FORBIDDEN);}
    private Case read(UUID id,boolean lock){var rows=jdbc.query("SELECT * FROM market_surveillance_cases WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->map(r),id);if(rows.size()!=1)throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();}
    private static Case map(java.sql.ResultSet r)throws java.sql.SQLException{return new Case(r.getObject("id",UUID.class),r.getObject("signal_id",UUID.class),r.getString("status"),(Long)r.getObject("assignee_id"),r.getLong("version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private <T>T work(long actor,String action,Supplier<T> work){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("시장 조사 업무는 독립 트랜잭션으로 실행하세요");try{return work.get();}catch(ResponseStatusException failure){audit.denied(actor,action,"market-case-restricted","POLICY_"+failure.getStatusCode().value());throw failure;}}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 시장 조사 사건과 담당 권한을 확인하세요");}
}
