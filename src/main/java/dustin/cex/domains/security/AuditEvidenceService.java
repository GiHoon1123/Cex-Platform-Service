package dustin.cex.domains.security;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 제한된 감사 사건을 결정론적으로 묶고 별도 키로 서명해 제출·재검증 가능한 증적을 만든다. */
@Service
public class AuditEvidenceService {
    public record Event(UUID id,UUID correlationId,Long actorId,String actorRole,UUID actorPerson,String sourceAddress,String action,String target,String outcome,String detail,Instant occurredAt){}
    public record Manifest(String policy,UUID exportId,Instant rangeStart,Instant rangeEnd,String actionFilter,Instant generatedAt,List<Event> events){}
    public record Export(UUID id,String manifest,String manifestHash,String signature,String keyId,Instant createdAt){}
    public record Verification(UUID id,boolean valid,String manifestHash,String keyId,int eventCount){}
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;private final AuditEvidenceKey key;private final AuditService audit;private final ObjectMapper json;
    public AuditEvidenceService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,AuditEvidenceKey key,AuditService audit,ObjectMapper json){this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.key=key;this.audit=audit;this.json=json;}
    @Transactional
    public Export export(long actor,UUID sid,Instant start,Instant end,String action){
        var user=users.lockById(actor).orElse(null);sessions.requireFresh(user,sid,UserRole.APPROVER,UserRole.AUDITOR);key.requireConfigured();
        if(start==null||end==null||!start.isBefore(end)||Duration.between(start,end).toDays()>31||action!=null&&(!action.matches("[A-Z0-9_]{1,64}")))throw denied(HttpStatus.BAD_REQUEST,"감사 증적 조회 범위가 올바르지 않습니다");
        String sql="SELECT * FROM security_audit WHERE occurred_at>=? AND occurred_at<?"+(action==null?"":" AND action=?")+" ORDER BY occurred_at,id LIMIT 1001";
        var events=jdbc.query(sql,statement->{statement.setTimestamp(1,Timestamp.from(start));statement.setTimestamp(2,Timestamp.from(end));if(action!=null)statement.setString(3,action);},
            (rs,n)->new Event(rs.getObject("id",UUID.class),rs.getObject("correlation_id",UUID.class),(Long)rs.getObject("actor_id"),rs.getString("actor_role"),rs.getObject("actor_person",UUID.class),rs.getString("source_address"),rs.getString("action"),rs.getString("target"),rs.getString("outcome"),rs.getString("detail"),rs.getTimestamp("occurred_at").toInstant()));
        if(events.size()>1000)throw denied(HttpStatus.PAYLOAD_TOO_LARGE,"감사 증적은 한 번에 천 건까지 내보낼 수 있습니다");
        UUID id=UUID.randomUUID();Instant created=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();var manifest=new Manifest("AUDIT_EVIDENCE_V1",id,start,end,action,created,List.copyOf(events));String raw=json.writeValueAsString(manifest),hash=digest(raw),signature=key.sign(raw);var role=user.getRole().name();
        jdbc.update("INSERT INTO audit_evidence_exports(id,requester_id,requester_person,requester_role,range_start,range_end,action_filter,event_count,manifest,manifest_hash,signature,key_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,actor,user.getOperatorPersonId(),role,Timestamp.from(start),Timestamp.from(end),action,events.size(),raw,hash,signature,key.keyId(),Timestamp.from(created));
        audit.record(actor,"AUDIT_EVIDENCE_EXPORTED","audit-export:"+id,"SUCCESS","COUNT:"+events.size()+":HASH:"+hash);return new Export(id,raw,hash,signature,key.keyId(),created);
    }
    @Transactional(readOnly=true)
    public Verification verify(long actor,UUID sid,UUID id){var user=users.findById(actor).orElse(null);sessions.requireFresh(user,sid,UserRole.APPROVER,UserRole.AUDITOR);key.requireConfigured();var rows=jdbc.queryForList("SELECT * FROM audit_evidence_exports WHERE id=?",id);if(rows.size()!=1)throw denied(HttpStatus.NOT_FOUND,"감사 증적이 없습니다");var row=rows.getFirst();String raw=(String)row.get("manifest"),hash=digest(raw),storedHash=(String)row.get("manifest_hash"),signature=(String)row.get("signature"),keyId=(String)row.get("key_id");boolean valid=hash.equals(storedHash)&&key.keyId().equals(keyId)&&key.verifies(raw,signature);if(!valid)throw denied(HttpStatus.SERVICE_UNAVAILABLE,"감사 증적 무결성을 확인할 수 없습니다");try{var manifest=json.readValue(raw,Manifest.class);if(!manifest.exportId().equals(id)||!"AUDIT_EVIDENCE_V1".equals(manifest.policy())||manifest.events().size()!=((Number)row.get("event_count")).intValue())throw new IllegalStateException();return new Verification(id,true,hash,keyId,manifest.events().size());}catch(Exception invalid){throw denied(HttpStatus.SERVICE_UNAVAILABLE,"감사 증적 형식을 확인할 수 없습니다");}}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static ResponseStatusException denied(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}
