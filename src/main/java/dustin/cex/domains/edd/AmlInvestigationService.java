package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.*;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/**
 * 실제 공급사 후보에 대한 담당자의 조사 의견을 기록한다. 의견은 동일 인물 확정이나 거래 허가가 아니다.
 * 기존 EDD 결론이 이 기록을 전체 근거 해시에 포함한다. 별도의 승인 종류·중복 서명 절차는 만들지 않는다.
 */
@Service
public class AmlInvestigationService {
    public static final String POLICY="EDD_AML_INVESTIGATION_V1";
    public static final String ASSURANCE="ANALYST_OBSERVATION_ONLY_RISK_UNRESOLVED";
    public enum Finding {POSSIBLE_MATCH,IDENTIFIERS_DIFFER,INSUFFICIENT_INFORMATION}
    public record Viewed(UUID viewId,long caseRevision,AmlSource.Content source) {
        @Override public String toString() {return "Viewed[개인정보 생략]";}
    }
    public record Payload(UUID id,UUID sourceId,String sourceMac,UUID caseId,long caseRevision,long userId,UUID assessmentId,
        UUID viewId,int screeningIndex,String hitId,Finding finding,String notesMac,String evidenceKeyId,
        long requesterId,UUID requesterPerson,long requesterVersion,String policy,String assurance) {}
    public record Investigation(Payload payload,String payloadHash) {}
    public record Detail(Investigation investigation,String notes) {
        @Override public String toString() {return "Detail[개인정보 생략]";}
    }
    private final AmlSource sources;private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final EvidenceCrypto crypto;private final CddCrypto cdd;private final ObjectMapper json;private final DiditWebhookVerifier verifier;
    private final AuditService audit;private final TransactionTemplate tx;
    public AmlInvestigationService(AmlSource sources,JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,
            EvidenceCrypto crypto,CddCrypto cdd,ObjectMapper json,DiditWebhookVerifier verifier,AuditService audit,PlatformTransactionManager manager) {
        this.sources=sources;this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.crypto=crypto;this.cdd=cdd;this.json=json;this.verifier=verifier;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    /** 원문 조회는 운영자와 승인자만 가능하다. 역사 자료도 읽을 수 있지만 옛 자료로 새 조사를 만들 수는 없다. */
    public Viewed inspect(long actor,UUID sid,UUID source) {
        return attempt(actor,"EDD_AML_INSPECT",()->tx.execute(s->{
            var m=sources.metadata(source);var locked=lock(actor,m.userId());var operator=locked.get(actor);
            sessions.requireFresh(operator,sid,UserRole.OPERATOR,UserRole.APPROVER);customer(locked.get(m.userId()));
            var content=sources.content(m);long revision=jdbc.queryForObject("SELECT revision FROM edd_cases WHERE id=?",Long.class,m.caseId());UUID view=UUID.randomUUID();
            jdbc.update("INSERT INTO edd_aml_source_views(id,source_id,actor_id,actor_person,actor_version,session_id,case_revision,content_mac) VALUES(?,?,?,?,?,?,?,?)",
                view,source,actor,operator.getOperatorPersonId(),operator.getVersion(),sid,revision,m.contentMac());
            audit.record(actor,"EDD_AML_SOURCE_VIEWED","aml-source:"+source,"SUCCESS",m.contentMac());return new Viewed(view,revision,content);
        }));
    }
    /** 목록은 원문·후보 성명·조사 메모를 노출하지 않는다. 감사자는 이 식별자로 결정 당시 기록을 연결한다. */
    public List<AmlSource.Metadata> listSources(long actor,UUID sid,long user,int page) {
        return attempt(actor,"EDD_AML_LIST",()->tx.execute(s->{
            page(page);var locked=lock(actor,user);sessions.requireFresh(locked.get(actor),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);customer(locked.get(user));
            var result=jdbc.queryForList("SELECT id FROM edd_aml_sources WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 25 OFFSET ?",user,page*25).stream().map(x->sources.metadata((UUID)x.get("id"))).toList();
            audit.record(actor,"EDD_AML_SOURCES_LISTED","customer:"+user,"SUCCESS","METADATA_ONLY");return result;
        }));
    }
    public Investigation record(long actor,UUID sid,UUID source,UUID view,UUID assessment,long revision,UUID key,
            int screening,String hit,Finding finding,String notes) {
        return attempt(actor,"EDD_AML_INVESTIGATE",()->tx.execute(s->{
            var m=sources.metadata(source);var locked=lock(actor,m.userId());var writer=locked.get(actor);sessions.requireFresh(writer,sid,UserRole.OPERATOR);customer(locked.get(m.userId()));crypto.requireConfigured();
            if(view==null||assessment==null||key==null||finding==null)throw denied(HttpStatus.BAD_REQUEST);
            String text=notes(notes);String mac=cdd.fingerprint("aml-investigation-notes",text);
            var old=jdbc.queryForList("SELECT id FROM edd_aml_investigations WHERE requester_id=? AND request_key=?",actor,key);
            // 같은 요청의 재전송은 기록을 추가하지 않는다. 저장된 메모·대상·열람과 같은 요청만 재사용한다.
            if(!old.isEmpty()) {
                var result=read((UUID)old.getFirst().get("id"));var p=result.payload();
                if(!p.sourceId().equals(source)||!p.viewId().equals(view)||!p.assessmentId().equals(assessment)||p.caseRevision()!=revision
                    ||p.screeningIndex()!=screening||!Objects.equals(p.hitId(),hit)||p.finding()!=finding||!p.notesMac().equals(mac))throw denied(HttpStatus.CONFLICT);
                return result;
            }
            current(m,assessment,revision);view(writer,sid,m,view,revision);
            target(sources.content(m).report(),screening,hit,finding);
            UUID id=UUID.randomUUID();var p=new Payload(id,source,m.contentMac(),m.caseId(),revision,m.userId(),assessment,view,screening,hit,finding,mac,crypto.keyId(),
                actor,writer.getOperatorPersonId(),writer.getVersion(),POLICY,ASSURANCE);String hash=verifier.digest(json.valueToTree(p));
            jdbc.update("INSERT INTO edd_aml_investigations(id,source_id,case_id,user_id,requester_id,request_key,payload,payload_hash,notes_cipher) VALUES(?,?,?,?,?,?,?,?,?)",
                id,source,m.caseId(),m.userId(),actor,key,json.writeValueAsString(p),hash,crypto.encrypt(context(p),text.getBytes(StandardCharsets.UTF_8)));
            audit.record(actor,"EDD_AML_INVESTIGATION_RECORDED","aml-investigation:"+id,"SUCCESS",finding+":"+hash);return read(id);
        }));
    }
    /** 불변 자료를 참조하는 조사 메모는 원문 권한으로 읽는다. 메모는 로그·감사 본문에 넣지 않는다. */
    public Detail inspectInvestigation(long actor,UUID sid,UUID id) {
        return attempt(actor,"EDD_AML_INVESTIGATION_READ",()->tx.execute(s->{
            var result=read(id);var p=result.payload();var locked=lock(actor,p.userId());sessions.requireFresh(locked.get(actor),sid,UserRole.OPERATOR,UserRole.APPROVER);customer(locked.get(p.userId()));
            String notes=verifiedNotes(result);
            audit.record(actor,"EDD_AML_INVESTIGATION_VIEWED","aml-investigation:"+id,"SUCCESS",result.payloadHash());return new Detail(result,notes);
        }));
    }
    public List<Investigation> list(long actor,UUID sid,long user,int page) {
        return attempt(actor,"EDD_AML_INVESTIGATION_LIST",()->tx.execute(s->{
            page(page);var locked=lock(actor,user);sessions.requireFresh(locked.get(actor),sid,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);customer(locked.get(user));
            var result=jdbc.queryForList("SELECT id FROM edd_aml_investigations WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 25 OFFSET ?",user,page*25).stream().map(x->read((UUID)x.get("id"))).toList();
            audit.record(actor,"EDD_AML_INVESTIGATIONS_LISTED","customer:"+user,"SUCCESS","METADATA_ONLY");return result;
        }));
    }
    /** 내부 종결 판단은 이미 권한을 확인한 업무 트랜잭션에서 메모의 무결성만 확인하고 원문은 반환하지 않는다. */
    Investigation verifiedForResolution(UUID id) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("조사 검증에는 업무 트랜잭션이 필요합니다");
        var result=read(id);verifiedNotes(result);return result;
    }
    private String verifiedNotes(Investigation result) {
        var p=result.payload();try {
            byte[] cipher=jdbc.queryForObject("SELECT notes_cipher FROM edd_aml_investigations WHERE id=?",byte[].class,p.id());
            String notes=new String(crypto.decrypt(p.evidenceKeyId(),context(p),cipher),StandardCharsets.UTF_8);
            if(!p.notesMac().equals(cdd.fingerprint("aml-investigation-notes",notes)))throw new IllegalStateException();return notes;
        } catch(RuntimeException e) {throw new IllegalStateException("AML 조사 메모의 해독·무결성 검증 실패");}
    }
    private void current(AmlSource.Metadata m,UUID assessment,long revision) {
        boolean valid=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM edd_cases c JOIN cdd_profiles p ON p.user_id=c.user_id AND p.assessment_id=c.latest_assessment
             JOIN cdd_assessments a ON a.id=p.assessment_id AND a.declaration_id=p.declaration_id AND a.user_id=c.user_id
             JOIN kyc_profiles k ON k.user_id=c.user_id AND k.active_attempt=a.attempt_id AND k.status_digest=a.status_digest
             WHERE c.id=? AND c.user_id=? AND c.revision=? AND c.state IN ('OPEN','REFERRED') AND a.id=? AND a.event_id=?
              AND a.status_digest=? AND a.key_id=? AND a.policy_version=? AND k.identity_status IN ('VERIFIED','PENDING','NOT_VERIFIED'))
            """,Boolean.class,m.caseId(),m.userId(),revision,assessment,m.eventId(),m.statusDigest(),cdd.keyId(),CddService.POLICY));
        if(!assessment.equals(m.assessmentId())||!valid||!m.keyId().equals(cdd.keyId()))throw denied(HttpStatus.CONFLICT);
    }
    private void view(User actor,UUID sid,AmlSource.Metadata m,UUID view,long revision) {
        boolean valid=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM edd_aml_source_views WHERE id=? AND source_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND case_revision=? AND content_mac=? AND viewed_at>clock_timestamp()-interval '5 minutes')",Boolean.class,
            view,m.id(),actor.getId(),actor.getOperatorPersonId(),actor.getVersion(),sid,revision,m.contentMac()));
        if(!valid)throw denied(HttpStatus.CONFLICT);
    }
    /** 같은 후보가 여러 검색 노드에 나타날 수 있어 실제 배열 위치와 후보 식별자를 함께 고정한다. */
    private void target(JsonNode report,int index,String hit,Finding finding) {
        var nodes=report.path("screenings");
        if(index==-1&&hit==null&&finding==Finding.INSUFFICIENT_INFORMATION&&(!nodes.isArray()||nodes.isEmpty()))return;
        if(index<0||!nodes.isArray()||index>=nodes.size())throw denied(HttpStatus.BAD_REQUEST);
        var hits=nodes.get(index).path("hits");
        if(hit==null) {if(finding!=Finding.INSUFFICIENT_INFORMATION)throw denied(HttpStatus.BAD_REQUEST);return;}
        if(hit.isBlank()||hit.length()>300||!hits.isArray())throw denied(HttpStatus.BAD_REQUEST);
        long count=0;for(var candidate:hits)if(candidate.path("id").isString()&&hit.equals(candidate.path("id").asText()))count++;
        if(count!=1)throw denied(HttpStatus.CONFLICT);
    }
    private Investigation read(UUID id) {
        var rows=jdbc.queryForList("SELECT payload,payload_hash FROM edd_aml_investigations WHERE id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();
        var raw=json.readTree((String)row.get("payload"));var p=json.treeToValue(raw,Payload.class);
        if(!p.id().equals(id)||!POLICY.equals(p.policy())||!ASSURANCE.equals(p.assurance())||!verifier.digest(raw).equals(row.get("payload_hash")))throw new IllegalStateException("AML 조사 기록 해시 검증 실패");
        return new Investigation(p,(String)row.get("payload_hash"));
    }
    private String notes(String value) {
        if(value==null||value.isBlank()||value.length()>2000)throw denied(HttpStatus.BAD_REQUEST);
        for(int c:value.codePoints().toArray())if((Character.isISOControl(c)&&c!='\n')||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE)throw denied(HttpStatus.BAD_REQUEST);
        return Normalizer.normalize(value,Normalizer.Form.NFC).strip();
    }
    private String context(Payload p) {return "AML_INVESTIGATION:"+p.id()+":"+p.sourceId()+":"+p.userId()+":"+p.sourceMac();}
    private Map<Long,User> lock(long... ids) {var result=new HashMap<Long,User>();Arrays.stream(ids).distinct().sorted().forEach(id->users.lockById(id).ifPresent(x->result.put(id,x)));return result;}
    private void customer(User u) {if(u==null||u.getRole()!=UserRole.CUSTOMER)throw denied(HttpStatus.CONFLICT);}
    private void page(int page) {if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);}
    private <T>T attempt(long actor,String action,Supplier<T> work) {try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,action,"edd-aml","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"현재 AML 근거·열람·대상 후보·역할을 확인하세요");}
}
