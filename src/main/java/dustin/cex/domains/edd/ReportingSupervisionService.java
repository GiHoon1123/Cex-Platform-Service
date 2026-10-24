package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.AuditService;
import dustin.cex.domains.security.OperatorSessionPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 확정된 보고 필요를 담당자 배정과 무관하게 점검한다. 승인 역할에도 원문을 일괄 공개하지 않는다.
 * 조회 결과는 감독 시점의 참고 정보이며, 기존 인계·원문 API의 현재 권한 검사를 대신하지 않는다.
 */
@Service
public class ReportingSupervisionService {
    public static final String SEMANTICS="REFRESH_FROM_START_NOT_A_DURABLE_CHECKPOINT";
    public record Cursor(Instant decidedAt,UUID reviewId) {}
    public record Member(long id,boolean available) {}
    public record Item(UUID reviewId,long userId,Instant internalDecisionAt,Instant internalCheckTargetAt,
            boolean internalCheckOverdue,long custodyGeneration,List<Member> members,boolean orphaned,
            Instant latestObservationRecordedAt,String taskStatus,String legalDeadlineStatus) {
        public Item {members=List.copyOf(members);}
    }
    public record Page(List<Item> items,Cursor next,int scanned,Instant observedAt,String semantics) {
        public Page {items=List.copyOf(items);}
    }
    private final UserRepository users;
    private final OperatorSessionPolicy sessions;
    private final ReportingReviewService reviews;
    private final ReportingCustody custody;
    private final ReportingFollowupService followups;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public ReportingSupervisionService(UserRepository users,OperatorSessionPolicy sessions,
            ReportingReviewService reviews,ReportingCustody custody,ReportingFollowupService followups,
            AuditService audit,PlatformTransactionManager manager) {
        this.users=users;this.sessions=sessions;this.reviews=reviews;this.custody=custody;
        this.followups=followups;this.audit=audit;this.tx=new TransactionTemplate(manager);
    }

    public Page queue(long actor,UUID sid,Cursor after) {
        try {
            return tx.execute(status->{
                // 필터 검사 뒤 권한 회수가 일어날 수 있어 현재 사용자 행을 잠그고 업무 직전에 재검사한다.
                // 다른 담당자·고객 행은 잠그지 않는다. 이 조회는 금융 결정이나 인계 승인이 아니다.
                var supervisor=users.lockById(actor).orElseThrow(()->denied(HttpStatus.FORBIDDEN));
                sessions.requireFresh(supervisor,sid,UserRole.APPROVER);
                Instant observed=Instant.now();
                if(after!=null&&(after.decidedAt()==null||after.reviewId()==null
                        ||after.decidedAt().isBefore(Instant.EPOCH)||after.decidedAt().isAfter(observed))) {
                    throw denied(HttpStatus.BAD_REQUEST);
                }
                // 전체 표를 복호화해 세는 대신 승인 후보를 한 번에 25개만 읽는다.
                // 미보고 후보가 있는 페이지도 검사한 마지막 위치로 진행하며 원래 승인 순서를 유지한다.
                var candidates=reviews.custodyCandidates(after==null?null:after.decidedAt(),after==null?null:after.reviewId());
                var items=new ArrayList<Item>();
                for(UUID id:candidates) {
                    var origin=reviews.custodyCandidate(id);
                    if(origin==null)continue;
                    var state=custody.current(id);
                    var members=state.members().stream().map(member->new Member(member.id(),
                        custody.available(member,users.findById(member.id()).orElse(null)))).toList();
                    var check=ReportingFollowupService.InternalCheck.at(origin.decidedAt(),observed);
                    // 기존 메타데이터 무결성 검사를 재사용하되 원문·결과 방향·작성자·외부 참조는 응답에 넣지 않는다.
                    var latest=followups.latest(origin);
                    items.add(new Item(id,origin.payload().userId(),origin.decidedAt(),check.targetAt(),check.overdue(),
                        state.generation(),members,members.stream().noneMatch(Member::available),
                        latest==null?null:latest.recordedAt(),"FOLLOW_UP_REQUIRED",ReportingFollowupService.DEADLINE));
                }
                Cursor next=candidates.size()==25
                    ?new Cursor(reviews.custodyDecision(candidates.getLast()),candidates.getLast()):null;
                // 감사가 저장되지 않으면 목록도 반환하지 않는다. 응답 항목과 사유는 감사 상세에 복제하지 않는다.
                audit.record(actor,"REPORTING_SUPERVISION_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");
                return new Page(items,next,candidates.size(),observed,SEMANTICS);
            });
        } catch(ResponseStatusException e) {
            audit.denied(actor,"REPORTING_SUPERVISION","restricted","POLICY_DENIED_"+e.getStatusCode().value());
            throw e;
        }
    }
    private static ResponseStatusException denied(HttpStatus status) {
        return new ResponseStatusException(status,"현재 감독 권한·최근 인증·조회 위치를 확인하세요");
    }
}
