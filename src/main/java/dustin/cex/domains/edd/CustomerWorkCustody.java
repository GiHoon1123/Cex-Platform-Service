package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** 고객 접수의 최초 두 인물과 승인 인계의 현재 세대를 읽는다. 금융/보고 원래 판단과 별개다. */
@Component
public class CustomerWorkCustody {
    public record Member(long id,UUID person) {}
    public record State(UUID reviewId,long userId,long generation,String stamp,List<Member> members) {
        public State {members=List.copyOf(members);}
        public boolean contains(User person) {return person!=null&&members.contains(new Member(person.getId(),person.getOperatorPersonId()));}
    }
    public record Origin(long userId,String hash,List<Member> members) {public Origin{members=List.copyOf(members);}}
    private final RelationshipExitService exits;
    private final JdbcTemplate jdbc;private final UserRepository users;private final ObjectMapper json;private final DiditWebhookVerifier verifier;
    public CustomerWorkCustody(JdbcTemplate jdbc,UserRepository users,ObjectMapper json,DiditWebhookVerifier verifier,RelationshipExitService exits) {this.exits=exits;this.jdbc=jdbc;this.users=users;this.json=json;this.verifier=verifier;}
    public State current(UUID review) {
        transaction();var origin=origin(review);
        var latest=jdbc.queryForList("SELECT r.id,r.payload,r.payload_hash,r.generation,r.primary_id,r.checker_id FROM customer_work_custody_requests r JOIN customer_work_custody_decisions d ON d.request_id=r.id AND d.approved WHERE r.review_id=? ORDER BY r.generation DESC LIMIT 1",review);
        // 인계가 없으면 실제 원래 배정이 현재 상태다. 이미 사용하지 않는 프로그램을 되살리는 우회가 아니다.
        if(latest.isEmpty())return new State(review,origin.userId(),0,origin.hash(),origin.members());
        var row=latest.getFirst();String payload=(String)row.get("payload");var handoff=json.readValue(payload,CustomerWorkCustodyService.Payload.class);
        if(!verifier.digest(json.readTree(payload)).equals(row.get("payload_hash"))||!CustomerWorkCustodyService.POLICY.equals(handoff.policy())||!review.equals(handoff.reviewId())
            ||!handoff.id().equals(row.get("id"))||handoff.primary().id()!=((Number)row.get("primary_id")).longValue()||handoff.checker().id()!=((Number)row.get("checker_id")).longValue()||handoff.userId()!=origin.userId()||!handoff.reviewHash().equals(origin.hash())||handoff.generation()!=((Number)row.get("generation")).longValue())throw new IllegalStateException("인계 근거 검증 실패");
        return new State(review,origin.userId(),handoff.generation(),(String)row.get("payload_hash"),List.of(new Member(handoff.primary().id(),handoff.primary().person()),new Member(handoff.checker().id(),handoff.checker().person())));
    }
    public Origin origin(UUID review){
        transaction();var rows=jdbc.queryForList("SELECT user_id,exit_id FROM customer_exit_review_requests WHERE id=?",review);
        if(rows.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        var source=rows.getFirst();long owner=((Number)source.get("user_id")).longValue();var original=exits.approvedOrigin((UUID)source.get("exit_id"));var p=original.payload();
        if(p.userId()!=owner)throw new IllegalStateException("고객 접수 기초 배정 검증 실패");
        return new Origin(owner,verifier.digest(json.valueToTree(List.of(review,owner,p.id(),original.payloadHash()))),List.of(new Member(p.requesterId(),p.requesterPerson()),new Member(p.checkerId(),p.checkerPerson())));
    }
    /** 모든 인물·고객을 같은 순서로 잠근 뒤 현재 세대를 재확인한다. 서로 다른 인계·권한 변경과의 경합을 직렬화한다. */
    public Map<Long,User> lock(State expected,long... extra) {
        transaction();
        // 배정 변경을 기다린 뒤 새 확정 상태를 읽어야 한다. 고정 스냅샷 격리는 권한 판정에 사용하지 않는다.
        if(!"read committed".equals(jdbc.queryForObject("SELECT current_setting('transaction_isolation')",String.class)))throw new IllegalStateException("현재 배정 검사는 READ COMMITTED 격리 수준이 필요합니다");
        var ids=new TreeSet<Long>();ids.add(expected.userId());expected.members().forEach(m->ids.add(m.id()));for(long id:extra)ids.add(id);
        var result=new HashMap<Long,User>();ids.forEach(id->users.lockById(id).ifPresent(user->result.put(id,user)));
        if(!expected.stamp().equals(current(expected.reviewId()).stamp()))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"현재 인계 상태가 바뀌었습니다");return result;
    }
    public boolean available(Member member,User user) {return user!=null&&member.id()==user.getId()&&member.person().equals(user.getOperatorPersonId())&&user.isAuthenticationAllowed()&&(user.getRole()==UserRole.OPERATOR||user.getRole()==UserRole.APPROVER);}
    public boolean orphaned(State state,Map<Long,User> locked) {return state.members().stream().noneMatch(m->available(m,locked.get(m.id())));}
    private void transaction() {if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("업무 배정에는 트랜잭션이 필요합니다");}
}
