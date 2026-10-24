package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** 현재 배정된 고객 후속 업무를 발견한다. 목록 조회나 회신을 최종 처리 완료로 해석하지 않는다. */
@Service
public class CustomerWorkQueueService {
    public static final String SEMANTICS="REFRESH_FROM_START_NOT_A_DURABLE_CHECKPOINT";
    public record Cursor(Instant receivedAt,UUID requestId) {}
    public record Item(UUID requestId,long userId,Instant receivedAt,long custodyGeneration,
                       long latestNoteSequence,long latestReplyNoteSequence,boolean hasReply,boolean hasUnreflectedNote) {}
    public record Page(List<Item> items,Cursor next,int scanned,Instant observedAt,String semantics) {
        public Page {items=List.copyOf(items);}
    }
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final CustomerWorkCustody custody;private final AuditService audit;private final TransactionTemplate tx;
    public CustomerWorkQueueService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,
                                    CustomerWorkCustody custody,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.custody=custody;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Page queue(long actor,UUID sid,Cursor after) {
        try {return tx.execute(t->{
            // 조회자의 권한 변경과 직렬화한다. 각 고객 전체를 잠그지 않아 업무 처리를 기다리게 하지 않는다.
            var person=users.lockById(actor).orElse(null);sessions.requireFresh(person,sid,UserRole.OPERATOR,UserRole.APPROVER);
            Instant observed=Instant.now();
            if(after!=null&&(after.receivedAt()==null||after.requestId()==null||after.receivedAt().isBefore(Instant.EPOCH)||after.receivedAt().isAfter(observed)))throw denied(HttpStatus.BAD_REQUEST);
            var rows=after==null?jdbc.queryForList("SELECT id,created_at FROM customer_exit_review_requests ORDER BY created_at,id LIMIT 25"):
                jdbc.queryForList("SELECT id,created_at FROM customer_exit_review_requests WHERE (created_at,id)>(?,?) ORDER BY created_at,id LIMIT 25",Timestamp.from(after.receivedAt()),after.requestId());
            var items=new ArrayList<Item>();
            for(var row:rows) {
                UUID id=(UUID)row.get("id");var state=custody.current(id);
                // 원래 작성자라는 이유만으로 남기지 않는다. 승인된 최신 배정의 계정과 실제 인물이 모두 맞아야 한다.
                if(!state.contains(person))continue;
                // 두 논리 순번을 같은 SQL 관측에서 읽는다. 기록 시각이나 회신 건수로 처리 진행을 추측하지 않는다.
                var progress=jdbc.queryForMap("SELECT (SELECT coalesce(max(sequence),0) FROM customer_exit_review_notes WHERE request_id=?) AS note_sequence,(SELECT coalesce(max(note_sequence),0) FROM customer_exit_review_replies WHERE request_id=?) AS reply_sequence",id,id);
                long note=((Number)progress.get("note_sequence")).longValue(),reply=((Number)progress.get("reply_sequence")).longValue();
                if(reply>note)throw new IllegalStateException("회신의 내부 기록 근거가 존재하지 않습니다");
                items.add(new Item(id,state.userId(),((Timestamp)row.get("created_at")).toInstant(),state.generation(),note,reply,reply>0,note>reply));
            }
            // 필터 뒤 빈 페이지도 마지막 검사 위치를 반환한다. 빈 items는 순회 종료의 조건이 아니다.
            var last=rows.isEmpty()?null:rows.getLast();Cursor next=rows.size()==25?new Cursor(((Timestamp)last.get("created_at")).toInstant(),(UUID)last.get("id")):null;
            audit.record(actor,"CUSTOMER_WORK_QUEUE_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");
            return new Page(items,next,rows.size(),observed,SEMANTICS);
        });}catch(ResponseStatusException e){audit.denied(actor,"CUSTOMER_WORK_QUEUE","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 업무 배정·최근 인증·조회 위치를 확인하세요");}
}
