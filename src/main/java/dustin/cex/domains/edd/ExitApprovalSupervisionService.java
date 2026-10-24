package dustin.cex.domains.edd;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.kyc.DiditWebhookVerifier;
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
import tools.jackson.databind.ObjectMapper;

/** 미결 종료/철회 요청을 원문 없이 점검한다. 만료는 업무 이력을 숨기는 조건으로 사용하지 않는다. */
@Service
public class ExitApprovalSupervisionService {
    public enum Kind { EXIT, RESCISSION }
    public record Cursor(Instant createdAt,Kind kind,UUID requestId) {}
    public record Item(Kind kind,UUID requestId,long userId,Instant createdAt,Instant expiresAt,boolean expired,
            long requesterId,boolean requesterMatchesRequest,long checkerId,boolean checkerMatchesRequest,String decisionEligibility) {}
    public record Page(List<Item> items,Cursor next,Instant observedAt,String semantics) {
        public Page {items=List.copyOf(items);}
    }
    // 두 업무는 식별자 공간을 공유한다고 가정하지 않는다. 종류까지 정렬 키에 포함한다.
    private static final String CANDIDATES="""
        SELECT * FROM (
         SELECT 'EXIT' AS kind,r.id,r.user_id,r.requester_id,r.checker_id,r.created_at,r.expires_at,r.payload,r.payload_hash
          FROM relationship_exit_requests r WHERE NOT EXISTS(SELECT 1 FROM relationship_exit_decisions d WHERE d.review_id=r.id)
         UNION ALL
         SELECT 'RESCISSION' AS kind,r.id,r.user_id,r.requester_id,r.checker_id,r.created_at,r.expires_at,r.payload,r.payload_hash
          FROM relationship_exit_rescissions r WHERE NOT EXISTS(SELECT 1 FROM relationship_exit_rescission_decisions d WHERE d.request_id=r.id)
        ) candidates
        """;
    private final JdbcTemplate jdbc;private final UserRepository users;private final OperatorSessionPolicy sessions;
    private final DiditWebhookVerifier verifier;private final ObjectMapper json;private final AuditService audit;private final TransactionTemplate tx;
    public ExitApprovalSupervisionService(JdbcTemplate jdbc,UserRepository users,OperatorSessionPolicy sessions,
            DiditWebhookVerifier verifier,ObjectMapper json,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.verifier=verifier;this.json=json;this.audit=audit;tx=new TransactionTemplate(manager);
    }
    public Page queue(long actor,UUID sid,Cursor after) {
        try {return tx.execute(t->{
            sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.APPROVER);
            Instant observed=Instant.now();
            if(after!=null&&(after.createdAt()==null||after.kind()==null||after.requestId()==null||after.createdAt().isBefore(Instant.EPOCH)||after.createdAt().isAfter(observed)))throw denied(HttpStatus.BAD_REQUEST);
            var candidates=after==null?jdbc.queryForList(CANDIDATES+" ORDER BY created_at,kind,id LIMIT 25"):
                jdbc.queryForList(CANDIDATES+" WHERE (created_at,kind,id)>(?,?,?) ORDER BY created_at,kind,id LIMIT 25",Timestamp.from(after.createdAt()),after.kind().name(),after.requestId());
            var items=new ArrayList<Item>();
            for(var row:candidates) {
                var kind=Kind.valueOf((String)row.get("kind"));String raw=(String)row.get("payload");
                if(!verifier.digest(json.readTree(raw)).equals(row.get("payload_hash")))throw new IllegalStateException("미결 종료 요청의 무결성 검증 실패");
                UUID id;long user,maker,checker,makerVersion,checkerVersion;UUID makerPerson,checkerPerson;Instant expires;String policy,assurance;
                // 실제 업무의 타입으로 읽는다. 사유 암호문·서명·근거 묶음은 해독하거나 반환하지 않는다.
                if(kind==Kind.EXIT) {
                    var p=json.readValue(raw,RelationshipExitService.Payload.class);id=p.id();user=p.userId();maker=p.requesterId();checker=p.checkerId();makerPerson=p.requesterPerson();checkerPerson=p.checkerPerson();makerVersion=p.requesterVersion();checkerVersion=p.checkerVersion();expires=p.expiresAt();policy=p.policy();assurance=p.assurance();
                    if(!RelationshipExitService.POLICY.equals(policy)||!RelationshipExitService.ASSURANCE.equals(assurance))throw new IllegalStateException("종료 요청 정책 검증 실패");
                } else {
                    var p=json.readValue(raw,ExitRescissionService.Payload.class);id=p.id();user=p.userId();maker=p.requesterId();checker=p.checkerId();makerPerson=p.requesterPerson();checkerPerson=p.checkerPerson();makerVersion=p.requesterVersion();checkerVersion=p.checkerVersion();expires=p.expiresAt();policy=p.policy();assurance=p.assurance();
                    if(!ExitRescissionService.POLICY.equals(policy)||!ExitRescissionService.ASSURANCE.equals(assurance))throw new IllegalStateException("철회 요청 정책 검증 실패");
                }
                if(!id.equals(row.get("id"))||user!=((Number)row.get("user_id")).longValue()||maker!=((Number)row.get("requester_id")).longValue()||checker!=((Number)row.get("checker_id")).longValue()||!expires.equals(((Timestamp)row.get("expires_at")).toInstant()))throw new IllegalStateException("미결 요청의 대상 결합 검증 실패");
                items.add(new Item(kind,id,user,((Timestamp)row.get("created_at")).toInstant(),expires,!expires.isAfter(observed),maker,
                    matches(maker,makerPerson,makerVersion,UserRole.OPERATOR),checker,matches(checker,checkerPerson,checkerVersion,UserRole.APPROVER),"RECHECK_REQUIRED"));
            }
            var last=candidates.isEmpty()?null:candidates.getLast();Cursor next=candidates.size()==25?new Cursor(((Timestamp)last.get("created_at")).toInstant(),Kind.valueOf((String)last.get("kind")),(UUID)last.get("id")):null;
            audit.record(actor,"EXIT_APPROVAL_SUPERVISION_READ","operator:"+actor,"SUCCESS","METADATA_ONLY");
            return new Page(items,next,observed,ExitSupervisionService.SEMANTICS);
        });}catch(ResponseStatusException e){audit.denied(actor,"EXIT_APPROVAL_SUPERVISION","restricted","POLICY_DENIED_"+e.getStatusCode().value());throw e;}
    }
    private boolean matches(long id,UUID person,long version,UserRole role) {
        var u=users.findById(id).orElse(null);return u!=null&&u.isAuthenticationAllowed()&&u.getRole()==role&&person.equals(u.getOperatorPersonId())&&version==u.getVersion();
    }
    private static ResponseStatusException denied(HttpStatus s){return new ResponseStatusException(s,"현재 감독 권한·최근 인증·조회 위치를 확인하세요");}
}
