package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.*;
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

/**
 * 고객 본인의 종료 개시 재검토 요청을 접수하고 지정 담당자에게만 제공한다.
 * 접수·열람은 판단이 아니다. 종료 효과·고객확인·보고·원장·주문을 변경하지 않는다.
 */
@Service
public class CustomerExitReviewService {
    public record Submission(String rationale) {
        public Submission validated() {
            if(rationale==null)throw denied(HttpStatus.BAD_REQUEST);
            String value=Normalizer.normalize(rationale.replace("\r\n","\n"),Normalizer.Form.NFC).strip();
            // 여러 줄의 사유는 허용하되 로그 제어 문자와 숨은 방향 전환 문자는 받지 않는다.
            if(value.isBlank()||value.length()>2000||value.codePoints().anyMatch(c->(Character.isISOControl(c)&&c!='\n')||Character.getType(c)==Character.FORMAT))throw denied(HttpStatus.BAD_REQUEST);
            return new Submission(value);
        }
        @Override public String toString(){return "Submission[고객 사유 생략]";}
    }
    public record Receipt(UUID id,Instant receivedAt,String status) {}
    public record Inspection(Receipt receipt,String rationale) {
        @Override public String toString(){return "Inspection[고객 사유 생략]";}
    }
    private final JdbcTemplate jdbc;private final UserRepository users;private final RefreshTokenRepository tokens;
    private final OperatorSessionPolicy sessions;private final RelationshipExitService exits;private final EvidenceCrypto crypto;
    private final CddCrypto fingerprints;private final ObjectMapper json;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerExitReviewService(JdbcTemplate jdbc,UserRepository users,RefreshTokenRepository tokens,
            OperatorSessionPolicy sessions,RelationshipExitService exits,EvidenceCrypto crypto,CddCrypto fingerprints,
            ObjectMapper json,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.tokens=tokens;this.sessions=sessions;this.exits=exits;this.crypto=crypto;
        this.fingerprints=fingerprints;this.json=json;this.audit=audit;tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** 외부 트랜잭션 참여를 막아 고객 잠금 뒤 새 확정 기록으로 중복/일일 한도를 검사한다. */
    @Transactional(propagation=Propagation.NEVER)
    public Receipt submit(long actor,UUID sid,UUID key,Submission supplied) {
        return attempt(actor,()->tx.execute(t->{
            customer(actor,sid);crypto.requireConfigured();fingerprints.requireConfigured();
            if(key==null||supplied==null)throw denied(HttpStatus.BAD_REQUEST);var value=supplied.validated();
            // 응답 유실 뒤 같은 키 재송신은 원래 접수만 반환한다. 새 종료 대상으로 붙여 바꾸지 않는다.
            var old=jdbc.queryForList("SELECT * FROM customer_exit_review_requests WHERE user_id=? AND request_key=?",actor,key);
            if(!old.isEmpty()) {
                var row=old.getFirst();UUID exit=(UUID)row.get("exit_id");
                if(!fingerprints.keyId().equals(row.get("fingerprint_key_id"))||!crypto.keyId().equals(row.get("evidence_key_id"))||!mac(actor,exit,value.rationale()).equals(row.get("content_mac")))throw denied(HttpStatus.CONFLICT);
                audit.record(actor,"CUSTOMER_EXIT_REVIEW_REPLAYED","customer-review:"+row.get("id"),"SUCCESS","RECEIPT_ONLY");return receipt(row);
            }
            var active=jdbc.queryForList("SELECT review_id FROM relationship_active_exits WHERE user_id=?",actor);
            if(active.size()!=1)throw denied(HttpStatus.CONFLICT);UUID exit=(UUID)active.getFirst().get("review_id");
            var original=exits.approvedOrigin(exit);if(original.payload().userId()!=actor)throw new IllegalStateException("재검토 대상 검증 실패");
            if(jdbc.queryForObject("SELECT count(*) FROM customer_exit_review_requests WHERE user_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,actor)>=3)throw denied(HttpStatus.TOO_MANY_REQUESTS);
            UUID id=UUID.randomUUID();String mac=mac(actor,exit,value.rationale());byte[] cipher=crypto.encrypt(context(id,actor,exit,mac),value.rationale().getBytes(StandardCharsets.UTF_8));
            jdbc.update("INSERT INTO customer_exit_review_requests(id,user_id,exit_id,request_key,reason_cipher,content_mac,fingerprint_key_id,evidence_key_id) VALUES(?,?,?,?,?,?,?,?)",id,actor,exit,key,cipher,mac,fingerprints.keyId(),crypto.keyId());
            // 접수 기록과 감사도 같은 트랜잭션에 확정돼야 고객에게 접수번호를 반환한다.
            audit.record(actor,"CUSTOMER_EXIT_REVIEW_RECEIVED","customer-review:"+id,"SUCCESS","RECEIVED_ONLY");return receipt(row(id));
        }));
    }
    public Receipt own(long actor,UUID sid,UUID id) {
        return attempt(actor,()->tx.execute(t->{customer(actor,sid);var row=row(id);if(((Number)row.get("user_id")).longValue()!=actor)throw denied(HttpStatus.NOT_FOUND);
            audit.record(actor,"CUSTOMER_EXIT_REVIEW_STATUS","customer-review:"+id,"SUCCESS","RECEIPT_ONLY");return receipt(row);}));
    }
    public List<Receipt> ownList(long actor,UUID sid,int page) {
        return attempt(actor,()->tx.execute(t->{customer(actor,sid);page(page);var rows=jdbc.queryForList("SELECT id,created_at FROM customer_exit_review_requests WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 25 OFFSET ?",actor,page*25);
            audit.record(actor,"CUSTOMER_EXIT_REVIEWS_LISTED","customer:"+actor,"SUCCESS","RECEIPT_ONLY");return rows.stream().map(this::receipt).toList();}));
    }
    public List<Receipt> assignedList(long actor,UUID sid,UUID exit,int page) {
        return attempt(actor,()->tx.execute(t->{assigned(actor,sid,exit);page(page);var rows=jdbc.queryForList("SELECT id,created_at FROM customer_exit_review_requests WHERE exit_id=? ORDER BY created_at DESC,id DESC LIMIT 25 OFFSET ?",exit,page*25);
            audit.record(actor,"CUSTOMER_EXIT_REVIEWS_ASSIGNED","relationship-exit:"+exit,"SUCCESS","RECEIPT_ONLY");return rows.stream().map(this::receipt).toList();}));
    }
    public Inspection inspect(long actor,UUID sid,UUID id) {
        return attempt(actor,()->tx.execute(t->{var row=row(id);UUID exit=(UUID)row.get("exit_id");var original=assigned(actor,sid,exit);long user=((Number)row.get("user_id")).longValue();
            if(original.payload().userId()!=user)throw new IllegalStateException("고객 재검토 대상 검증 실패");String mac=(String)row.get("content_mac");
            byte[] plain=crypto.decrypt((String)row.get("evidence_key_id"),context(id,user,exit,mac),(byte[])row.get("reason_cipher"));
            String value=new Submission(new String(plain,StandardCharsets.UTF_8)).validated().rationale();
            if(!fingerprints.keyId().equals(row.get("fingerprint_key_id"))||!mac.equals(mac(user,exit,value)))throw new IllegalStateException("고객 사유 무결성 검증 실패");
            // 원문을 먼저 해독해도 감사가 실패하면 호출자에게 반환하지 않는다. 제공을 금융 승인 영수증으로 쓰지 않는다.
            audit.record(actor,"CUSTOMER_EXIT_REVIEW_VIEWED","customer-review:"+id,"SUCCESS","CUSTOMER_REASON_ONLY");return new Inspection(receipt(row),value);
        }));
    }
    private void customer(long actor,UUID sid) {
        var owner=users.lockById(actor).orElse(null);var token=sid==null?null:tokens.findBySessionId(sid).orElse(null);
        if(owner==null||owner.getRole()!=UserRole.CUSTOMER||!owner.isAuthenticationAllowed()||token==null||token.getUserId()!=actor||token.getRevoked()||!token.getExpiresAt().isAfter(LocalDateTime.now()))throw denied(HttpStatus.FORBIDDEN);
    }
    private RelationshipExitService.Review assigned(long actor,UUID sid,UUID exit) {
        var person=users.lockById(actor).orElse(null);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);var original=exits.approvedOrigin(exit);var p=original.payload();
        if(!((actor==p.requesterId()&&person.getOperatorPersonId().equals(p.requesterPerson()))||(actor==p.checkerId()&&person.getOperatorPersonId().equals(p.checkerPerson()))))throw denied(HttpStatus.NOT_FOUND);return original;
    }
    private Map<String,Object> row(UUID id) {var rows=jdbc.queryForList("SELECT * FROM customer_exit_review_requests WHERE id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();}
    private Receipt receipt(Map<String,Object> row){return new Receipt((UUID)row.get("id"),((Timestamp)row.get("created_at")).toInstant(),"RECEIVED");}
    private String mac(long user,UUID exit,String value){return fingerprints.fingerprint("customer-exit-review",json.writeValueAsString(List.of(user,exit,value)));}
    private String context(UUID id,long user,UUID exit,String mac){return "CUSTOMER_EXIT_REVIEW:"+id+":"+user+":"+exit+":"+mac;}
    private void page(int page){if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);}
    private <T>T attempt(long actor,Supplier<T> work){try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_EXIT_REVIEW","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 본인 인증·접수 키·재검토 대상·담당 권한을 확인하세요");}
}
