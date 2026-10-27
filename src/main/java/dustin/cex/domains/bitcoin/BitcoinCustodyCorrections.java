package dustin.cex.domains.bitcoin;

import static dustin.cex.domains.ledger.LedgerService.p;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.ledger.LedgerService;
import dustin.cex.domains.security.*;
import java.math.BigDecimal;
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
import tools.jackson.databind.ObjectMapper;

/** 수탁 대사 차이를 지정 두 사람의 내용 결합 승인 뒤 새 균형 분개로만 정정한다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinCustodyCorrections {
    public static final String POLICY="BTC_CUSTODY_CORRECTION_V1";
    public record Payload(UUID id,UUID runId,String expectedBalance,String observedBalance,String difference,
        long requesterId,UUID requesterPerson,long requesterVersion,long checkerId,UUID checkerPerson,long checkerVersion,
        Instant expiresAt,String policy){}
    public record Request(Payload payload,String payloadHash,String status){}
    public record Confirmation(Request request,boolean approve,OperatorMfaService.Options authentication){}
    public record Execution(UUID requestId,UUID verificationRun,String operationKey,String status){}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final OperatorMfaService mfa;private final AuditService audit;private final ObjectMapper json;
    private final LedgerService ledger;private final BitcoinCustodyReconciliationService reconciliation;private final TransactionTemplate tx;
    public BitcoinCustodyCorrections(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,OperatorMfaService mfa,
        AuditService audit,ObjectMapper json,LedgerService ledger,BitcoinCustodyReconciliationService reconciliation,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.mfa=mfa;this.audit=audit;this.json=json;this.ledger=ledger;this.reconciliation=reconciliation;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Request propose(long actor,UUID sid,UUID run,long checker,UUID key){return work(actor,sid,"BTC_CUSTODY_CORRECTION_PROPOSE",()->{
        var people=lock(actor,checker);var maker=people.get(actor);sessions.requireFresh(maker,sid,UserRole.OPERATOR);var reviewer=people.get(checker);
        if(key==null||key.equals(new UUID(0,0)))throw denied(HttpStatus.BAD_REQUEST);
        if(reviewer==null||!reviewer.isAuthenticationAllowed()||reviewer.getRole()!=UserRole.APPROVER||actor==checker
          ||maker.getOperatorPersonId()==null||reviewer.getOperatorPersonId()==null||maker.getOperatorPersonId().equals(reviewer.getOperatorPersonId()))throw denied(HttpStatus.FORBIDDEN);
        var old=jdbc.queryForList("SELECT id FROM bitcoin_custody_correction_requests WHERE requester_id=? AND request_key=?",UUID.class,actor,key);
        if(!old.isEmpty()){var existing=read(old.getFirst());if(!existing.payload().runId().equals(run)||existing.payload().checkerId()!=checker)throw denied(HttpStatus.CONFLICT);audit.record(actor,"BTC_CUSTODY_CORRECTION_REPLAYED","bitcoin-custody-correction:"+existing.payload().id(),"SUCCESS",existing.payloadHash());return existing;}
        var source=source(run);BigDecimal expected=amount(source,"expected"),observed=amount(source,"actual"),difference=observed.subtract(expected);
        UUID id=UUID.randomUUID();Instant expiry=Instant.now().plusSeconds(600);var payload=new Payload(id,run,money(expected),money(observed),money(difference),actor,maker.getOperatorPersonId(),maker.getVersion(),checker,reviewer.getOperatorPersonId(),reviewer.getVersion(),expiry,POLICY);
        String raw=json.writeValueAsString(payload),hash=BitcoinCreditBasis.digest(raw);
        jdbc.update("INSERT INTO bitcoin_custody_correction_requests(id,run_id,requester_id,requester_person,requester_version,checker_id,checker_person,checker_version,request_key,expected_balance,observed_balance,difference,payload,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,run,actor,maker.getOperatorPersonId(),maker.getVersion(),checker,reviewer.getOperatorPersonId(),reviewer.getVersion(),key,expected,observed,difference,raw,hash,Timestamp.from(expiry));
        mfa.bindApproval(OperatorMfaService.ApprovalKind.BTC_CUSTODY_CORRECTION,id,actor,maker.getVersion(),hash,expiry);
        audit.record(actor,"BTC_CUSTODY_CORRECTION_PROPOSED","bitcoin-custody-correction:"+id,"SUCCESS",hash);return read(id);
    });}
    public Request inspect(long actor,UUID sid,UUID id){return work(actor,sid,"BTC_CUSTODY_CORRECTION_INSPECT",()->{
        var request=read(id);assigned(actor,sid,request);var current=users.findById(actor).orElseThrow();UUID view=UUID.randomUUID();
        jdbc.update("INSERT INTO bitcoin_custody_correction_views(id,request_id,actor_id,actor_person,actor_version,session_id,payload_hash) VALUES(?,?,?,?,?,?,?)",view,id,actor,current.getOperatorPersonId(),current.getVersion(),sid,request.payloadHash());
        audit.record(actor,"BTC_CUSTODY_CORRECTION_VIEWED","bitcoin-custody-correction-view:"+view,"SUCCESS",request.payloadHash());return request;
    });}
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve){return work(actor,sid,"BTC_CUSTODY_CORRECTION_OPTIONS",()->{var request=check(actor,sid,id,hash);return new Confirmation(request,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.BTC_CUSTODY_CORRECTION,id,hash,approve));});}
    public Request decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof){return work(actor,sid,"BTC_CUSTODY_CORRECTION_DECIDE",()->{
        check(actor,sid,id,hash);mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.BTC_CUSTODY_CORRECTION,id,hash,approve,proof);
        jdbc.update("INSERT INTO bitcoin_custody_correction_decisions(request_id,approver_id,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?)",id,actor,sid,approve,hash,proof.challengeId());
        audit.record(actor,"BTC_CUSTODY_CORRECTION_DECIDED","bitcoin-custody-correction:"+id,"SUCCESS",hash);return read(id);
    });}
    /** 승인만으로 금액을 움직이지 않는다. 새 노드 대사가 같은 차이를 다시 증명한 경우에만 별도 실행한다. */
    public Execution execute(UUID id){
        // 재시도는 노드를 다시 호출하기 전에 이미 확정된 실행 원본을 그대로 반환한다.
        var completed=jdbc.queryForList("SELECT verification_run,operation_key FROM bitcoin_custody_correction_executions WHERE request_id=?",id);
        if(!completed.isEmpty())return execution(id,completed.getFirst());
        var approved=read(id);if(!"APPROVED".equals(approved.status())||!approved.payload().expiresAt().isAfter(now()))throw denied(HttpStatus.CONFLICT);
        var verification=reconciliation.run();if(!"MISMATCH".equals(verification.status()))return new Execution(id,verification.id(),null,"STALE");
        return tx.execute(status->{var request=readForUpdate(id);
            var existing=jdbc.queryForList("SELECT verification_run,operation_key FROM bitcoin_custody_correction_executions WHERE request_id=?",id);
            if(!existing.isEmpty())return execution(id,existing.getFirst());
            if(!"APPROVED".equals(request.status())||!request.payload().expiresAt().isAfter(now()))throw denied(HttpStatus.CONFLICT);
            var expected=source(verification.id());BigDecimal before=new BigDecimal(request.payload().expectedBalance()),observed=new BigDecimal(request.payload().observedBalance());
            if(amount(expected,"expected").compareTo(before)!=0||amount(expected,"actual").compareTo(observed)!=0)throw denied(HttpStatus.CONFLICT);
            BigDecimal difference=observed.subtract(before);String operation="btc:custody-correction:"+id;
            jdbc.update("INSERT INTO bitcoin_custody_correction_executions(request_id,verification_run,operation_key) VALUES(?,?,?)",id,verification.id(),operation);
            ledger.record(operation,"BTC_CUSTODY_CORRECTION",List.of(p(0,"CUSTODY","BTC",difference.negate()),p(0,"SUSPENSE","BTC",difference)));
            audit.record(null,"BTC_CUSTODY_CORRECTION_EXECUTED","bitcoin-custody-correction:"+id,"SUCCESS","VERIFICATION:"+verification.id());
            return new Execution(id,verification.id(),operation,"EXECUTED");});
    }
    private Request check(long actor,UUID sid,UUID id,String hash){var request=read(id);assigned(actor,sid,request);var p=request.payload();if(actor!=p.checkerId()||!"PENDING".equals(request.status())||!Objects.equals(hash,request.payloadHash()))throw denied(HttpStatus.CONFLICT);if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_custody_correction_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND viewed_at>=clock_timestamp()-interval '5 minutes')",Boolean.class,id,actor,p.checkerPerson(),p.checkerVersion(),sid,hash)))throw denied(HttpStatus.CONFLICT);source(p.runId());return request;}
    private void assigned(long actor,UUID sid,Request request){var p=request.payload();var people=lock(actor,p.requesterId(),p.checkerId());var current=people.get(actor);sessions.requireFresh(current,sid,UserRole.OPERATOR,UserRole.APPROVER);var maker=people.get(p.requesterId());var checker=people.get(p.checkerId());if(maker==null||checker==null||!maker.isAuthenticationAllowed()||!checker.isAuthenticationAllowed()||maker.getRole()!=UserRole.OPERATOR||checker.getRole()!=UserRole.APPROVER||maker.getVersion()!=p.requesterVersion()||checker.getVersion()!=p.checkerVersion()||!p.requesterPerson().equals(maker.getOperatorPersonId())||!p.checkerPerson().equals(checker.getOperatorPersonId())||(actor!=p.requesterId()&&actor!=p.checkerId()))throw denied(HttpStatus.FORBIDDEN);}
    private Map<String,Object> source(UUID run){var rows=jdbc.queryForList("SELECT i.expected,i.actual FROM bitcoin_custody_reconciliation_runs r JOIN bitcoin_custody_reconciliation_issues i ON i.run_id=r.id AND i.code='CUSTODY_BALANCE_MISMATCH' WHERE r.id=? AND r.status='MISMATCH' AND r.completed_at>clock_timestamp()-interval '10 minutes'",run);if(rows.size()!=1)throw denied(HttpStatus.CONFLICT);return rows.getFirst();}
    private Request readForUpdate(UUID id){jdbc.queryForObject("SELECT id FROM bitcoin_custody_correction_requests WHERE id=? FOR UPDATE",UUID.class,id);return read(id);}
    private Request read(UUID id){var rows=jdbc.queryForList("SELECT r.*,d.approved,x.request_id executed FROM bitcoin_custody_correction_requests r LEFT JOIN bitcoin_custody_correction_decisions d ON d.request_id=r.id LEFT JOIN bitcoin_custody_correction_executions x ON x.request_id=r.id WHERE r.id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();try{String raw=(String)row.get("payload");var payload=json.readValue(raw,Payload.class);if(!payload.id().equals(id)||!payload.runId().equals(row.get("run_id"))||payload.requesterId()!=number(row,"requester_id")||payload.checkerId()!=number(row,"checker_id")||!payload.requesterPerson().equals(row.get("requester_person"))||!payload.checkerPerson().equals(row.get("checker_person"))||payload.requesterVersion()!=number(row,"requester_version")||payload.checkerVersion()!=number(row,"checker_version")||new BigDecimal(payload.expectedBalance()).compareTo(amount(row,"expected_balance"))!=0||new BigDecimal(payload.observedBalance()).compareTo(amount(row,"observed_balance"))!=0||new BigDecimal(payload.difference()).compareTo(amount(row,"difference"))!=0||!payload.expiresAt().equals(((Timestamp)row.get("expires_at")).toInstant())||!POLICY.equals(payload.policy())||!BitcoinCreditBasis.digest(raw).equals(row.get("payload_hash")))throw denied(HttpStatus.SERVICE_UNAVAILABLE);String state=row.get("executed")!=null?"EXECUTED":row.get("approved")==null?(payload.expiresAt().isAfter(now())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED");return new Request(payload,(String)row.get("payload_hash"),state);}catch(RuntimeException invalid){throw denied(HttpStatus.SERVICE_UNAVAILABLE);}}
    private Map<Long,User> lock(long... ids){var result=new HashMap<Long,User>();for(long id:Arrays.stream(ids).distinct().sorted().toArray())users.lockById(id).ifPresent(user->result.put(id,user));return result;}
    private <T>T work(long actor,UUID sid,String action,Supplier<T> callback){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("수탁 정정은 독립 트랜잭션으로 실행하세요");try{return tx.execute(status->callback.get());}catch(ResponseStatusException failure){audit.denied(actor,action,"bitcoin-custody-correction-restricted","POLICY_"+failure.getStatusCode().value());throw failure;}}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static Execution execution(UUID id,Map<String,Object> row){return new Execution(id,(UUID)row.get("verification_run"),(String)row.get("operation_key"),"EXECUTED");}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static BigDecimal amount(Map<String,Object> row,String key){var value=row.get(key);return value instanceof BigDecimal number?number:new BigDecimal(String.valueOf(value));}
    private static String money(BigDecimal value){return value.setScale(8,java.math.RoundingMode.UNNECESSARY).toPlainString();}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 수탁 대사·지정 인물·내용 결합 승인 상태를 확인하세요");}
}
