package dustin.cex.domains.edd;

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

/** 승인 행 자체가 같은 DB 안의 영속 알림이다. 별도 발송 상태를 복제하거나 고객 문구를 목록에 복호화하지 않는다. */
@Service
public class CustomerFinalNoticeService {
    public record Cursor(Instant decidedAt,UUID finalId) {}
    public record Item(UUID finalId,UUID reviewId,Instant decidedAt,Instant acknowledgedAt) {}
    public record Page(List<Item> items,Cursor next,String semantics){public Page{items=List.copyOf(items);}}
    private final JdbcTemplate jdbc;private final CustomerExitReviewService requests;private final ObjectMapper json;
    private final DiditWebhookVerifier verifier;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerFinalNoticeService(JdbcTemplate jdbc,CustomerExitReviewService requests,ObjectMapper json,DiditWebhookVerifier verifier,AuditService audit,PlatformTransactionManager manager){this.jdbc=jdbc;this.requests=requests;this.json=json;this.verifier=verifier;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public Page list(long actor,UUID sid,Cursor after){
        try{return tx.execute(t->{
            requests.customer(actor,sid);
            if(after!=null&&(after.decidedAt()==null||after.finalId()==null||after.decidedAt().isBefore(Instant.EPOCH)||after.decidedAt().isAfter(Instant.now())))throw denied(HttpStatus.BAD_REQUEST);
            String sql="""
                SELECT r.payload,r.payload_hash,d.request_id,d.review_id,d.decided_at,d.approver_id,d.approver_person,a.acknowledged_at
                FROM customer_exit_review_requests c JOIN customer_review_final_decisions d ON d.review_id=c.id AND d.approved
                JOIN customer_review_final_requests r ON r.id=d.request_id AND r.review_id=c.id
                LEFT JOIN customer_review_final_acknowledgments a ON a.final_id=d.request_id AND a.user_id=c.user_id
                WHERE c.user_id=?
                """;
            var rows=after==null?jdbc.queryForList(sql+" ORDER BY d.decided_at DESC,d.request_id DESC LIMIT 25",actor):
                jdbc.queryForList(sql+" AND (d.decided_at,d.request_id)<(?,?) ORDER BY d.decided_at DESC,d.request_id DESC LIMIT 25",actor,Timestamp.from(after.decidedAt()),after.finalId());
            var items=new ArrayList<Item>();
            for(var row:rows){
                String raw=(String)row.get("payload");var p=json.readValue(raw,CustomerFinalReviewService.Payload.class);
                if(!verifier.digest(json.readTree(raw)).equals(row.get("payload_hash"))||!CustomerFinalReviewService.POLICY.equals(p.policy())||p.userId()!=actor||!p.id().equals(row.get("request_id"))||!p.reviewId().equals(row.get("review_id"))||p.checker().id()!=((Number)row.get("approver_id")).longValue()||!p.checker().person().equals(row.get("approver_person")))throw new IllegalStateException("고객 알림의 실제 승인 연결 검증 실패");
                items.add(new Item(p.id(),p.reviewId(),((Timestamp)row.get("decided_at")).toInstant(),row.get("acknowledged_at")==null?null:((Timestamp)row.get("acknowledged_at")).toInstant()));
            }
            var last=items.isEmpty()?null:items.getLast();Cursor next=items.size()==25?new Cursor(last.decidedAt(),last.finalId()):null;
            // 목록을 제공해도 명시적 확인 이력은 쓰지 않는다. 감사 실패는 목록 응답도 실패시킨다.
            audit.record(actor,"CUSTOMER_FINAL_NOTICES_LISTED","customer:"+actor,"SUCCESS","METADATA_ONLY_NOT_ACKNOWLEDGED");
            return new Page(items,next,"REFRESH_FROM_START_NOT_A_DURABLE_CHECKPOINT");
        });}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_FINAL_NOTICES","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 고객 세션·알림 조회 위치를 확인하세요");}
}
