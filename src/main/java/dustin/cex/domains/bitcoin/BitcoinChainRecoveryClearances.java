package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.model.entity.*;
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
import tools.jackson.databind.ObjectMapper;

/** 실물 복구가 증명된 위험 구간만 독립 승인한다. 원장·잔액·고객의 다른 제한은 수정하지 않는다. */
@Service
@DependsOn("bitcoinCreditSchemaInitializer")
public class BitcoinChainRecoveryClearances {
    public static final String POLICY="BTC_CHAIN_RECOVERY_CLEARANCE_V1";
    /** 서명 내용에 입금·위험 구간·금융 지문·두 대사·블록 앵커·지정 인물을 함께 고정한다. */
    public record Payload(UUID id,UUID proofId,String proofHash,UUID receiptId,long customerId,String asset,String amount,
        long riskCount,long firstRiskId,long lastRiskId,String scopeHash,String ledgerHash,UUID financialRun,UUID nativeRun,
        String blockHash,long blockHeight,long requesterId,UUID requesterPerson,long requesterVersion,
        long checkerId,UUID checkerPerson,long checkerVersion,Instant expiresAt,String policy){}
    public record Request(Payload payload,String payloadHash,String status){}
    public record Confirmation(Request request,boolean approve,OperatorMfaService.Options authentication){}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final OperatorMfaService mfa;private final AuditService audit;private final ObjectMapper json;private final TransactionTemplate tx;
    public BitcoinChainRecoveryClearances(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,OperatorMfaService mfa,
        AuditService audit,ObjectMapper json,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.mfa=mfa;this.audit=audit;this.json=json;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Request propose(long actor,UUID sid,UUID proofId,long checker,UUID key){return work(actor,sid,"BTC_CHAIN_CLEARANCE_PROPOSE",()->{
        var source=proofSource(proofId);long customer=((Number)source.get("user_id")).longValue();
        var people=lock(actor,checker,customer);var maker=people.get(actor);sessions.requireFresh(maker,sid,UserRole.OPERATOR);
        var reviewer=people.get(checker);
        if(key==null||key.equals(new UUID(0,0)))throw denied(HttpStatus.BAD_REQUEST);
        if(reviewer==null||!reviewer.isAuthenticationAllowed()||reviewer.getRole()!=UserRole.APPROVER||reviewer.getOperatorPersonId()==null
           ||actor==checker||actor==customer||checker==customer||maker.getOperatorPersonId().equals(reviewer.getOperatorPersonId()))throw denied(HttpStatus.FORBIDDEN);
        if(((Number)source.get("actor_id")).longValue()!=actor)throw denied(HttpStatus.FORBIDDEN);
        var old=jdbc.queryForList("SELECT id FROM bitcoin_chain_clearance_requests WHERE requester_id=? AND request_key=?",UUID.class,actor,key);
        if(!old.isEmpty()){var request=read(old.getFirst());if(!request.payload().proofId().equals(proofId)||request.payload().checkerId()!=checker)throw denied(HttpStatus.CONFLICT);
            audit.record(actor,"BTC_CHAIN_CLEARANCE_REPLAYED","bitcoin-chain-clearance:"+request.payload().id(),"SUCCESS",request.payloadHash());return request;}
        requireCurrent(proofId);source=proofSource(proofId);var basis=jdbc.queryForMap("SELECT * FROM bitcoin_chain_recovery_basis(?)",source.get("receipt_id"));
        var money=jdbc.queryForMap("SELECT asset,amount FROM bitcoin_credit_authorizations WHERE receipt_id=?",source.get("receipt_id"));
        UUID id=UUID.randomUUID();Instant expiry=((Timestamp)source.get("expires_at")).toInstant();
        var p=new Payload(id,proofId,(String)source.get("payload_hash"),(UUID)source.get("receipt_id"),customer,(String)money.get("asset"),
            ((java.math.BigDecimal)money.get("amount")).setScale(8,java.math.RoundingMode.UNNECESSARY).toPlainString(),number(basis,"risk_count"),number(basis,"first_risk_id"),number(basis,"last_risk_id"),
            (String)source.get("scope_hash"),(String)source.get("ledger_hash"),(UUID)source.get("financial_run"),(UUID)source.get("native_run"),(String)source.get("block_hash"),number(source,"block_height"),
            actor,maker.getOperatorPersonId(),maker.getVersion(),checker,reviewer.getOperatorPersonId(),reviewer.getVersion(),expiry,POLICY);
        String raw=json.writeValueAsString(p),hash=BitcoinCreditBasis.digest(raw);
        jdbc.update("INSERT INTO bitcoin_chain_clearance_requests(id,probe_id,receipt_id,user_id,requester_id,checker_id,request_key,last_risk_id,payload,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            id,proofId,p.receiptId(),customer,actor,checker,key,p.lastRiskId(),raw,hash,Timestamp.from(expiry));
        mfa.bindApproval(OperatorMfaService.ApprovalKind.BTC_CHAIN_RECOVERY,id,actor,maker.getVersion(),hash,expiry);
        audit.record(actor,"BTC_CHAIN_CLEARANCE_PROPOSED","bitcoin-chain-clearance:"+id,"SUCCESS",hash);return read(id);
    });}
    /** 지정된 두 사람만 내용을 읽는다. 승인자는 같은 현재 세션에서 실제 열람한 내용에 대해 다시 서명해야 한다. */
    public Request inspect(long actor,UUID sid,UUID id){return work(actor,sid,"BTC_CHAIN_CLEARANCE_INSPECT",()->{
        var r=read(id);assigned(actor,sid,r);UUID view=UUID.randomUUID();var user=users.findById(actor).orElseThrow();
        jdbc.update("INSERT INTO bitcoin_chain_clearance_views(id,request_id,actor_id,actor_person,actor_version,session_id,payload_hash) VALUES(?,?,?,?,?,?,?)",
            view,id,actor,user.getOperatorPersonId(),user.getVersion(),sid,r.payloadHash());
        audit.record(actor,"BTC_CHAIN_CLEARANCE_VIEWED","bitcoin-chain-clearance-view:"+view,"SUCCESS",r.payloadHash());return r;
    });}
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve){return work(actor,sid,"BTC_CHAIN_CLEARANCE_OPTIONS",()->{
        var r=check(actor,sid,id,hash);return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.BTC_CHAIN_RECOVERY,id,hash,approve));
    });}
    /** 서명 소비·독립 결정·필수 감사를 하나의 TX로 확정한다. 엔진 금융 명령이나 노드 RPC를 이 구간에 넣지 않는다. */
    public Request decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof){return work(actor,sid,"BTC_CHAIN_CLEARANCE_DECIDE",()->{
        check(actor,sid,id,hash);mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.BTC_CHAIN_RECOVERY,id,hash,approve,proof);
        jdbc.update("INSERT INTO bitcoin_chain_clearance_decisions(request_id,approver_id,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?)",id,actor,sid,approve,hash,proof.challengeId());
        audit.record(actor,"BTC_CHAIN_CLEARANCE_DECIDED","bitcoin-chain-clearance:"+id,"SUCCESS",hash);return read(id);
    });}
    private Request check(long actor,UUID sid,UUID id,String hash){
        var initial=read(id);assigned(actor,sid,initial);var p=initial.payload();
        if(actor!=p.checkerId())throw denied(HttpStatus.FORBIDDEN);
        // 모든 사용자 잠금 뒤 결정/현재 사실을 다시 읽는다. 병렬 승인과 새 위험 공개를 같은 고객 잠금으로 직렬화한다.
        var current=read(id);if(!"PENDING".equals(current.status())||!Objects.equals(hash,current.payloadHash()))throw denied(HttpStatus.CONFLICT);
        requireCurrent(p.proofId());
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_chain_clearance_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=?)",
            Boolean.class,id,actor,p.checkerPerson(),p.checkerVersion(),sid,hash)))throw denied(HttpStatus.CONFLICT);
        return current;
    }
    private void assigned(long actor,UUID sid,Request r){
        var p=r.payload();var people=lock(actor,p.requesterId(),p.checkerId(),p.customerId());var current=people.get(actor);
        sessions.requireFresh(current,sid,UserRole.OPERATOR,UserRole.APPROVER);
        var maker=people.get(p.requesterId());var checker=people.get(p.checkerId());
        if(maker==null||checker==null||!maker.isAuthenticationAllowed()||!checker.isAuthenticationAllowed()||maker.getRole()!=UserRole.OPERATOR||checker.getRole()!=UserRole.APPROVER
          ||maker.getVersion()!=p.requesterVersion()||checker.getVersion()!=p.checkerVersion()||!p.requesterPerson().equals(maker.getOperatorPersonId())||!p.checkerPerson().equals(checker.getOperatorPersonId())
          ||p.requesterPerson().equals(p.checkerPerson())||(actor!=p.requesterId()&&actor!=p.checkerId()))throw denied(HttpStatus.FORBIDDEN);
    }
    private Map<String, Object> proofSource(UUID id){var rows=jdbc.queryForList("SELECT p.*,r.financial_run,r.native_run,r.block_hash,r.block_height,r.expires_at FROM bitcoin_chain_recovery_probes p LEFT JOIN bitcoin_chain_recovery_results r ON r.probe_id=p.id WHERE p.id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();}
    private void requireCurrent(UUID id){if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT bitcoin_chain_recovery_current(?)",Boolean.class,id)))throw denied(HttpStatus.CONFLICT);}
    private Request read(UUID id){
        var rows=jdbc.queryForList("SELECT r.payload,r.payload_hash,d.approved,r.expires_at>clock_timestamp() fresh FROM bitcoin_chain_clearance_requests r LEFT JOIN bitcoin_chain_clearance_decisions d ON d.request_id=r.id WHERE r.id=?",id);
        if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String raw=(String)row.get("payload");var p=json.readValue(raw,Payload.class);
        if(!p.id().equals(id)||!BitcoinCreditBasis.digest(raw).equals(row.get("payload_hash")))throw denied(HttpStatus.SERVICE_UNAVAILABLE);
        return new Request(p,(String)row.get("payload_hash"),row.get("approved")==null?(Boolean.TRUE.equals(row.get("fresh"))?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED"));
    }
    private Map<Long,User> lock(long...ids){var result=new HashMap<Long,User>();for(long id:Arrays.stream(ids).distinct().sorted().toArray())users.lockById(id).ifPresent(u->result.put(id,u));return result;}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private <T>T work(long actor,UUID sid,String action,Supplier<T> callback){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("체인 위험 해제는 독립 트랜잭션으로 실행하세요");try{return tx.execute(t->callback.get());}catch(ResponseStatusException denied){
        // 없는 원본도 현재 운영 인증을 먼저 요구한다. 이미 끝난 업무 TX 밖에서 행위자만 잠그므로 잠금 순서를 뒤집지 않는다.
        if(denied.getStatusCode().value()==404)tx.executeWithoutResult(t->sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.OPERATOR,UserRole.APPROVER));
        audit.denied(actor,action,"bitcoin-chain-clearance-restricted","POLICY_"+denied.getStatusCode().value());throw denied;}}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 지정 인물·복구 근거·위험/금융 범위·내용 결합 인증을 확인하세요");}
}
