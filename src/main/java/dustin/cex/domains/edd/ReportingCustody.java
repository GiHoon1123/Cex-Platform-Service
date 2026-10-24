package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** 최초 배정과 승인된 인계의 현재 상태를 읽는다. 원래 보고 판단의 작성자·서명·기산 시각은 바꾸지 않는다. */
@Component
public class ReportingCustody {
    public record Member(long id,UUID person) {}
    public record State(UUID reviewId,long userId,long generation,String stamp,List<Member> members) {
        public State {members=List.copyOf(members);}
        public boolean contains(User person) {return person!=null&&members.contains(new Member(person.getId(),person.getOperatorPersonId()));}
    }
    private final JdbcTemplate jdbc;private final UserRepository users;private final ObjectMapper json;private final DiditWebhookVerifier verifier;
    public ReportingCustody(JdbcTemplate jdbc,UserRepository users,ObjectMapper json,DiditWebhookVerifier verifier) {this.jdbc=jdbc;this.users=users;this.json=json;this.verifier=verifier;}
    public State current(UUID review) {
        transaction();var source=jdbc.queryForMap("SELECT payload,payload_hash FROM compliance_reviews WHERE id=?",review);String raw=(String)source.get("payload");
        if(!verifier.digest(json.readTree(raw)).equals(source.get("payload_hash")))throw new IllegalStateException("원래 보고 근거 검증 실패");var p=json.readValue(raw,ReportingReviewService.Payload.class);
        if(!p.id().equals(review))throw new IllegalStateException("원래 보고 근거 검증 실패");
        var latest=jdbc.queryForList("SELECT r.id,r.payload,r.payload_hash,r.generation,r.primary_id,r.checker_id FROM reporting_custody_requests r JOIN reporting_custody_decisions d ON d.request_id=r.id AND d.approved WHERE r.review_id=? ORDER BY r.generation DESC LIMIT 1",review);
        // 인계가 없으면 실제 원래 배정이 현재 상태다. 이미 사용하지 않는 프로그램을 되살리는 우회가 아니다.
        if(latest.isEmpty())return new State(review,p.userId(),0,(String)source.get("payload_hash"),List.of(new Member(p.requesterId(),p.requesterPerson()),new Member(p.checkerId(),p.checkerPerson())));
        var row=latest.getFirst();String payload=(String)row.get("payload");var handoff=json.readValue(payload,ReportingCustodyService.Payload.class);
        if(!verifier.digest(json.readTree(payload)).equals(row.get("payload_hash"))||!ReportingCustodyService.POLICY.equals(handoff.policy())||!review.equals(handoff.reviewId())
            ||!handoff.id().equals(row.get("id"))||handoff.primary().id()!=((Number)row.get("primary_id")).longValue()||handoff.checker().id()!=((Number)row.get("checker_id")).longValue()||handoff.userId()!=p.userId()||!handoff.reviewHash().equals(source.get("payload_hash"))||handoff.generation()!=((Number)row.get("generation")).longValue())throw new IllegalStateException("인계 근거 검증 실패");
        return new State(review,p.userId(),handoff.generation(),(String)row.get("payload_hash"),List.of(new Member(handoff.primary().id(),handoff.primary().person()),new Member(handoff.checker().id(),handoff.checker().person())));
    }
    /** 모든 인물·고객을 같은 순서로 잠근 뒤 현재 세대를 재확인한다. 서로 다른 인계·권한 변경과의 경합을 직렬화한다. */
    public Map<Long,User> lock(State expected,long... extra) {
        transaction();var ids=new TreeSet<Long>();ids.add(expected.userId());expected.members().forEach(m->ids.add(m.id()));for(long id:extra)ids.add(id);
        var result=new HashMap<Long,User>();ids.forEach(id->users.lockById(id).ifPresent(user->result.put(id,user)));
        if(!expected.stamp().equals(current(expected.reviewId()).stamp()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"현재 인계 상태가 바뀌었습니다");return result;
    }
    public boolean available(Member member,User user) {return user!=null&&member.id()==user.getId()&&member.person().equals(user.getOperatorPersonId())&&user.isAuthenticationAllowed()&&(user.getRole()==UserRole.OPERATOR||user.getRole()==UserRole.APPROVER);}
    public boolean orphaned(State state,Map<Long,User> locked) {return state.members().stream().noneMatch(m->available(m,locked.get(m.id())));}
    public boolean orphaned(State state) {return state.members().stream().noneMatch(m->available(m,users.findById(m.id()).orElse(null)));}
    private void transaction() {if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("업무 배정에는 트랜잭션이 필요합니다");}
}
