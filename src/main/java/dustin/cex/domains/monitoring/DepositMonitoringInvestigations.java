package dustin.cex.domains.monitoring;

import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.bitcoin.*;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.edd.EvidenceCrypto;
import dustin.cex.domains.security.AuditService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.text.Normalizer;
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

/** 거래 패턴의 당시 입력을 확인하고 담당자 의견을 보존한다. 신원 조사·종결·보고 결정·금융 허가와 구분한다. */
@Service
@DependsOn("depositMonitoringSchemaInitializer")
public class DepositMonitoringInvestigations {
    public static final String ASSURANCE="ANALYST_OBSERVATION_ONLY_NOT_A_RESOLUTION_OR_FINANCIAL_PERMISSION";
    public enum Finding {EXPLAINED_ACTIVITY,REQUIRES_FURTHER_REVIEW,INSUFFICIENT_INFORMATION}
    public record Metadata(UUID id,String cursor,Instant referenceAt,String policy,String outcome,String reason,int smallTransfers,String smallTotalKrw,int unknownTransfers,String sourceHash,Instant recordedAt){}
    public record Page(List<Metadata> items,String nextBefore){}
    public record Input(String cursor,UUID receiptId,String asset,String network,String txid,Instant recognizedAt,String currency,String valuationStatus,String priceAttemptId,String krwValue){
        @Override public String toString(){return "MonitoringInput[금융 자료 생략]";}
    }
    public record Viewed(UUID viewId,Metadata assessment,List<Input> inputs,String assurance){
        @Override public String toString(){return "MonitoringViewed[금융 자료 생략]";}
    }
    public record Write(UUID viewId,UUID requestKey,Finding finding,String notes){
        @Override public String toString(){return "MonitoringWrite[조사 내용 생략]";}
    }
    public record Observation(UUID id,UUID assessmentId,UUID viewId,long actorId,Finding finding,Instant recordedAt,String assurance){}
    public record Detail(Observation observation,String notes){
        @Override public String toString(){return "MonitoringDetail[조사 내용 생략]";}
    }
    private record Verified(Metadata metadata,List<SmallDepositMonitoringPolicy.Input> inputs){}
    private final BitcoinDepositOperatorAccess access;
    private final BitcoinSettings settings;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CddCrypto cdd;
    private final EvidenceCrypto crypto;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public DepositMonitoringInvestigations(BitcoinDepositOperatorAccess access,BitcoinSettings settings,JdbcTemplate jdbc,AuditService audit,CddCrypto cdd,EvidenceCrypto crypto,ObjectMapper json,PlatformTransactionManager manager){
        this.access=access;this.settings=settings;this.jdbc=jdbc;this.audit=audit;this.cdd=cdd;this.crypto=crypto;this.json=json;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 입금 소유자의 계정 단위 이력을 원장 순번으로 탐색한다. 개별 거래 입력·메모를 목록에 복사하지 않는다. */
    public Page list(long actor,UUID sid,UUID receipt,Long before){return work(actor,"MONITORING_ASSESSMENTS_LISTED",()->{
        var allowed=access.require(actor,sid,receipt,settings,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);
        if(before!=null&&before<=0)throw denied(HttpStatus.BAD_REQUEST);
        var rows=jdbc.queryForList("SELECT * FROM deposit_monitoring_assessments WHERE user_id=? AND input_cursor<? ORDER BY input_cursor DESC LIMIT 26",allowed.customer().getId(),before==null?Long.MAX_VALUE:before);
        var items=rows.stream().limit(25).map(this::metadata).toList();String next=rows.size()>25?items.getLast().cursor():null;
        audit.record(actor,"MONITORING_ASSESSMENTS_LISTED","bitcoin-deposit:"+receipt,"SUCCESS","METADATA_ONLY");return new Page(items,next);
    });}
    /** 실제 불변 금융 원본과 저장 근거·정책 결과를 재검증한 뒤 열람 증적과 필수 감사를 남긴다. */
    public Viewed inspect(long actor,UUID sid,UUID receipt,UUID assessment){return work(actor,"MONITORING_EVIDENCE_INSPECTED",()->{
        var allowed=access.require(actor,sid,receipt,settings,UserRole.OPERATOR,UserRole.APPROVER);var verified=verified(allowed.customer().getId(),assessment);UUID view=UUID.randomUUID();
        jdbc.update("INSERT INTO deposit_monitoring_source_views(id,assessment_id,actor_id,actor_person,actor_version,session_id,source_hash) VALUES(?,?,?,?,?,?,?)",view,assessment,actor,allowed.actor().getOperatorPersonId(),allowed.actor().getVersion(),sid,verified.metadata().sourceHash());
        audit.record(actor,"MONITORING_EVIDENCE_INSPECTED","monitoring-source-view:"+view,"SUCCESS",verified.metadata().sourceHash());
        var inputs=verified.inputs().stream().map(i->new Input(Long.toString(i.cursor()),i.receiptId(),i.asset(),i.network(),i.txid(),i.recognizedAt(),i.currency(),i.valuationStatus(),i.priceAttemptId()==null?null:i.priceAttemptId().toString(),i.krwValue()==null?null:i.krwValue().toPlainString())).toList();
        return new Viewed(view,verified.metadata(),inputs,ASSURANCE);
    });}
    /** 직접 확인한 자료에만 의견을 붙인다. 과거 신호를 정상으로 덮지 않고 재전송은 같은 불변 의견을 반환한다. */
    public Observation record(long actor,UUID sid,UUID receipt,UUID assessment,Write request){return work(actor,"MONITORING_OBSERVATION_RECORDED",()->{
        var allowed=access.require(actor,sid,receipt,settings,UserRole.OPERATOR);
        if(request==null||request.viewId()==null||request.requestKey()==null||request.finding()==null)throw denied(HttpStatus.BAD_REQUEST);
        String text=notes(request.notes()),mac=cdd.fingerprint("monitoring-observation-notes",text);crypto.requireConfigured();
        // 재전송도 현재 요청 경로의 실제 고객·분석 원본에 속해야 한다. 키 일치만으로 다른 고객의 의견을 빌려주지 않는다.
        var checked=verified(allowed.customer().getId(),assessment);
        if("NO_SIGNAL".equals(checked.metadata().outcome()))throw denied(HttpStatus.CONFLICT);
        var old=jdbc.queryForList("SELECT * FROM deposit_monitoring_observations WHERE actor_id=? AND request_key=?",actor,request.requestKey());
        if(!old.isEmpty()){
            var row=old.getFirst();if(!assessment.equals(row.get("assessment_id"))||!request.viewId().equals(row.get("view_id"))||!request.finding().name().equals(row.get("finding"))||!mac.equals(row.get("notes_mac")))throw denied(HttpStatus.CONFLICT);
            decrypt(row);return observation(row);
        }
        boolean viewed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deposit_monitoring_source_views WHERE id=? AND assessment_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND source_hash=?)",Boolean.class,request.viewId(),assessment,actor,allowed.actor().getOperatorPersonId(),allowed.actor().getVersion(),sid,checked.metadata().sourceHash()));
        if(!viewed)throw denied(HttpStatus.CONFLICT);
        UUID id=UUID.randomUUID();String context=context(id,assessment,request.viewId(),actor,allowed.actor().getOperatorPersonId(),allowed.actor().getVersion(),sid,request.finding().name(),checked.metadata().sourceHash(),mac,cdd.keyId());
        jdbc.update("INSERT INTO deposit_monitoring_observations(id,assessment_id,view_id,actor_id,actor_person,actor_version,session_id,request_key,finding,source_hash,notes_mac,fingerprint_key_id,evidence_key_id,notes_cipher) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,assessment,request.viewId(),actor,allowed.actor().getOperatorPersonId(),allowed.actor().getVersion(),sid,request.requestKey(),request.finding().name(),checked.metadata().sourceHash(),mac,cdd.keyId(),crypto.keyId(),crypto.encrypt(context,text.getBytes(StandardCharsets.UTF_8)));
        audit.record(actor,"MONITORING_OBSERVATION_RECORDED","monitoring-observation:"+id,"SUCCESS",request.finding().name());return observation(jdbc.queryForMap("SELECT * FROM deposit_monitoring_observations WHERE id=?",id));
    });}
    /** 감사자는 분류·작성 이력만 읽는다. 조사 메모의 평문·암호문·키와 지문은 이 목록에 포함하지 않는다. */
    public List<Observation> observations(long actor,UUID sid,UUID receipt,UUID assessment,int page){return work(actor,"MONITORING_OBSERVATIONS_LISTED",()->{
        var allowed=access.require(actor,sid,receipt,settings,UserRole.OPERATOR,UserRole.APPROVER,UserRole.AUDITOR);row(allowed.customer().getId(),assessment);
        if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);
        var result=jdbc.queryForList("SELECT * FROM deposit_monitoring_observations WHERE assessment_id=? ORDER BY recorded_at DESC,id DESC LIMIT 25 OFFSET ?",assessment,page*25).stream().map(this::observation).toList();
        audit.record(actor,"MONITORING_OBSERVATIONS_LISTED","monitoring-assessment:"+assessment,"SUCCESS","METADATA_ONLY");return result;
    });}
    /** 메모의 대상과 작성 맥락을 인증 복호화하고 접근 증적을 추가한다. 이전 열람 이력은 수정하지 않는다. */
    public Detail inspectObservation(long actor,UUID sid,UUID receipt,UUID assessment,UUID id){return work(actor,"MONITORING_OBSERVATION_INSPECTED",()->{
        var allowed=access.require(actor,sid,receipt,settings,UserRole.OPERATOR,UserRole.APPROVER);verified(allowed.customer().getId(),assessment);
        var rows=jdbc.queryForList("SELECT * FROM deposit_monitoring_observations WHERE id=? AND assessment_id=?",id,assessment);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);var row=rows.getFirst();String text=decrypt(row);UUID view=UUID.randomUUID();
        jdbc.update("INSERT INTO deposit_monitoring_observation_views(id,observation_id,actor_id,actor_person,actor_version,session_id) VALUES(?,?,?,?,?,?)",view,id,actor,allowed.actor().getOperatorPersonId(),allowed.actor().getVersion(),sid);
        audit.record(actor,"MONITORING_OBSERVATION_INSPECTED","monitoring-observation-view:"+view,"SUCCESS","READ");return new Detail(observation(row),text);
    });}
    private Verified verified(long owner,UUID id){
        var row=row(owner,id);var meta=metadata(row);
        try{
            String document=(String)row.get("source_document");String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document.getBytes(StandardCharsets.UTF_8)));
            var stored=List.of(json.readValue(document,SmallDepositMonitoringPolicy.Input[].class));
            var original=DepositMonitoringInputs.read(jdbc,owner,Long.parseLong(meta.cursor()),meta.referenceAt());
            var result=SmallDepositMonitoringPolicy.assess(meta.referenceAt(),stored);
            // 저장 문서의 지문만 맞아도 기준 시각·순번이 실제 마지막 원장 입력과 다르면 거부한다.
            if(original.isEmpty()||original.getLast().cursor()!=Long.parseLong(meta.cursor())||!original.getLast().recognizedAt().equals(meta.referenceAt()))throw new IllegalStateException();
            if(!meta.id().equals(id)||!SmallDepositMonitoringPolicy.POLICY.equals(meta.policy())||!hash.equals(meta.sourceHash())||!stored.equals(original)||stored.size()!=((Number)row.get("source_count")).intValue()
                ||!result.outcome().equals(meta.outcome())||!result.reason().equals(meta.reason())||result.smallTransfers()!=meta.smallTransfers()||result.unknownTransfers()!=meta.unknownTransfers()||result.smallTotalKrw().compareTo(new BigDecimal(meta.smallTotalKrw()))!=0)throw new IllegalStateException();
            return new Verified(meta,stored);
        }catch(Exception invalid){throw denied(HttpStatus.SERVICE_UNAVAILABLE);}
    }
    private Map<String,Object> row(long owner,UUID id){var rows=jdbc.queryForList("SELECT * FROM deposit_monitoring_assessments WHERE id=? AND user_id=?",id,owner);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();}
    private Metadata metadata(Map<String,Object> r){return new Metadata((UUID)r.get("id"),r.get("input_cursor").toString(),((Timestamp)r.get("reference_at")).toInstant(),(String)r.get("policy_id"),(String)r.get("outcome"),(String)r.get("reason"),((Number)r.get("small_transfers")).intValue(),((BigDecimal)r.get("small_total_krw")).toPlainString(),((Number)r.get("unknown_transfers")).intValue(),(String)r.get("source_hash"),((Timestamp)r.get("recorded_at")).toInstant());}
    private Observation observation(Map<String,Object> r){return new Observation((UUID)r.get("id"),(UUID)r.get("assessment_id"),(UUID)r.get("view_id"),((Number)r.get("actor_id")).longValue(),Finding.valueOf((String)r.get("finding")),((Timestamp)r.get("recorded_at")).toInstant(),ASSURANCE);}
    private String decrypt(Map<String,Object> r){try{
        if(!cdd.keyId().equals(r.get("fingerprint_key_id")))throw new IllegalStateException();
        String context=context((UUID)r.get("id"),(UUID)r.get("assessment_id"),(UUID)r.get("view_id"),((Number)r.get("actor_id")).longValue(),(UUID)r.get("actor_person"),((Number)r.get("actor_version")).longValue(),(UUID)r.get("session_id"),(String)r.get("finding"),(String)r.get("source_hash"),(String)r.get("notes_mac"),(String)r.get("fingerprint_key_id"));
        String text=new String(crypto.decrypt((String)r.get("evidence_key_id"),context,(byte[])r.get("notes_cipher")),StandardCharsets.UTF_8);
        if(!cdd.fingerprint("monitoring-observation-notes",text).equals(r.get("notes_mac")))throw new IllegalStateException();return text;
    }catch(RuntimeException invalid){throw denied(HttpStatus.SERVICE_UNAVAILABLE);}}
    /** 인증 부가 데이터에 의견의 경제적 대상·작성자·열람·분류를 묶어 암호문 바꿔치기를 검출한다. */
    private String context(UUID id,UUID assessment,UUID view,long actor,UUID person,long version,UUID sid,String finding,String source,String mac,String fingerprint){return "MONITORING_OBSERVATION_V1:"+id+":"+assessment+":"+view+":"+actor+":"+person+":"+version+":"+sid+":"+finding+":"+source+":"+mac+":"+fingerprint;}
    private String notes(String value){
        if(value==null||value.isBlank()||value.length()>2000)throw denied(HttpStatus.BAD_REQUEST);
        for(int c:value.codePoints().toArray())if((Character.isISOControl(c)&&c!='\n')||Character.getType(c)==Character.FORMAT||Character.getType(c)==Character.SURROGATE)throw denied(HttpStatus.BAD_REQUEST);
        return Normalizer.normalize(value,Normalizer.Form.NFC).strip();
    }
    private <T>T work(long actor,String action,Supplier<T> callback){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("거래 조사 업무는 독립 트랜잭션으로 시작하세요");
        try{return tx.execute(t->callback.get());}catch(ResponseStatusException rejected){audit.denied(actor,action,"monitoring-restricted","POLICY_OR_SOURCE_"+rejected.getStatusCode().value());throw rejected;}
    }
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 운영 인증·탐지 원본·열람 근거를 확인하세요");}
}
