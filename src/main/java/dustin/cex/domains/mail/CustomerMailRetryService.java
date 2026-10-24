package dustin.cex.domains.mail;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 소진된 서비스 알림에만 한 번의 추가 시도 묶음을 승인한다. 토큰/주소/본문을 새로 만들지 않는다. */
@Service
public class CustomerMailRetryService {
    public static final String POLICY="CUSTOMER_MAIL_RETRY_V1";
    public record Person(long id,UUID person,long version) {}
    public record Payload(UUID id,UUID jobId,String kind,String jobMac,String jobKeyId,long customerVersion,
        int attempts,Person requester,Person checker,Instant mailExpiresAt,Instant expiresAt,String policy,String keyId) {}
    public record Request(Payload payload,String payloadHash,String status) {}
    public record Confirmation(Request request,boolean approve,OperatorMfaService.Options authentication) {}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final CddCrypto crypto;private final ObjectMapper json;private final DiditWebhookVerifier digests;
    private final OperatorMfaService mfa;private final CustomerMailOutbox outbox;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerMailRetryService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,CddCrypto crypto,ObjectMapper json,DiditWebhookVerifier digests,OperatorMfaService mfa,CustomerMailOutbox outbox,AuditService audit,PlatformTransactionManager manager){this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.crypto=crypto;this.json=json;this.digests=digests;this.mfa=mfa;this.outbox=outbox;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}

    @Transactional(propagation=Propagation.NEVER)
    public Request propose(long actor,UUID sid,UUID jobId,UUID key,long checker){return attempt(actor,()->tx.execute(t->{
        if(jobId==null||key==null||checker<=0)throw denied(HttpStatus.BAD_REQUEST);
        var job=job(jobId);var people=lock(actor,checker,((Number)job.get("user_id")).longValue());var maker=people.get(actor);var approver=people.get(checker);
        sessions.requireFresh(maker,sid,UserRole.OPERATOR);crypto.requireConfigured();
        var old=jdbc.queryForList("SELECT id FROM customer_mail_retry_requests WHERE requester_id=? AND request_key=?",actor,key);
        if(!old.isEmpty()){var r=read((UUID)old.getFirst().get("id"));if(!r.payload().jobId().equals(jobId)||r.payload().checker().id()!=checker)throw denied(HttpStatus.CONFLICT);audit.record(actor,"CUSTOMER_MAIL_RETRY_REPLAYED","mail-retry:"+r.payload().id(),"SUCCESS","HISTORICAL_REQUEST");return r;}
        requirePerson(approver,UserRole.APPROVER);if(maker.getOperatorPersonId().equals(approver.getOperatorPersonId()))throw denied(HttpStatus.CONFLICT);
        var delivery=delivery(jobId);eligible(job,delivery);long owner=((Number)job.get("user_id")).longValue();
        if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_mail_retry_requests r WHERE job_id=? AND expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM customer_mail_retry_decisions d WHERE d.request_id=r.id))",Boolean.class,jobId))throw denied(HttpStatus.CONFLICT);
        if(jdbc.queryForObject("SELECT count(*) FROM customer_mail_retry_requests WHERE job_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,jobId)>=10)throw denied(HttpStatus.TOO_MANY_REQUESTS);
        Instant mailExpiry=((Timestamp)job.get("expires_at")).toInstant(),expires=Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);if(mailExpiry.isBefore(expires))expires=mailExpiry;
        UUID id=UUID.randomUUID();var p=new Payload(id,jobId,(String)job.get("kind"),(String)job.get("content_mac"),(String)job.get("key_id"),people.get(owner).getVersion(),5,person(maker),person(approver),mailExpiry,expires,POLICY,crypto.keyId());String raw=json.writeValueAsString(p),hash=digests.digest(json.readTree(raw));
        jdbc.update("INSERT INTO customer_mail_retry_requests(id,job_id,requester_id,checker_id,request_key,payload,payload_hash,payload_mac,key_id,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,jobId,actor,checker,key,raw,hash,mac(raw),crypto.keyId(),Timestamp.from(expires));
        mfa.bindApproval(OperatorMfaService.ApprovalKind.CUSTOMER_MAIL_RETRY,id,actor,maker.getVersion(),hash,expires);
        audit.record(actor,"CUSTOMER_MAIL_RETRY_PROPOSED","mail-retry:"+id,"SUCCESS","ONE_EXTRA_BATCH_ONLY");return read(id);
    }));}
    public Request inspect(long actor,UUID sid,UUID id){return attempt(actor,()->tx.execute(t->{var r=read(id);var people=check(r,actor,sid,false,false);var u=people.get(actor);
        // 원문 대신 같은 작업·시도 수·기한·두 인물에 결합된 계획을 제공한 사실만 남긴다.
        jdbc.update("INSERT INTO customer_mail_retry_views(id,request_id,actor_id,actor_person,actor_version,session_id,payload_hash) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),id,actor,u.getOperatorPersonId(),u.getVersion(),sid,r.payloadHash());audit.record(actor,"CUSTOMER_MAIL_RETRY_INSPECTED","mail-retry:"+id,"SUCCESS","NO_RECIPIENT_OR_TOKEN");return r;
    }));}
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve){return attempt(actor,()->tx.execute(t->{var r=read(id);check(r,actor,sid,true,true);if(!r.payloadHash().equals(hash))throw denied(HttpStatus.CONFLICT);return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.CUSTOMER_MAIL_RETRY,id,hash,approve));}));}
    @Transactional(propagation=Propagation.NEVER)
    public Request decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof){return attempt(actor,()->tx.execute(t->{
        var r=read(id);check(r,actor,sid,true,true);if(!r.payloadHash().equals(hash))throw denied(HttpStatus.CONFLICT);var p=r.payload();
        mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.CUSTOMER_MAIL_RETRY,id,hash,approve,proof);
        jdbc.update("INSERT INTO customer_mail_retry_decisions(request_id,job_id,approver_id,approved) VALUES(?,?,?,?)",id,p.jobId(),actor,approve);
        if(approve){
            // 누적 시도 수를 초기화하지 않는다. 원래5회에 딱 한 번5회를 더하며 메시지 ID/본문/기한은 유지한다.
            jdbc.update("INSERT INTO customer_mail_retry_grants(job_id,request_id,approved,max_attempts) VALUES(?,?,true,10)",p.jobId(),id);
            jdbc.update("UPDATE customer_mail_delivery SET status='RETRY',next_attempt_at=clock_timestamp(),last_reason='MANUAL_RETRY_APPROVED' WHERE job_id=?",p.jobId());
        }
        audit.record(actor,"CUSTOMER_MAIL_RETRY_DECIDED","mail-retry:"+id,"SUCCESS",approve?"APPROVED":"REJECTED");return read(id);
    }));}
    public Request status(long actor,UUID sid,UUID id){return attempt(actor,()->tx.execute(t->{var r=read(id);var p=r.payload();var user=users.lockById(actor).orElse(null);sessions.requireFresh(user,sid,UserRole.OPERATOR,UserRole.APPROVER);if(!samePerson(user,p.requester())&&!samePerson(user,p.checker()))throw denied(HttpStatus.NOT_FOUND);audit.record(actor,"CUSTOMER_MAIL_RETRY_STATUS","mail-retry:"+id,"SUCCESS","OWN_REQUEST");return r;}));}

    private Map<Long,User> check(Request r,long actor,UUID sid,boolean approval,boolean viewed){
        var p=r.payload();var job=job(p.jobId());long owner=((Number)job.get("user_id")).longValue();var people=lock(p.requester().id(),p.checker().id(),owner);
        var maker=people.get(p.requester().id());var checker=people.get(p.checker().id());var caller=people.get(actor);
        sessions.requireFresh(caller,sid,UserRole.OPERATOR,UserRole.APPROVER);
        if((approval&&actor!=p.checker().id())||(!samePerson(caller,p.requester())&&!samePerson(caller,p.checker())))throw denied(HttpStatus.FORBIDDEN);
        requirePerson(maker,UserRole.OPERATOR);requirePerson(checker,UserRole.APPROVER);
        if(!person(maker).equals(p.requester())||!person(checker).equals(p.checker())||maker.getOperatorPersonId().equals(checker.getOperatorPersonId())||people.get(owner).getVersion()!=p.customerVersion())throw denied(HttpStatus.CONFLICT);
        var delivery=delivery(p.jobId());
        // 출력 행 잠금을 기다린 뒤 READ COMMITTED로 현재 결정/자격을 다시 읽는다. 옛 PENDING 관측을 승인 근거로 쓰지 않는다.
        if(!read(p.id()).status().equals("PENDING")||!p.policy().equals(POLICY)||!p.keyId().equals(crypto.keyId())||!p.jobMac().equals(job.get("content_mac"))||!p.jobKeyId().equals(job.get("key_id"))||!p.kind().equals(job.get("kind"))||!p.mailExpiresAt().equals(((Timestamp)job.get("expires_at")).toInstant())||p.attempts()!=5)throw denied(HttpStatus.CONFLICT);
        eligible(job,delivery);
        if(viewed&&jdbc.queryForObject("SELECT count(*) FROM customer_mail_retry_views WHERE request_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND viewed_at>clock_timestamp()-interval '5 minutes'",Long.class,p.id(),actor,caller.getOperatorPersonId(),caller.getVersion(),sid,r.payloadHash())==0)throw denied(HttpStatus.CONFLICT);
        return people;
    }
    private void eligible(Map<String,Object> job,Map<String,Object> delivery){
        UUID id=(UUID)job.get("id");
        if(!Set.of("STATUS_NOTICE","FINAL_NOTICE").contains(job.get("kind"))||!"EXHAUSTED".equals(delivery.get("status"))||((Number)delivery.get("attempts")).intValue()!=5||jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_mail_retry_grants WHERE job_id=?)",Boolean.class,id))throw denied(HttpStatus.CONFLICT);
        if(!outbox.eligible(outbox.open(id)))throw denied(HttpStatus.CONFLICT);
    }
    private Map<String,Object> job(UUID id){var rows=jdbc.queryForList("SELECT * FROM customer_mail_jobs WHERE id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();}
    private Map<String,Object> delivery(UUID id){return jdbc.queryForMap("SELECT * FROM customer_mail_delivery WHERE job_id=? FOR UPDATE",id);}
    private Map<Long,User> lock(long... ids){var result=new HashMap<Long,User>();Arrays.stream(ids).distinct().sorted().forEach(id->result.put(id,users.lockById(id).orElse(null)));return result;}
    private Person person(User u){return new Person(u.getId(),u.getOperatorPersonId(),u.getVersion());}
    private boolean samePerson(User u,Person p){return u!=null&&u.getId()==p.id()&&p.person().equals(u.getOperatorPersonId());}
    private void requirePerson(User u,UserRole role){if(u==null||!u.isAuthenticationAllowed()||u.getRole()!=role||u.getOperatorPersonId()==null)throw denied(HttpStatus.CONFLICT);}
    private Request read(UUID id){var rows=jdbc.queryForList("SELECT * FROM customer_mail_retry_requests WHERE id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String raw=(String)row.get("payload");var p=json.readValue(raw,Payload.class);String hash=digests.digest(json.readTree(raw));
        if(!crypto.keyId().equals(row.get("key_id"))||!mac(raw).equals(row.get("payload_mac"))||!hash.equals(row.get("payload_hash"))||!p.id().equals(id)||!p.jobId().equals(row.get("job_id"))||p.requester().id()!=((Number)row.get("requester_id")).longValue()||p.checker().id()!=((Number)row.get("checker_id")).longValue()||!p.expiresAt().equals(((Timestamp)row.get("expires_at")).toInstant()))throw new IllegalStateException("메일 재시도 승인 근거 검증 실패");
        var decisions=jdbc.queryForList("SELECT approved FROM customer_mail_retry_decisions WHERE request_id=?",id);String status=decisions.isEmpty()?(p.expiresAt().isAfter(Instant.now())?"PENDING":"EXPIRED"):(Boolean.TRUE.equals(decisions.getFirst().get("approved"))?"APPROVED":"REJECTED");return new Request(p,hash,status);
    }
    private String mac(String raw){return crypto.fingerprint("customer-mail-retry-v1",raw);}
    private <T>T attempt(long actor,Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_MAIL_RETRY_POLICY","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 독립 인물·소진 작업·수신 자격·기한·서명 근거를 확인하세요");}
}
