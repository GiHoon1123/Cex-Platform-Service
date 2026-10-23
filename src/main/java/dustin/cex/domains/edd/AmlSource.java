package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.AuditService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;

/**
 * 서명이 검증된 최신 공급사 결과에서 조사에 필요한 신원·후보 필드만 보존한다.
 * 공급사의 주장과 실제 동일 인물/제재 판정은 구분한다. 조회 중 외부 주소를 따라가지 않는다.
 */
@Component
public class AmlSource {
    public static final String POLICY="EDD_SIGNED_AML_SOURCE_V1";
    public static final String ASSURANCE="PROVIDER_ASSERTIONS_ONLY_NOT_ADJUDICATED";
    public record Metadata(UUID id,UUID caseId,long userId,UUID assessmentId,UUID eventId,String statusDigest,
            long providerTime,String contentMac,String keyId,String policy,String assurance) {}
    public record Content(Metadata metadata,JsonNode report) {
        @Override public String toString() {return "Content[개인정보 생략]";}
    }
    private final JdbcTemplate jdbc;private final CddCrypto crypto;private final ObjectMapper json;private final AuditService audit;
    public AmlSource(JdbcTemplate jdbc,CddCrypto crypto,ObjectMapper json,AuditService audit) {this.jdbc=jdbc;this.crypto=crypto;this.json=json;this.audit=audit;}

    /** CDD가 사용자 행을 잠그고 평가·사건을 저장한 같은 업무 트랜잭션에서만 호출한다. */
    public void capture(long user,UUID assessment,UUID event,String digest,JsonNode decision) {
        transaction();
        var rows=jdbc.queryForList("""
            SELECT c.id,e.provider_time FROM cdd_assessments a JOIN edd_cases c ON c.user_id=a.user_id
             JOIN kyc_events e ON e.event_id=a.event_id AND e.attempt_id=a.attempt_id
             JOIN kyc_attempts t ON t.id=a.attempt_id JOIN cdd_profiles p ON p.user_id=a.user_id AND p.assessment_id=a.id
             WHERE a.id=? AND a.user_id=? AND a.event_id=? AND a.status_digest=? AND c.latest_assessment=a.id
              AND 'AML_REVIEW_REQUIRED'=ANY(string_to_array(a.reasons,',')) AND e.disposition='APPLIED' AND t.environment='live'
            """,assessment,user,event,digest);
        // 정상 고객이나 과거/시험 환경 평가를 추가 조사 자료로 복사하지 않는다.
        if(rows.isEmpty())return;
        var row=rows.getFirst();UUID id=UUID.randomUUID();var body=json.createObjectNode();
        body.put("id",id.toString()).put("caseId",row.get("id").toString()).put("userId",user)
            .put("assessmentId",assessment.toString()).put("eventId",event.toString()).put("statusDigest",digest)
            .put("policy",POLICY).put("providerTime",((Number)row.get("provider_time")).longValue()).set("report",project(decision));
        byte[] plain=json.writeValueAsBytes(body);String mac=crypto.fingerprint("aml-source",new String(plain,java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO edd_aml_sources(id,case_id,user_id,assessment_id,event_id,status_digest,provider_time,content_mac,key_id,report_cipher) VALUES(?,?,?,?,?,?,?,?,?,?)",
            id,row.get("id"),user,assessment,event,digest,row.get("provider_time"),mac,crypto.keyId(),crypto.encrypt(user,id,plain));
        audit.record(user,"EDD_AML_SOURCE_CAPTURED","aml-source:"+id,"SUCCESS",mac);
    }

    /**
     * 허용한 필드만 복사한다. 문서 번호·이미지·공급사 링크·임의 속성은 원문이나 암호문에도 넣지 않는다.
     * 잘못된 자료형은 null로 보존해 자료 부족을 드러내며 임의의 정상 값으로 바꾸지 않는다.
     */
    public JsonNode project(JsonNode decision) {
        var result=json.createObjectNode();var identities=result.putArray("identity");
        if(decision.path("id_verifications").isArray())for(var id:decision.path("id_verifications")) {
            var item=identities.addObject();scalars(item,id,"status","full_name","date_of_birth","nationality");
        }
        if(!decision.path("aml_screenings").isArray()){result.putNull("screenings");return result;}
        var screenings=result.putArray("screenings");
        for(var node:decision.path("aml_screenings")) {
            var item=screenings.addObject();scalars(item,node,"node_id","status","total_hits","entity_type","score");
            var subject=item.putObject("screened_data");scalars(subject,node.path("screened_data"),"full_name","date_of_birth","nationality");
            if(node.path("warnings").isArray()) {var warnings=item.putArray("warnings");for(var warning:node.path("warnings"))scalars(warnings.addObject(),warning,"risk");}
            else item.putNull("warnings");
            if(!node.path("hits").isArray()){item.putNull("hits");continue;}
            var hits=item.putArray("hits");
            for(var hit:node.path("hits")) {
                var candidate=hits.addObject();scalars(candidate,hit,"id","caption","match_score","risk_score","review_status");strings(candidate,hit,"datasets");
                var properties=candidate.putObject("properties");for(String field:List.of("name","alias","birthDate","citizenship"))strings(properties,hit.path("properties"),field);
            }
        }
        return result;
    }
    private void scalars(ObjectNode target,JsonNode source,String... fields) {
        for(String field:fields) {var value=source.path(field);if(value.isString()||value.isNumber()||value.isBoolean())target.set(field,value.deepCopy());else target.putNull(field);}
    }
    private void strings(ObjectNode target,JsonNode source,String field) {
        var value=source.path(field);boolean valid=value.isArray();if(valid)for(var v:value)if(!v.isString()){valid=false;break;}
        if(valid)target.set(field,value.deepCopy());else target.putNull(field);
    }
    public Metadata metadata(UUID id) {
        transaction();var rows=jdbc.queryForList("SELECT * FROM edd_aml_sources WHERE id=?",id);
        if(rows.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        var r=rows.getFirst();return new Metadata(id,(UUID)r.get("case_id"),((Number)r.get("user_id")).longValue(),(UUID)r.get("assessment_id"),(UUID)r.get("event_id"),
            (String)r.get("status_digest"),((Number)r.get("provider_time")).longValue(),(String)r.get("content_mac"),(String)r.get("key_id"),POLICY,ASSURANCE);
    }
    public Content content(Metadata m) {
        transaction();try {
            if(!crypto.keyId().equals(m.keyId())||!POLICY.equals(m.policy())||!ASSURANCE.equals(m.assurance()))throw new IllegalStateException();
            byte[] cipher=jdbc.queryForObject("SELECT report_cipher FROM edd_aml_sources WHERE id=?",byte[].class,m.id());
            byte[] plain=crypto.decrypt(m.userId(),m.id(),cipher);var body=json.readTree(plain);
            if(!m.contentMac().equals(crypto.fingerprint("aml-source",new String(plain,java.nio.charset.StandardCharsets.UTF_8)))
                || !body.path("id").asText().equals(m.id().toString()) || !body.path("caseId").asText().equals(m.caseId().toString())
                || body.path("userId").longValue()!=m.userId() || !body.path("assessmentId").asText().equals(m.assessmentId().toString())
                || !body.path("eventId").asText().equals(m.eventId().toString()) || !body.path("statusDigest").asText().equals(m.statusDigest())
                || body.path("providerTime").longValue()!=m.providerTime() || !POLICY.equals(body.path("policy").asText()))throw new IllegalStateException();
            return new Content(m,body.path("report"));
        } catch(RuntimeException e) {throw new IllegalStateException("AML 조사 근거의 해독·무결성 검증 실패");}
    }
    private void transaction() {if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("AML 근거에는 업무 트랜잭션이 필요합니다");}
}
