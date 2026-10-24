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

/**
 * 승인 역할이 현재 종료 개시를 원문 없이 점검한다. 감독 목록은 인계나 철회 권한을 부여하지 않는다.
 * 과거 승인 후보를 제한해 읽고 활성 효과만 표시한다. 철회된 후보로 빈 페이지가 생겨도 위치는 진행한다.
 */
@Service
public class ExitSupervisionService {
    public static final String SEMANTICS="REFRESH_FROM_START_NOT_A_DURABLE_CHECKPOINT";
    public record Cursor(Instant decidedAt,UUID reviewId) {}
    public record Member(long id,boolean historyAvailable,boolean rescissionRequesterAvailable) {}
    public record Item(UUID exitId,long userId,Instant decidedAt,List<Member> members,boolean requesterUnavailable) {
        public Item {members=List.copyOf(members);}
    }
    public record Page(List<Item> items,Cursor next,int scanned,Instant observedAt,String semantics) {
        public Page {items=List.copyOf(items);}
    }
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final RelationshipExitService exits;private final AuditService audit;private final TransactionTemplate tx;
    public ExitSupervisionService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,
            RelationshipExitService exits,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.exits=exits;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    public Page queue(long actor,UUID sid,Cursor after) {
        try {return tx.execute(t->{
            // 현재 감독자만 잠근다. 여러 고객을 잠가 주문 접수를 지연시키지 않는다.
            var supervisor=users.lockById(actor).orElse(null);sessions.requireFresh(supervisor,sid,UserRole.APPROVER);
            Instant observed=Instant.now();validate(after,observed);
            var candidates=after==null?jdbc.queryForList("SELECT review_id,user_id,decided_at FROM relationship_exit_decisions WHERE approved ORDER BY decided_at,review_id LIMIT 25"):
                jdbc.queryForList("SELECT review_id,user_id,decided_at FROM relationship_exit_decisions WHERE approved AND (decided_at,review_id)>(?,?) ORDER BY decided_at,review_id LIMIT 25",Timestamp.from(after.decidedAt()),after.reviewId());
            var items=new ArrayList<Item>();
            for(var row:candidates) {
                UUID id=(UUID)row.get("review_id");var original=exits.approvedOrigin(id);var p=original.payload();
                if(p.userId()!=((Number)row.get("user_id")).longValue())throw new IllegalStateException("종료 승인 대상 검증 실패");
                if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM relationship_active_exits WHERE review_id=?)",Boolean.class,id)))continue;
                var members=List.of(member(p.requesterId(),p.requesterPerson()),member(p.checkerId(),p.checkerPerson()));
                items.add(new Item(id,p.userId(),((Timestamp)row.get("decided_at")).toInstant(),members,members.stream().noneMatch(Member::rescissionRequesterAvailable)));
            }
            // 시각이 같아도 UUID를 함께 비교해 빠뜨리지 않는다. 새 변경은 처음부터 재조회해야 한다.
            var last=candidates.isEmpty()?null:candidates.getLast();
            Cursor next=candidates.size()==25?new Cursor(((Timestamp)last.get("decided_at")).toInstant(),(UUID)last.get("review_id")):null;
            audit.record(actor,"EXIT_SUPERVISION_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");
            return new Page(items,next,candidates.size(),observed,SEMANTICS);
        });}catch(ResponseStatusException e){audit.denied(actor,"EXIT_SUPERVISION","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private Member member(long id,UUID person) {
        var u=users.findById(id).orElse(null);
        // 최근 MFA 만료는 인물의 업무 자격 상실과 다르다. 실제 열람/철회에서는 세션을 다시 검사한다.
        boolean available=u!=null&&u.isAuthenticationAllowed()&&person.equals(u.getOperatorPersonId())
            &&(u.getRole()==UserRole.OPERATOR||u.getRole()==UserRole.APPROVER);
        return new Member(id,available,available&&u.getRole()==UserRole.OPERATOR);
    }
    private void validate(Cursor c,Instant now) {
        if(c!=null&&(c.decidedAt()==null||c.reviewId()==null||c.decidedAt().isBefore(Instant.EPOCH)||c.decidedAt().isAfter(now)))throw denied(HttpStatus.BAD_REQUEST);
    }
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 감독 권한·최근 인증·조회 위치를 확인하세요");}
}
