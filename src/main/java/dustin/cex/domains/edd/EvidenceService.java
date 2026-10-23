package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 자료 수집은 사실 검증과 분리한다. 제출·열람은 거래 허가나 제재/PEP 해소의 근거로 자동 승격되지 않는다. */
@Service
public class EvidenceService {
    public enum Purpose { SOURCE_OF_FUNDS, SOURCE_OF_WEALTH, RESIDENTIAL_ADDRESS }
    public enum AccessReason { DUE_DILIGENCE_REVIEW, INDEPENDENT_REVIEW }
    public record Request(UUID id,long userId,UUID assessmentId,Purpose purpose,Instant expiresAt,UUID evidenceId,String status) {}
    public record Receipt(UUID id,UUID requestId,int originalBytes,int viewBytes,String status) {}
    /** 원본 이미지·암호문·개인 이름은 기본 문자열 출력과 메타데이터 응답에 포함하지 않는다. */
    public record View(UUID id,UUID viewId,byte[] bytes) {@Override public String toString() {return "View[자료 생략]";}}
    private final JdbcTemplate jdbc;private final UserRepository users;private final RefreshTokenRepository sessions;
    private final OperatorSessionPolicy policy;private final EvidenceCrypto crypto;private final EvidenceImage images;
    private final AuditService audit;private final TransactionTemplate tx;
    public EvidenceService(JdbcTemplate jdbc,UserRepository users,RefreshTokenRepository sessions,OperatorSessionPolicy policy,
            EvidenceCrypto crypto,EvidenceImage images,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.policy=policy;this.crypto=crypto;this.images=images;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    /** 운영자가 지금 평가에 필요한 자료 종류만 요청한다. 같은 키는 같은 요청만 다시 보여준다. */
    public Request request(long actor,UUID sid,long user,UUID assessment,Purpose purpose,UUID key) {
        return attempt(actor,"EDD_EVIDENCE_REQUEST",()->tx.execute(s->{
            var locked=lock(actor,user);var maker=locked.get(actor);policy.requireFresh(maker,sid,UserRole.OPERATOR);customer(locked.get(user));crypto.requireConfigured();
            if(assessment==null || purpose==null || key==null) throw denied(HttpStatus.BAD_REQUEST);
            var old=jdbc.queryForList("SELECT id,assessment_id,user_id,purpose FROM edd_evidence_requests WHERE requester_id=? AND request_key=?",actor,key);
            if(!old.isEmpty()) {var r=old.getFirst();if(!r.get("assessment_id").equals(assessment) || ((Number)r.get("user_id")).longValue()!=user || !r.get("purpose").equals(purpose.name())) throw denied(HttpStatus.CONFLICT);return read((UUID)r.get("id"));}
            var c=currentCase(user,assessment);
            // 하루 요청 수와 아직 제출되지 않은 같은 종류를 제한한다. 기한이 지난 요청도 이력에서 지우지 않는다.
            if(jdbc.queryForObject("SELECT count(*) FROM edd_evidence_requests WHERE user_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,user)>=5) throw denied(HttpStatus.TOO_MANY_REQUESTS);
            if(jdbc.queryForObject("SELECT count(*) FROM edd_evidence_requests r WHERE r.user_id=? AND r.assessment_id=? AND r.purpose=? AND r.expires_at>clock_timestamp() AND NOT EXISTS(SELECT 1 FROM edd_evidence e WHERE e.request_id=r.id)",Long.class,user,assessment,purpose.name())>0) throw denied(HttpStatus.CONFLICT);
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO edd_evidence_requests(id,case_id,user_id,assessment_id,purpose,requester_id,requester_person,request_key,expires_at) VALUES(?,?,?,?,?,?,?,?,clock_timestamp()+interval '7 days')",id,c,user,assessment,purpose.name(),actor,maker.getOperatorPersonId(),key);
            audit.record(actor,"EDD_EVIDENCE_REQUESTED","edd-evidence-request:"+id,"SUCCESS",purpose.name());return read(id);
        }));
    }
    public List<Request> customerRequests(long user,UUID sid) {
        return attempt(user,"EDD_EVIDENCE_LIST",()->tx.execute(s->{customerSession(user,sid);return list(user);}));
    }
    public List<Request> operatorRequests(long actor,UUID sid,long user) {
        return attempt(actor,"EDD_EVIDENCE_LIST",()->tx.execute(s->{
            var locked=lock(actor,user);policy.requireFresh(locked.get(actor),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);customer(locked.get(user));
            audit.record(actor,"EDD_EVIDENCE_LISTED","customer:"+user,"SUCCESS","METADATA_ONLY");return list(user);
        }));
    }
    /** 원본과 열람본을 같은 트랜잭션에 넣는다. 재전달은 원본 바이트가 정확히 같아야 한다. */
    public Receipt submit(long user,UUID sid,UUID request,byte[] original) {
        return attempt(user,"EDD_EVIDENCE_SUBMIT",()->tx.execute(s->{
            customerSession(user,sid);crypto.requireConfigured();var r=requestRow(request);
            if(((Number)r.get("user_id")).longValue()!=user) throw denied(HttpStatus.NOT_FOUND);
            // 파일 유효성 검사보다 먼저 현재 고객과 요청 소유권을 확인한다. 옛 자료의 재전달은 새 평가를 변경하지 않는다.
            if(original==null || original.length==0 || original.length>EvidenceImage.MAX_INPUT) throw denied(HttpStatus.PAYLOAD_TOO_LARGE);
            String digest=digest(original);var existing=jdbc.queryForList("SELECT * FROM edd_evidence WHERE request_id=?",request);
            if(!existing.isEmpty()) {var e=existing.getFirst();if(!digest.equals(e.get("original_digest"))) throw denied(HttpStatus.CONFLICT);return receipt(e);}
            if(!((Timestamp)r.get("expires_at")).toInstant().isAfter(Instant.now())) throw denied(HttpStatus.CONFLICT);
            currentCase(user,(UUID)r.get("assessment_id"));
            long stored=jdbc.queryForObject("SELECT COALESCE(sum(octet_length(original_cipher)+octet_length(view_cipher)),0) FROM edd_evidence WHERE user_id=?",Long.class,user);
            if(stored+original.length+EvidenceImage.MAX_VIEW+56>10L*1024*1024) throw denied(HttpStatus.PAYLOAD_TOO_LARGE);
            byte[] view=images.render(original);UUID id=UUID.randomUUID();String context=context(id,r);
            jdbc.update("INSERT INTO edd_evidence(id,request_id,user_id,original_digest,original_bytes,view_bytes,key_id,original_cipher,view_cipher) VALUES(?,?,?,?,?,?,?,?,?)",id,request,user,digest,original.length,view.length,crypto.keyId(),crypto.encrypt(context+":ORIGINAL",original),crypto.encrypt(context+":VIEW",view));
            // 새 자료가 도착하면 옛 판단·서명은 재사용할 수 없다. 자료 도착만으로 위험을 해소하지 않는다.
            jdbc.update("UPDATE edd_cases SET revision=revision+1,state='OPEN',valid_until=NULL WHERE id=?",r.get("case_id"));
            audit.record(user,"EDD_EVIDENCE_SUBMITTED","edd-evidence:"+id,"SUCCESS",r.get("purpose").toString());
            return new Receipt(id,request,original.length,view.length,"RECEIVED_UNVERIFIED");
        }));
    }
    /** 열람 감사가 커밋된 뒤에만 자료를 반환한다. 원본 다운로드와 공개 URL은 제공하지 않는다. */
    public View view(long actor,UUID sid,UUID id,AccessReason reason) {
        return attempt(actor,"EDD_EVIDENCE_VIEW",()->tx.execute(s->{
            var e=evidenceRow(id);var r=requestRow((UUID)e.get("request_id"));long user=((Number)r.get("user_id")).longValue();
            var locked=lock(actor,user);var operator=locked.get(actor);policy.requireFresh(operator,sid,UserRole.OPERATOR,UserRole.APPROVER);
            if(reason==null) throw denied(HttpStatus.BAD_REQUEST);
            // 일반 운영자는 자신이 요청한 사건만, 승인자는 독립 확인 목적으로만 열람한다. 감사 역할에는 원문 권한이 없다.
            if(operator.getRole()==UserRole.OPERATOR && (reason!=AccessReason.DUE_DILIGENCE_REVIEW || ((Number)r.get("requester_id")).longValue()!=actor || !operator.getOperatorPersonId().equals(r.get("requester_person")))) throw denied(HttpStatus.FORBIDDEN);
            if(operator.getRole()==UserRole.APPROVER && (reason!=AccessReason.INDEPENDENT_REVIEW || operator.getOperatorPersonId().equals(r.get("requester_person")))) throw denied(HttpStatus.FORBIDDEN);
            // 같은 운영자의 무제한 자료 반출을 막는 실행 한도다. 최근 인증은 현재 저장된 상태로 다시 확인한다.
            if(jdbc.queryForObject("SELECT count(*) FROM security_audit WHERE actor_id=? AND action='EDD_EVIDENCE_VIEWED' AND outcome='SUCCESS' AND occurred_at>clock_timestamp()-interval '5 minutes'",Long.class,actor)>=20) throw denied(HttpStatus.TOO_MANY_REQUESTS);
            byte[] view=crypto.decrypt((String)e.get("key_id"),context(id,r)+":VIEW",(byte[])e.get("view_cipher"));
            // 실제 반환 시점의 평가·사건·운영 인물·세션을 묶는다. 옛 열람을 새 심사의 근거로 재사용하지 않는다.
            var c=jdbc.queryForMap("SELECT latest_assessment,revision FROM edd_cases WHERE id=?",r.get("case_id"));UUID receipt=UUID.randomUUID();
            jdbc.update("INSERT INTO edd_evidence_views(id,evidence_id,actor_id,actor_person,actor_version,session_id,assessment_id,case_revision,key_id) VALUES(?,?,?,?,?,?,?,?,?)",
                receipt,id,actor,operator.getOperatorPersonId(),operator.getVersion(),sid,c.get("latest_assessment"),c.get("revision"),crypto.keyId());
            audit.record(actor,"EDD_EVIDENCE_VIEWED","edd-evidence:"+id,"SUCCESS",reason.name());return new View(id,receipt,view);
        }));
    }
    private UUID currentCase(long user,UUID assessment) {
        var rows=jdbc.queryForList("SELECT e.id FROM edd_cases e JOIN cdd_profiles p ON p.user_id=e.user_id AND p.assessment_id=e.latest_assessment JOIN cdd_assessments a ON a.id=p.assessment_id AND a.declaration_id=p.declaration_id AND a.user_id=p.user_id WHERE e.user_id=? AND e.latest_assessment=? AND e.state IN ('OPEN','REFERRED') FOR UPDATE OF e",user,assessment);
        if(rows.isEmpty()) throw denied(HttpStatus.CONFLICT);return (UUID)rows.getFirst().get("id");
    }
    private void customerSession(long user,UUID sid) {
        var u=users.lockById(user).orElseThrow(()->denied(HttpStatus.FORBIDDEN));customer(u);var token=sessions.findBySessionId(sid).orElseThrow(()->denied(HttpStatus.FORBIDDEN));
        if(!u.isAuthenticationAllowed() || !token.getUserId().equals(user) || token.getRevoked() || !token.getExpiresAt().isAfter(LocalDateTime.now())) throw denied(HttpStatus.FORBIDDEN);
    }
    private List<Request> list(long user) {return jdbc.queryForList("SELECT id FROM edd_evidence_requests WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 50",user).stream().map(r->read((UUID)r.get("id"))).toList();}
    private Request read(UUID id) {
        var r=requestRow(id);var found=jdbc.queryForList("SELECT id FROM edd_evidence WHERE request_id=?",id);UUID evidence=found.isEmpty()?null:(UUID)found.getFirst().get("id");
        String state=evidence!=null?"RECEIVED_UNVERIFIED":(((Timestamp)r.get("expires_at")).toInstant().isAfter(Instant.now())?"REQUESTED":"EXPIRED");
        // 현재 평가가 바뀐 미제출 요청은 UI에서도 더 이상 제출 가능하다고 표시하지 않는다.
        if(evidence==null && jdbc.queryForObject("SELECT count(*) FROM cdd_profiles p JOIN edd_cases e ON e.user_id=p.user_id WHERE p.user_id=? AND p.assessment_id=? AND e.latest_assessment=p.assessment_id AND e.state IN ('OPEN','REFERRED')",Long.class,r.get("user_id"),r.get("assessment_id"))==0) state="STALE";
        return new Request(id,((Number)r.get("user_id")).longValue(),(UUID)r.get("assessment_id"),Purpose.valueOf((String)r.get("purpose")),((Timestamp)r.get("expires_at")).toInstant(),evidence,state);
    }
    private Map<String,Object> requestRow(UUID id) {var rows=jdbc.queryForList("SELECT * FROM edd_evidence_requests WHERE id=?",id);if(rows.isEmpty()) throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();}
    private Map<String,Object> evidenceRow(UUID id) {var rows=jdbc.queryForList("SELECT * FROM edd_evidence WHERE id=?",id);if(rows.isEmpty()) throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();}
    private Receipt receipt(Map<String,Object> r) {return new Receipt((UUID)r.get("id"),(UUID)r.get("request_id"),((Number)r.get("original_bytes")).intValue(),((Number)r.get("view_bytes")).intValue(),"RECEIVED_UNVERIFIED");}
    private String context(UUID id,Map<String,Object> r) {return id+":"+r.get("user_id")+":"+r.get("case_id")+":"+r.get("id")+":"+r.get("assessment_id")+":"+r.get("purpose");}
    private String digest(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException("증빙 지문 생성 실패");}}
    private void customer(User u) {if(u==null || u.getRole()!=UserRole.CUSTOMER) throw denied(HttpStatus.CONFLICT);}
    private Map<Long,User> lock(long... ids) {var result=new HashMap<Long,User>();Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(u->result.put(id,u)));return result;}
    private <T>T attempt(long actor,String action,Supplier<T> work) {try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,action,"edd-evidence","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"증빙의 현재 요청·소유권·운영 인증을 확인하세요");}
}
