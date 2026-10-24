package dustin.cex.domains.edd;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import dustin.cex.domains.security.AuditService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 공개 진행 회신과 최종 승인의 원본이 영속 알림이다. 별도 발송 상태·문구 사본을 만들지 않는다. */
@Service
public class CustomerReviewNoticeService {
    public enum Kind { STATUS, FINAL }
    public record Cursor(Instant occurredAt,Kind kind,UUID noticeId) {}
    public record Item(Kind kind,UUID noticeId,UUID reviewId,Instant occurredAt,Instant finalAcknowledgedAt) {}
    public record Page(List<Item> items,Cursor next,String semantics){public Page{items=List.copyOf(items);}}
    private final JdbcTemplate jdbc;private final CustomerExitReviewService requests;private final ObjectMapper json;
    private final DiditWebhookVerifier verifier;private final CddCrypto cdd;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerReviewNoticeService(JdbcTemplate jdbc,CustomerExitReviewService requests,ObjectMapper json,DiditWebhookVerifier verifier,CddCrypto cdd,AuditService audit,PlatformTransactionManager manager){this.jdbc=jdbc;this.requests=requests;this.json=json;this.verifier=verifier;this.cdd=cdd;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public Page list(long actor,UUID sid,Cursor after){
        try{return tx.execute(t->{
            requests.customer(actor,sid);
            if(after!=null&&(after.occurredAt()==null||after.kind()==null||after.noticeId()==null||after.occurredAt().isBefore(Instant.EPOCH)||after.occurredAt().isAfter(Instant.now())))throw denied(HttpStatus.BAD_REQUEST);
            String sql="""
                SELECT * FROM (
                    SELECT 'STATUS'::text AS kind,r.id AS notice_id,r.request_id AS review_id,
                        (r.payload::jsonb->>'observedAt')::timestamptz AS occurred_at,r.payload,r.content_mac AS integrity,
                        r.fingerprint_key_id AS key_id,NULL::bigint AS approver_id,NULL::uuid AS approver_person,
                        NULL::timestamptz AS final_ack,r.sequence AS logical_sequence,r.note_id
                    FROM customer_exit_review_requests c JOIN customer_exit_review_replies r ON r.request_id=c.id WHERE c.user_id=?
                    UNION ALL
                    SELECT 'FINAL',d.request_id,d.review_id,d.decided_at,f.payload,f.payload_hash,NULL,
                        d.approver_id,d.approver_person,a.acknowledged_at,NULL::bigint,NULL::uuid
                    FROM customer_exit_review_requests c JOIN customer_review_final_decisions d ON d.review_id=c.id AND d.approved
                    JOIN customer_review_final_requests f ON f.id=d.request_id AND f.review_id=c.id
                    LEFT JOIN customer_review_final_acknowledgments a ON a.final_id=d.request_id AND a.user_id=c.user_id
                    WHERE c.user_id=?
                ) notices
                """;
            var rows=after==null?jdbc.queryForList(sql+" ORDER BY occurred_at DESC,kind DESC,notice_id DESC LIMIT 25",actor,actor):
                jdbc.queryForList(sql+" WHERE (occurred_at,kind,notice_id)<(?,?,?) ORDER BY occurred_at DESC,kind DESC,notice_id DESC LIMIT 25",actor,actor,Timestamp.from(after.occurredAt()),after.kind().name(),after.noticeId());
            var items=new ArrayList<Item>();for(var row:rows)items.add(metadata(actor,row));
            var last=items.isEmpty()?null:items.getLast();Cursor next=items.size()==25?new Cursor(last.occurredAt(),last.kind(),last.noticeId()):null;
            audit.record(actor,"CUSTOMER_REVIEW_NOTICES_LISTED","customer:"+actor,"SUCCESS","METADATA_ONLY_NOT_ACKNOWLEDGED");
            return new Page(items,next,"REFRESH_FROM_START_NOT_A_DURABLE_CHECKPOINT");
        });}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_REVIEW_NOTICES","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private Item metadata(long actor,Map<String,Object> row){
        Kind kind=Kind.valueOf((String)row.get("kind"));String raw=(String)row.get("payload");UUID id=(UUID)row.get("notice_id"),review=(UUID)row.get("review_id");
        if(kind==Kind.FINAL){
            var p=json.readValue(raw,CustomerFinalReviewService.Payload.class);
            if(!verifier.digest(json.readTree(raw)).equals(row.get("integrity"))||!CustomerFinalReviewService.POLICY.equals(p.policy())||p.userId()!=actor||!p.id().equals(id)||!p.reviewId().equals(review)||p.checker().id()!=((Number)row.get("approver_id")).longValue()||!p.checker().person().equals(row.get("approver_person")))throw new IllegalStateException("고객 알림의 실제 최종 승인 연결 검증 실패");
        }else{
            var p=json.readValue(raw,CustomerReviewReplyService.Payload.class);
            // 상태 회신의 해시는 비밀키 지문이다. 최종 승인의 일반 해시와 혼동하지 않고 원래 검증 방식을 유지한다.
            if(!cdd.keyId().equals(row.get("key_id"))||!cdd.fingerprint("customer-review-public-reply",raw).equals(row.get("integrity"))||p.userId()!=actor||!p.id().equals(id)||!p.requestId().equals(review)||p.sequence()!=((Number)row.get("logical_sequence")).longValue()||!p.noteId().equals(row.get("note_id")))throw new IllegalStateException("고객 알림의 실제 상태 회신 연결 검증 실패");
        }
        return new Item(kind,id,review,((Timestamp)row.get("occurred_at")).toInstant(),row.get("final_ack")==null?null:((Timestamp)row.get("final_ack")).toInstant());
    }
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 고객 세션·알림 조회 위치를 확인하세요");}
}
