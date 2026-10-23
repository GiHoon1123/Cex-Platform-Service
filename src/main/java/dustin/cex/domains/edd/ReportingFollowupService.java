package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import dustin.cex.domains.security.AuditService;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 승인된 보고 필요의 후속 확인을 기록한다. 외부 제출을 수행하거나 접수 참조의 진위를 자동 인증하지 않는다.
 * 영구 소비 위치나 현재 고객 상태로 과거 확정 의무를 숨기지 않고, 지정된 두 인물에게 상한이 있는 조회를 제공한다.
 */
@Service
public class ReportingFollowupService {
    public static final String POLICY="REPORTING_FOLLOWUP_OBSERVATION_V1";
    public static final String ASSURANCE="STAFF_OBSERVATION_ONLY_EXTERNAL_RECEIPT_UNVERIFIED";
    public static final String DEADLINE="LEGAL_DEADLINE_REQUIRES_OFFICER_CONFIRMATION";
    public enum Outcome { OUTCOME_UNKNOWN, REJECTION_OBSERVED, RECEIPT_REFERENCE_OBSERVED }
    public record Facts(Outcome outcome,Instant occurredAt,String externalReference,String notes) {
        public Facts validated() {
            if(outcome==null||occurredAt==null||occurredAt.isBefore(Instant.EPOCH)||occurredAt.isAfter(Instant.now()))throw denied(HttpStatus.BAD_REQUEST);
            String reference=clean(externalReference,200,true),reason=clean(notes,2000,false);
            if(outcome==Outcome.RECEIPT_REFERENCE_OBSERVED&&reference.isEmpty())throw denied(HttpStatus.BAD_REQUEST);
            return new Facts(outcome,occurredAt.truncatedTo(ChronoUnit.MICROS),reference,reason);
        }
        @Override public String toString() {return "Facts[외부 참조·메모 생략]";}
    }
    public record Payload(UUID id,UUID reviewId,long userId,String reviewHash,String policy,String assurance,Outcome outcome,
        String factsMac,String evidenceKeyId,String cddKeyId,long actorId,UUID actorPerson) {}
    public record Observation(Payload payload,String payloadHash,Instant recordedAt) {}
    public record Detail(Observation observation,Facts facts) {
        @Override public String toString() {return "Detail[외부 참조·메모 생략]";}
    }
    public record Cursor(Instant decidedAt,UUID reviewId) {}
    public record Item(UUID reviewId,long userId,Instant internalDecisionAt,Instant internalCheckTargetAt,boolean internalCheckOverdue,
        String legalDeadlineStatus,String taskStatus,Observation latestObservation) {}
    public record Page(List<Item> items,Cursor next,int scanned,String semantics) {
        public Page {items=List.copyOf(items);}
    }
    private final JdbcTemplate jdbc;private final ReportingReviewService reviews;private final EvidenceCrypto crypto;
    private final CddCrypto cdd;private final ObjectMapper json;private final DiditWebhookVerifier verifier;private final AuditService audit;private final TransactionTemplate tx;
    public ReportingFollowupService(JdbcTemplate jdbc,ReportingReviewService reviews,EvidenceCrypto crypto,CddCrypto cdd,ObjectMapper json,
            DiditWebhookVerifier verifier,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.reviews=reviews;this.crypto=crypto;this.cdd=cdd;this.json=json;this.verifier=verifier;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    /**
     * 현재 사건·고객 확인 기한을 조건으로 삼지 않는다. 승인 후 바뀐 사실이 이미 확정된 보고 필요를 없애지는 않는다.
     * 내부 목표는 승인 시각부터 24시간이라는 자체 점검 기준이며 법정 3영업일을 계산한 값이 아니다.
     */
    public Page queue(long actor,UUID sid,Cursor after) {
        return attempt(actor,()->tx.execute(s->{
            if(after!=null&&(after.decidedAt()==null||after.reviewId()==null||after.decidedAt().isBefore(Instant.EPOCH)||after.decidedAt().isAfter(Instant.now())))throw denied(HttpStatus.BAD_REQUEST);
            var batch=reviews.deliveryBatch(actor,sid,after==null?null:after.decidedAt(),after==null?null:after.reviewId());
            var items=batch.origins().stream().map(origin->{Instant target=origin.decidedAt().plusSeconds(86400);
                return new Item(origin.payload().id(),origin.payload().userId(),origin.decidedAt(),target,!target.isAfter(Instant.now()),DEADLINE,"FOLLOW_UP_REQUIRED",latest(origin));}).toList();
            Cursor next=batch.more()?new Cursor(batch.lastDecision(),batch.lastReview()):null;
            audit.record(actor,"REPORTING_QUEUE_READ","operator:"+actor,"SUCCESS","ASSIGNED_HISTORY_SCAN");return new Page(items,next,batch.scanned(),"REFRESH_FROM_START_NOT_A_DURABLE_CHECKPOINT");
        }));
    }
    /** 관찰은 추가만 허용한다. 같은 키/내용은 같은 기록이고 다른 내용은 충돌한다. 확정된 보고 필요를 완료로 바꾸지 않는다. */
    public Observation record(long actor,UUID sid,UUID review,UUID key,Facts supplied) {
        return attempt(actor,()->tx.execute(s->{
            if(review==null||key==null||supplied==null)throw denied(HttpStatus.BAD_REQUEST);var origin=reviews.deliveryOrigin(actor,sid,review);
            var value=supplied.validated();String mac=fingerprint(origin,value);
            var prior=jdbc.queryForList("SELECT id FROM reporting_followups WHERE actor_id=? AND request_key=?",actor,key);
            if(!prior.isEmpty()) {var old=read((UUID)prior.getFirst().get("id"));if(!old.payload().reviewId().equals(review)||!old.payload().factsMac().equals(mac))throw denied(HttpStatus.CONFLICT);
                audit.record(actor,"REPORTING_FOLLOWUP_REPLAYED","reporting-followup:"+old.payload().id(),"SUCCESS","SAME_OBSERVATION");return old;}
            if(jdbc.queryForObject("SELECT count(*) FROM reporting_followups WHERE review_id=? AND recorded_at>clock_timestamp()-interval '1 day'",Long.class,review)>=20)throw denied(HttpStatus.TOO_MANY_REQUESTS);
            UUID id=UUID.randomUUID();var maker=jdbc.queryForMap("SELECT operator_person_id FROM users WHERE id=?",actor);
            var p=new Payload(id,review,origin.payload().userId(),origin.payloadHash(),POLICY,ASSURANCE,value.outcome(),mac,crypto.keyId(),cdd.keyId(),actor,(UUID)maker.get("operator_person_id"));
            String hash=verifier.digest(json.valueToTree(p));byte[] cipher=crypto.encrypt(context(p),json.writeValueAsBytes(value));
            jdbc.update("INSERT INTO reporting_followups(id,review_id,user_id,actor_id,request_key,payload,payload_hash,facts_cipher) VALUES(?,?,?,?,?,?,?,?)",id,review,p.userId(),actor,key,json.writeValueAsString(p),hash,cipher);
            // 감사에는 외부 참조·메모·관찰 방향을 복사하지 않는다.
            audit.record(actor,"REPORTING_FOLLOWUP_RECORDED","reporting-followup:"+id,"SUCCESS",hash);return read(id);
        }));
    }
    public Detail inspect(long actor,UUID sid,UUID id) {
        return attempt(actor,()->tx.execute(s->{
            var r=read(id);var origin=reviews.deliveryOrigin(actor,sid,r.payload().reviewId());verifyOrigin(r,origin);var facts=facts(r,origin);
            audit.record(actor,"REPORTING_FOLLOWUP_VIEWED","reporting-followup:"+id,"SUCCESS","ASSIGNED_ONLY");return new Detail(r,facts);
        }));
    }
    public List<Observation> history(long actor,UUID sid,UUID review,int page) {
        return attempt(actor,()->tx.execute(s->{
            var origin=reviews.deliveryOrigin(actor,sid,review);if(page<0||page>10000)throw denied(HttpStatus.BAD_REQUEST);
            var result=jdbc.queryForList("SELECT id FROM reporting_followups WHERE review_id=? ORDER BY recorded_at DESC,id DESC LIMIT 25 OFFSET ?",review,page*25).stream().map(row->{var r=read((UUID)row.get("id"));verifyOrigin(r,origin);return r;}).toList();
            audit.record(actor,"REPORTING_FOLLOWUPS_LISTED","compliance-review:"+review,"SUCCESS","METADATA_ONLY");return result;
        }));
    }
    private Observation latest(ReportingReviewService.DeliveryOrigin origin) {
        var rows=jdbc.queryForList("SELECT id FROM reporting_followups WHERE review_id=? ORDER BY recorded_at DESC,id DESC LIMIT 1",origin.payload().id());
        if(rows.isEmpty())return null;var result=read((UUID)rows.getFirst().get("id"));verifyOrigin(result,origin);return result;
    }
    private void verifyOrigin(Observation r,ReportingReviewService.DeliveryOrigin origin) {
        if(!r.payload().reviewId().equals(origin.payload().id())||r.payload().userId()!=origin.payload().userId()||!r.payload().reviewHash().equals(origin.payloadHash()))throw new IllegalStateException("후속 관찰의 확정 근거 검증 실패");
    }
    private Facts facts(Observation r,ReportingReviewService.DeliveryOrigin origin) {
        var p=r.payload();try {
            var cipher=jdbc.queryForObject("SELECT facts_cipher FROM reporting_followups WHERE id=?",byte[].class,p.id());
            var value=json.readValue(crypto.decrypt(p.evidenceKeyId(),context(p),cipher),Facts.class).validated();
            if(!p.cddKeyId().equals(cdd.keyId())||value.outcome()!=p.outcome()||!p.factsMac().equals(fingerprint(origin,value)))throw new IllegalStateException();return value;
        } catch(RuntimeException e) {throw new IllegalStateException("외부 결과 관찰의 해독·무결성 검증 실패");}
    }
    private String fingerprint(ReportingReviewService.DeliveryOrigin origin,Facts facts) {return cdd.fingerprint("reporting-followup",json.writeValueAsString(List.of(origin.payload().id(),origin.payloadHash(),facts)));}
    private String context(Payload p) {return "REPORTING_FOLLOWUP:"+p.id()+":"+p.userId()+":"+p.reviewId()+":"+p.reviewHash()+":"+p.factsMac();}
    private Observation read(UUID id) {
        var rows=jdbc.queryForList("SELECT payload,payload_hash,recorded_at FROM reporting_followups WHERE id=?",id);if(rows.isEmpty())throw denied(HttpStatus.NOT_FOUND);
        var row=rows.getFirst();String raw=(String)row.get("payload");var p=json.readValue(raw,Payload.class);
        if(!p.id().equals(id)||!POLICY.equals(p.policy())||!ASSURANCE.equals(p.assurance())||!verifier.digest(json.readTree(raw)).equals(row.get("payload_hash")))throw new IllegalStateException("후속 관찰의 메타데이터 검증 실패");
        return new Observation(p,(String)row.get("payload_hash"),((Timestamp)row.get("recorded_at")).toInstant());
    }
    private static String clean(String input,int maximum,boolean allowEmpty) {
        if(input==null)throw denied(HttpStatus.BAD_REQUEST);String value=Normalizer.normalize(input,Normalizer.Form.NFC).strip();
        if(value.length()>maximum||(!allowEmpty&&value.isBlank())||value.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))throw denied(HttpStatus.BAD_REQUEST);return value;
    }
    private <T>T attempt(long actor,Supplier<T> work) {try{return work.get();}catch(ResponseStatusException e){audit.denied(actor,"REPORTING_FOLLOWUP","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}}
    private static ResponseStatusException denied(HttpStatus status) {return new ResponseStatusException(status,"현재 보고 근거·업무 배정·관찰 입력을 확인하세요");}
}
