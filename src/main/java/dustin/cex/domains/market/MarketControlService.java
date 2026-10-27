package dustin.cex.domains.market;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 시장 중단·재개를 지정된 두 사람의 현재 역할, 실제 열람, 내용 결합 보안키 서명에 묶는다. */
@Service
@DependsOn("marketSurveillanceSchemaInitializer")
public class MarketControlService {
    public static final String POLICY="MARKET_STATE_V1";
    public record State(String baseAsset,String quoteAsset,String state,long version,Instant updatedAt){}
    public record Payload(UUID id,String baseAsset,String quoteAsset,String expectedState,long expectedVersion,String desiredState,UUID signalId,
        long requesterId,UUID requesterPerson,long requesterVersion,long checkerId,UUID checkerPerson,long checkerVersion,String reason,Instant expiresAt,String policy){}
    public record Request(Payload payload,String payloadHash,String status){}
    public record Confirmation(Request request,boolean approve,OperatorMfaService.Options authentication){}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final OperatorMfaService mfa;private final AuditService audit;private final ObjectMapper json;private final TransactionTemplate tx;
    public MarketControlService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,OperatorMfaService mfa,
        AuditService audit,ObjectMapper json,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.mfa=mfa;this.audit=audit;this.json=json;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public State state(String base,String quote){return mapState(jdbc.queryForMap("SELECT * FROM market_trading_states WHERE base_asset=? AND quote_asset=?",asset(base),asset(quote)));}

    /** 주문 예약 트랜잭션이 끝날 때까지 시장 변경을 기다리게 하고, 변경 뒤의 신규 주문은 같은 행에서 차단한다. */
    public void requireOpen(String base,String quote){
        var rows=jdbc.queryForList("SELECT * FROM market_trading_states WHERE base_asset=? AND quote_asset=? FOR SHARE",asset(base),asset(quote));
        if(rows.size()!=1||!"OPEN".equals(rows.getFirst().get("state")))throw denied(HttpStatus.SERVICE_UNAVAILABLE,"현재 시장의 신규 주문이 중단되었습니다");
    }
    public Request propose(long actor,UUID sid,String base,String quote,String desired,UUID signal,long checker,String reason,UUID key){
        String selectedBase=asset(base),selectedQuote=asset(quote);
        return work(actor,"MARKET_STATE_PROPOSE",()->tx.execute(status->{
        if(!List.of("OPEN","HALTED").contains(desired)||reason==null||reason.isBlank()||reason.length()>300||key==null)throw denied(HttpStatus.BAD_REQUEST,"시장 상태 요청이 올바르지 않습니다");
        var people=lock(actor,checker);var maker=people.get(actor);sessions.requireFresh(maker,sid,UserRole.OPERATOR);var reviewer=people.get(checker);
        if(reviewer==null||!reviewer.isAuthenticationAllowed()||reviewer.getRole()!=UserRole.APPROVER||maker.getOperatorPersonId()==null||reviewer.getOperatorPersonId()==null
            ||actor==checker||maker.getOperatorPersonId().equals(reviewer.getOperatorPersonId()))throw denied(HttpStatus.FORBIDDEN,"서로 다른 담당자와 승인자가 필요합니다");
        var replay=jdbc.queryForList("SELECT id FROM market_state_requests WHERE requester_id=? AND request_key=?",UUID.class,actor,key);
        if(!replay.isEmpty()){var old=read(replay.getFirst(),false);if(!old.payload().baseAsset().equals(selectedBase)||!old.payload().quoteAsset().equals(selectedQuote)||!old.payload().desiredState().equals(desired)||!Objects.equals(old.payload().signalId(),signal)||old.payload().checkerId()!=checker||!old.payload().reason().equals(reason))throw denied(HttpStatus.CONFLICT,"같은 요청 키의 내용이 다릅니다");return old;}
        var current=lockState(selectedBase,selectedQuote);if(current.state().equals(desired))throw denied(HttpStatus.CONFLICT,"이미 요청한 시장 상태입니다");
        if("HALTED".equals(desired)&&!signalExists(signal,selectedBase,selectedQuote))throw denied(HttpStatus.CONFLICT,"중단에는 현재 시장 감시 신호가 필요합니다");
        if("OPEN".equals(desired)&&signal!=null)throw denied(HttpStatus.BAD_REQUEST,"재개 요청은 과거 신호 하나를 근거로 고정하지 않습니다");
        UUID id=UUID.randomUUID();Instant expiry=now().plusSeconds(600);var payload=new Payload(id,selectedBase,selectedQuote,current.state(),current.version(),desired,signal,actor,maker.getOperatorPersonId(),maker.getVersion(),checker,reviewer.getOperatorPersonId(),reviewer.getVersion(),reason,expiry,POLICY);
        String raw=json.writeValueAsString(payload),hash=digest(raw);
        jdbc.update("""
            INSERT INTO market_state_requests(id,base_asset,quote_asset,expected_state,expected_version,desired_state,signal_id,requester_id,requester_person,requester_version,checker_id,checker_person,checker_version,request_key,reason,payload,payload_hash,expires_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,selectedBase,selectedQuote,current.state(),current.version(),desired,signal,actor,maker.getOperatorPersonId(),maker.getVersion(),checker,reviewer.getOperatorPersonId(),reviewer.getVersion(),key,reason,raw,hash,Timestamp.from(expiry));
        mfa.bindApproval(OperatorMfaService.ApprovalKind.MARKET_STATE,id,actor,maker.getVersion(),hash,expiry);
        audit.record(actor,"MARKET_STATE_PROPOSED","market-state:"+id,"SUCCESS",hash);return read(id,false);
    }));}
    public Request inspect(long actor,UUID sid,UUID id){return work(actor,"MARKET_STATE_INSPECT",()->tx.execute(status->{
        var request=read(id,false);assigned(actor,sid,request);var current=users.findById(actor).orElseThrow();UUID view=UUID.randomUUID();
        jdbc.update("INSERT INTO market_state_views(id,request_id,actor_id,actor_person,actor_version,session_id,payload_hash) VALUES(?,?,?,?,?,?,?)",view,id,actor,current.getOperatorPersonId(),current.getVersion(),sid,request.payloadHash());
        audit.record(actor,"MARKET_STATE_VIEWED","market-state-view:"+view,"SUCCESS",request.payloadHash());return request;
    }));}
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve){return work(actor,"MARKET_STATE_OPTIONS",()->tx.execute(status->{var request=check(actor,sid,id,hash);return new Confirmation(request,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.MARKET_STATE,id,hash,approve));}));}
    public Request decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof){return work(actor,"MARKET_STATE_DECIDE",()->tx.execute(status->{
        var request=check(actor,sid,id,hash);mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.MARKET_STATE,id,hash,approve,proof);
        jdbc.update("INSERT INTO market_state_decisions(request_id,approver_id,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?)",id,actor,sid,approve,hash,proof.challengeId());
        if(approve){var p=request.payload();var current=lockState(p.baseAsset(),p.quoteAsset());if(!current.state().equals(p.expectedState())||current.version()!=p.expectedVersion())throw denied(HttpStatus.CONFLICT,"시장 상태가 요청 뒤 변경되었습니다");
            jdbc.update("INSERT INTO market_state_changes(request_id,base_asset,quote_asset,previous_state,previous_version,next_state,next_version) VALUES(?,?,?,?,?,?,?)",id,p.baseAsset(),p.quoteAsset(),p.expectedState(),p.expectedVersion(),p.desiredState(),p.expectedVersion()+1);
            jdbc.update("UPDATE market_trading_states SET state=?,version=version+1,updated_at=clock_timestamp() WHERE base_asset=? AND quote_asset=? AND version=?",p.desiredState(),p.baseAsset(),p.quoteAsset(),p.expectedVersion());
            audit.record(actor,"MARKET_STATE_CHANGED","market:"+p.baseAsset()+"/"+p.quoteAsset(),"SUCCESS",p.expectedState()+"->"+p.desiredState()+":REQUEST:"+id);}
        audit.record(actor,"MARKET_STATE_DECIDED","market-state:"+id,"SUCCESS",hash);return read(id,false);
    }));}
    private Request check(long actor,UUID sid,UUID id,String hash){var request=read(id,true);assigned(actor,sid,request);var p=request.payload();if(actor!=p.checkerId()||!"PENDING".equals(request.status())||!Objects.equals(hash,request.payloadHash())||!p.expiresAt().isAfter(now()))throw denied(HttpStatus.CONFLICT,"시장 상태 승인 원본이 현재와 다릅니다");if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM market_state_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND viewed_at>=clock_timestamp()-interval '5 minutes')",Boolean.class,id,actor,p.checkerPerson(),p.checkerVersion(),sid,hash)))throw denied(HttpStatus.CONFLICT,"현재 승인 내용을 먼저 열람해야 합니다");return request;}
    private void assigned(long actor,UUID sid,Request request){var p=request.payload();var people=lock(actor,p.requesterId(),p.checkerId());var current=people.get(actor);sessions.requireFresh(current,sid,UserRole.OPERATOR,UserRole.APPROVER);var maker=people.get(p.requesterId());var checker=people.get(p.checkerId());if(maker==null||checker==null||!maker.isAuthenticationAllowed()||!checker.isAuthenticationAllowed()||maker.getRole()!=UserRole.OPERATOR||checker.getRole()!=UserRole.APPROVER||maker.getVersion()!=p.requesterVersion()||checker.getVersion()!=p.checkerVersion()||!p.requesterPerson().equals(maker.getOperatorPersonId())||!p.checkerPerson().equals(checker.getOperatorPersonId())||(actor!=p.requesterId()&&actor!=p.checkerId()))throw denied(HttpStatus.FORBIDDEN,"현재 지정 인물이 아닙니다");}
    private Request read(UUID id,boolean lock){var rows=jdbc.queryForList("SELECT r.*,d.approved,c.request_id changed FROM market_state_requests r LEFT JOIN market_state_decisions d ON d.request_id=r.id LEFT JOIN market_state_changes c ON c.request_id=r.id WHERE r.id=?"+(lock?" FOR UPDATE OF r":""),id);if(rows.size()!=1)throw denied(HttpStatus.NOT_FOUND,"시장 상태 요청이 없습니다");var row=rows.getFirst();try{String raw=(String)row.get("payload");var payload=json.readValue(raw,Payload.class);if(!payload.id().equals(id)||!POLICY.equals(payload.policy())||!digest(raw).equals(row.get("payload_hash")))throw new IllegalStateException();String state=row.get("changed")!=null?"APPLIED":row.get("approved")==null?(payload.expiresAt().isAfter(now())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED");return new Request(payload,(String)row.get("payload_hash"),state);}catch(Exception invalid){throw denied(HttpStatus.SERVICE_UNAVAILABLE,"시장 상태 원본을 검증할 수 없습니다");}}
    private State lockState(String base,String quote){return mapState(jdbc.queryForMap("SELECT * FROM market_trading_states WHERE base_asset=? AND quote_asset=? FOR UPDATE",base,quote));}
    private static State mapState(Map<String,Object> row){return new State((String)row.get("base_asset"),(String)row.get("quote_asset"),(String)row.get("state"),((Number)row.get("version")).longValue(),((Timestamp)row.get("updated_at")).toInstant());}
    private boolean signalExists(UUID id,String base,String quote){return id!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM market_surveillance_signals s JOIN market_surveillance_assessments a ON a.id=s.assessment_id JOIN market_activity_events e ON e.id=a.input_event WHERE s.id=? AND e.base_asset=? AND e.quote_asset=?)",Boolean.class,id,base,quote));}
    private Map<Long,User> lock(long... ids){var result=new HashMap<Long,User>();for(long id:Arrays.stream(ids).distinct().sorted().toArray())users.lockById(id).ifPresent(user->result.put(id,user));return result;}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String asset(String value){if(value==null||!value.matches("[A-Z0-9]{2,20}"))throw denied(HttpStatus.BAD_REQUEST,"시장 자산이 올바르지 않습니다");return value;}
    private <T>T work(long actor,String action,Supplier<T> callback){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("시장 상태 업무는 독립 트랜잭션으로 실행하세요");try{return callback.get();}catch(ResponseStatusException failure){audit.denied(actor,action,"market-state-restricted","POLICY_"+failure.getStatusCode().value());throw failure;}}
    private static ResponseStatusException denied(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
