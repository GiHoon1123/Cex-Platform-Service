package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 고객 요청을 원문 없이 전사 점검한다. 열람 기록은 서버의 제공 기록이며 처리 완료나 고객 통지가 아니다. */
@Service
public class CustomerReviewSupervisionService {
    public static final String SEMANTICS="REFRESH_FROM_START_NOT_A_DURABLE_CHECKPOINT";
    public record Cursor(Instant receivedAt,UUID requestId) {}
    public record Member(long id,boolean available) {}
    public record Item(UUID requestId,long userId,UUID exitId,Instant receivedAt,Instant latestViewRecordedAt,List<Member> members,boolean orphaned,CustomerReviewResponseTarget.Assessment responseTarget,UUID finalReviewId,Instant finalDecidedAt,Instant customerAcknowledgedAt,CustomerReviewUpdateTarget.Assessment updateTarget,CustomerReviewCompletionTarget.Assessment completionReview) {
        public Item {members=List.copyOf(members);}
        public boolean requiresAttention(){return updateTarget.state()==CustomerReviewUpdateTarget.State.OVERDUE||updateTarget.state()==CustomerReviewUpdateTarget.State.TIME_REVIEW_REQUIRED||completionReview.state()==CustomerReviewCompletionTarget.State.OPEN_REVIEW_DUE||completionReview.state()==CustomerReviewCompletionTarget.State.TIME_REVIEW_REQUIRED||responseTarget.state()==CustomerReviewResponseTarget.State.OVERDUE||responseTarget.state()==CustomerReviewResponseTarget.State.TIME_REVIEW_REQUIRED||(finalReviewId!=null&&customerAcknowledgedAt==null)||(orphaned&&finalReviewId==null);}
    }
    public record Page(List<Item> items,Cursor next,int scanned,Instant observedAt,String semantics) {
        public Page {items=List.copyOf(items);}
    }
    private final CustomerReviewUpdateTimeline updates;
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final RelationshipExitService exits;private final CustomerWorkCustody custody;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerReviewSupervisionService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,
            RelationshipExitService exits,AuditService audit,PlatformTransactionManager manager,CustomerWorkCustody custody,CustomerReviewUpdateTimeline updates) {
        this.updates=updates;
        this.custody=custody;
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.exits=exits;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    public Page queue(long actor,UUID sid,Cursor after){return scan(actor,sid,after,false);}
    public Page attention(long actor,UUID sid,Cursor after){
        // 전체 감독과 같은 관측/커서를 쓰되 지금 조치할 건만 고른다. 과거 지연 이력은 전체 목록에 남는다.
        return scan(actor,sid,after,true);
    }
    private Page scan(long actor,UUID sid,Cursor after,boolean attentionOnly) {
        try {return tx.execute(t->{
            sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.APPROVER);
            Instant observed=Instant.now();validate(after,observed);
            // 고객·담당자 전체를 잠그지 않는다. 이 목록은 인계나 결정 권한이 아닌 발견용 정보다.
            var candidates=after==null?jdbc.queryForList("SELECT id,user_id,exit_id,created_at FROM customer_exit_review_requests ORDER BY created_at,id LIMIT 25"):
                jdbc.queryForList("SELECT id,user_id,exit_id,created_at FROM customer_exit_review_requests WHERE (created_at,id)>(?,?) ORDER BY created_at,id LIMIT 25",Timestamp.from(after.receivedAt()),after.requestId());
            var items=new ArrayList<Item>();
            for(var row:candidates) {
                UUID id=(UUID)row.get("id");var original=exits.approvedOrigin((UUID)row.get("exit_id"));var p=original.payload();
                if(p.userId()!=((Number)row.get("user_id")).longValue())throw new IllegalStateException("고객 재검토 대상 검증 실패");
                var state=custody.current(id);var members=state.members().stream().map(m->member(m.id(),m.person())).toList();
                Timestamp viewed=jdbc.queryForObject("SELECT max(occurred_at) FROM security_audit WHERE action='CUSTOMER_EXIT_REVIEW_VIEWED' AND outcome='SUCCESS' AND target=?",Timestamp.class,"customer-review:"+id);
                var progress=jdbc.queryForMap("""
                    SELECT (SELECT min((payload::jsonb->>'observedAt')::timestamptz) FROM customer_exit_review_replies WHERE request_id=?) AS first_reply,
                    d.request_id,d.decided_at,a.acknowledged_at FROM (SELECT 1) seed
                    LEFT JOIN customer_review_final_decisions d ON d.review_id=? AND d.approved
                    LEFT JOIN customer_review_final_acknowledgments a ON a.final_id=d.request_id
                    """,id,id);
                var assessment=CustomerReviewResponseTarget.assess(((Timestamp)row.get("created_at")).toInstant(),instant(progress.get("first_reply")),instant(progress.get("decided_at")),Instant.now());
                var item=new Item(id,p.userId(),p.id(),((Timestamp)row.get("created_at")).toInstant(),viewed==null?null:viewed.toInstant(),members,members.stream().noneMatch(Member::available),assessment,(UUID)progress.get("request_id"),instant(progress.get("decided_at")),instant(progress.get("acknowledged_at")),updates.assess(id,((Timestamp)row.get("created_at")).toInstant(),instant(progress.get("decided_at"))),CustomerReviewCompletionTarget.assess(((Timestamp)row.get("created_at")).toInstant(),instant(progress.get("decided_at")),Instant.now()));
                if(!attentionOnly||item.requiresAttention())items.add(item);
            }
            var last=candidates.isEmpty()?null:candidates.getLast();Cursor next=candidates.size()==25?new Cursor(((Timestamp)last.get("created_at")).toInstant(),(UUID)last.get("id")):null;
            audit.record(actor,attentionOnly?"CUSTOMER_REVIEW_ATTENTION_READ":"CUSTOMER_REVIEW_SUPERVISION_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");
            return new Page(items,next,candidates.size(),observed,SEMANTICS);
        });}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_REVIEW_SUPERVISION","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private Instant instant(Object value){return value==null?null:((Timestamp)value).toInstant();}
    private Member member(long id,UUID person) {
        var u=users.findById(id).orElse(null);
        boolean available=u!=null&&u.isAuthenticationAllowed()&&person.equals(u.getOperatorPersonId())
            &&(u.getRole()==UserRole.OPERATOR||u.getRole()==UserRole.APPROVER);
        return new Member(id,available);
    }
    private void validate(Cursor c,Instant now) {
        if(c!=null&&(c.receivedAt()==null||c.requestId()==null||c.receivedAt().isBefore(Instant.EPOCH)||c.receivedAt().isAfter(now)))throw denied(HttpStatus.BAD_REQUEST);
    }
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 감독 권한·최근 인증·조회 위치를 확인하세요");}
}
