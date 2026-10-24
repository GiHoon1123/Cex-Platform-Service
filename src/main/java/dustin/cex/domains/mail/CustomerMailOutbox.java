package dustin.cex.domains.mail;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.AuditService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;

/** 업무와 함께 메일을 영속 예약한다. 외부 SMTP 전달은 업무 트랜잭션·고객 잠금 밖에서 수행한다. */
@Service
public class CustomerMailOutbox {
    public enum Kind { EMAIL_VERIFICATION, STATUS_NOTICE, FINAL_NOTICE }
    public record Payload(String policy,UUID id,long userId,Kind kind,UUID reference,String email,String token,Instant expiresAt){@Override public String toString(){return "Payload[수신 주소·토큰 생략]";}}
    public record Lease(UUID id,UUID token,int attempt) {}
    public record State(UUID id,String status,int attempts,String reason) {}
    private final CustomerMailRecipientPolicy recipients;
    private final JdbcTemplate jdbc;private final CddCrypto crypto;private final ObjectMapper json;private final UserRepository users;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerMailOutbox(JdbcTemplate jdbc,CddCrypto crypto,ObjectMapper json,UserRepository users,AuditService audit,PlatformTransactionManager manager,CustomerMailRecipientPolicy recipients){this.recipients=recipients;this.jdbc=jdbc;this.crypto=crypto;this.json=json;this.users=users;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    /**
     * 업무를 먼저 확정하고 나중에 메일을 저장하면 그 사이 장애로 통지가 사라진다.
     * 따라서 호출자의 트랜잭션에만 참여하며 암호문·예약·감사를 함께 확정한다.
     * 수신 주소와 토큰은 조회용 메타데이터에 평문으로 두지 않는다.
     */
    public UUID enqueue(long user,Kind kind,UUID reference,String email,String token,Instant expires){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("업무 트랜잭션에서만 메일을 예약합니다");
        if(reference==null||kind==null||email==null||email.codePoints().anyMatch(Character::isISOControl)||(kind==Kind.EMAIL_VERIFICATION?(token==null||!token.matches("[A-Za-z0-9_-]{43}")):token!=null)||expires==null||!expires.isAfter(Instant.now()))throw new IllegalArgumentException("메일 예약 근거가 올바르지 않습니다");
        expires=expires.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var old=jdbc.queryForList("SELECT id FROM customer_mail_jobs WHERE kind=? AND reference_id=?",kind.name(),reference);
        if(!old.isEmpty()){UUID id=(UUID)old.getFirst().get("id");var p=open(id);if(p.userId()!=user||!p.email().equals(email)||!Objects.equals(p.token(),token)||!p.expiresAt().equals(expires))throw new IllegalStateException("기존 메일과 다른 재예약입니다");return id;}
        UUID id=UUID.randomUUID();var p=new Payload("CUSTOMER_MAIL_V1",id,user,kind,reference,email,token,expires);String raw=json.writeValueAsString(p);
        jdbc.update("INSERT INTO customer_mail_jobs(id,user_id,kind,reference_id,content_mac,key_id,expires_at) VALUES(?,?,?,?,?,?,?)",id,user,kind.name(),reference,mac(raw),crypto.keyId(),Timestamp.from(expires));
        jdbc.update("INSERT INTO customer_mail_payloads(job_id,payload_cipher) VALUES(?,?)",id,crypto.encrypt(user,id,raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("INSERT INTO customer_mail_delivery(job_id,status) VALUES(?,'PENDING')",id);audit.record(user,"CUSTOMER_MAIL_QUEUED","mail:"+id,"SUCCESS","ENCRYPTED_PENDING");return id;
    }
    /**
     * 잠긴 다른 예약은 건너뛰므로 여러 출력 워커가 같은 예약을 동시에 소유하지 않는다.
     * 반환 직후 트랜잭션을 끝낸다. SMTP 대기 중에는 DB 잠금을 유지하지 않는다.
     * 임대 만료는 미발송 증명이 아니므로 재시도에 중복 발송 가능성이 남는다.
     */
    public Lease claim(){return tx.execute(t->{
        crypto.requireConfigured();var rows=jdbc.queryForList("SELECT d.job_id,d.attempts FROM customer_mail_delivery d JOIN customer_mail_jobs j ON j.id=d.job_id WHERE d.next_attempt_at<=clock_timestamp() AND (d.status IN ('PENDING','RETRY') OR (d.status='SENDING' AND d.lease_until<clock_timestamp())) ORDER BY j.created_at,j.id LIMIT 1 FOR UPDATE OF d SKIP LOCKED");
        if(rows.isEmpty())return null;var row=rows.getFirst();UUID id=(UUID)row.get("job_id");int attempt=((Number)row.get("attempts")).intValue();UUID lease=UUID.randomUUID();
        if(attempt>=5){jdbc.update("UPDATE customer_mail_delivery SET status='EXHAUSTED',last_reason='LEASE_EXHAUSTED',lease_id=NULL,lease_until=NULL WHERE job_id=?",id);event(id,lease,attempt,"RESULT","EXHAUSTED");audit.record(null,"CUSTOMER_MAIL_EXHAUSTED","mail:"+id,"SUCCESS","LEASE_EXHAUSTED");return null;}
        jdbc.update("UPDATE customer_mail_delivery SET status='SENDING',attempts=attempts+1,lease_id=?,lease_until=clock_timestamp()+interval '60 seconds' WHERE job_id=?",lease,id);
        event(id,lease,attempt+1,"CLAIMED","CLAIMED");audit.record(null,"CUSTOMER_MAIL_CLAIMED","mail:"+id,"SUCCESS","LEASED");return new Lease(id,lease,attempt+1);
    });}
    /** 복호화에 성공해도 행의 사용자·종류·근거·기한까지 대조해야 다른 업무의 암호문을 재사용할 수 없다. */
    public Payload open(UUID id){var row=jdbc.queryForMap("SELECT j.*,p.payload_cipher FROM customer_mail_jobs j JOIN customer_mail_payloads p ON p.job_id=j.id WHERE j.id=?",id);long user=((Number)row.get("user_id")).longValue();if(!crypto.keyId().equals(row.get("key_id")))throw new IllegalStateException("메일 키가 일치하지 않습니다");String raw=new String(crypto.decrypt(user,id,(byte[])row.get("payload_cipher")),java.nio.charset.StandardCharsets.UTF_8);var p=json.readValue(raw,Payload.class);
        if(!mac(raw).equals(row.get("content_mac"))||!"CUSTOMER_MAIL_V1".equals(p.policy())||!p.id().equals(id)||p.userId()!=user||!p.kind().name().equals(row.get("kind"))||!p.reference().equals(row.get("reference_id"))||!p.expiresAt().equals(((Timestamp)row.get("expires_at")).toInstant()))throw new IllegalStateException("메일 예약 문맥 검증 실패");return p;}
    public boolean eligible(Payload p){
        var u=users.findById(p.userId()).orElse(null);
        if(!p.expiresAt().isAfter(Instant.now())||u==null||u.getRole()!=UserRole.CUSTOMER||!u.isAuthenticationAllowed()||!p.email().equals(u.getEmail()))return false;
        if(p.kind()==Kind.EMAIL_VERIFICATION)return true;
        // 예약 근거와 현재 수신 선택을 발송 직전에 재검사한다. SMTP 동안 고객/금융 잠금은 잡지 않는다.
        if(!recipients.allowed(u))return false;
        return switch(p.kind()){
            case STATUS_NOTICE->jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_exit_review_replies r JOIN customer_exit_review_requests q ON q.id=r.request_id WHERE r.id=? AND q.user_id=?)",Boolean.class,p.reference(),p.userId());
            case FINAL_NOTICE->jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_review_final_decisions d JOIN customer_review_final_requests r ON r.id=d.request_id WHERE d.request_id=? AND d.approved AND (r.payload::jsonb->>'userId')::bigint=?)",Boolean.class,p.reference(),p.userId());
            default->false;
        };
    }

    /** 이전 워커의 늦은 응답은 관측 이력만 남기고 새 임대의 현재 상태를 덮어쓰지 않는다. */
    public boolean finish(Lease lease,String result){return tx.execute(t->{
        var rows=jdbc.queryForList("SELECT status,lease_id,lease_until,attempts FROM customer_mail_delivery WHERE job_id=? FOR UPDATE",lease.id());if(rows.isEmpty())throw new IllegalStateException("메일 예약이 없습니다");
        if(jdbc.queryForObject("SELECT count(*) FROM customer_mail_attempts WHERE job_id=? AND lease_id=? AND phase='RESULT'",Long.class,lease.id(),lease.token())>0)return false;
        var row=rows.getFirst();boolean current=((Number)row.get("attempts")).intValue()==lease.attempt()&&"SENDING".equals(row.get("status"))&&lease.token().equals(row.get("lease_id"))&&((Timestamp)row.get("lease_until")).toInstant().isAfter(Instant.now());
        if(!current){event(lease.id(),lease.token(),lease.attempt(),"RESULT","STALE_RESULT");audit.record(null,"CUSTOMER_MAIL_STALE_RESULT","mail:"+lease.id(),"SUCCESS","OBSERVED_ONLY");return false;}
        String status=switch(result){case "SMTP_ACCEPTED"->"SMTP_ACCEPTED";case "SUPPRESSED"->"SUPPRESSED";case "SMTP_UNKNOWN"->lease.attempt()>=5?"EXHAUSTED":"RETRY";default->throw new IllegalArgumentException("알 수 없는 메일 결과");};
        jdbc.update("UPDATE customer_mail_delivery SET status=?,last_reason=?,next_attempt_at=clock_timestamp()+(? * interval '1 second'),lease_id=NULL,lease_until=NULL WHERE job_id=?",status,result,Math.min(300,30*(1<<Math.min(lease.attempt()-1,4))),lease.id());
        event(lease.id(),lease.token(),lease.attempt(),"RESULT",result);audit.record(null,"CUSTOMER_MAIL_RESULT","mail:"+lease.id(),"SUCCESS",status+":"+result);return true;
    });}
    /** 금융/감사 이력은 유지하고 종료·만료한 수신 주소/토큰의 암호문만 제한된 묶음으로 삭제한다. */
    public int pruneExpiredPayloads(){return tx.execute(t->{
        int count=jdbc.update("""
            WITH due AS (SELECT p.job_id FROM customer_mail_payloads p JOIN customer_mail_jobs j ON j.id=p.job_id JOIN customer_mail_delivery d ON d.job_id=j.id
                WHERE j.expires_at<clock_timestamp()-interval '1 day' AND d.status IN ('SMTP_ACCEPTED','SUPPRESSED','EXHAUSTED') ORDER BY j.expires_at,j.id LIMIT 100 FOR UPDATE OF p SKIP LOCKED)
            DELETE FROM customer_mail_payloads p USING due WHERE p.job_id=due.job_id
            """);
        if(count>0)audit.record(null,"CUSTOMER_MAIL_PAYLOADS_PRUNED","mail-payloads","SUCCESS","EXPIRED_TERMINAL:"+count);return count;
    });}
    public State state(UUID id){var row=jdbc.queryForMap("SELECT * FROM customer_mail_delivery WHERE job_id=?",id);return new State(id,(String)row.get("status"),((Number)row.get("attempts")).intValue(),(String)row.get("last_reason"));}
    private void event(UUID id,UUID lease,int attempt,String phase,String result){jdbc.update("INSERT INTO customer_mail_attempts(id,job_id,lease_id,attempt,phase,outcome) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),id,lease,attempt,phase,result);}
    private String mac(String raw){return crypto.fingerprint("customer-mail-envelope-v1",raw);}
}
