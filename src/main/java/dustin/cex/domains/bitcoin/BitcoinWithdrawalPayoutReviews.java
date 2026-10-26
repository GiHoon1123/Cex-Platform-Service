package dustin.cex.domains.bitcoin;

import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 담당자의 암호화 지급 의견과 지정된 다른 사람의 내용 결합 서명을 보존한다. 승인은 체인 전송이나 자금 차감이 아니다. */
@Service
@DependsOn("bitcoinWithdrawalSchemaInitializer")
public class BitcoinWithdrawalPayoutReviews {
    public record Person(long id,UUID person,long version){}
    public record Payload(UUID id,BitcoinWithdrawalPayoutBasis.Basis basis,Person requester,Person checker,String note,Instant expiresAt) {
        @Override public String toString(){return "WithdrawalPayoutReview[검토 원문 생략]";}
    }
    public record Request(Payload payload,String payloadHash,String status) {
        @Override public String toString(){return "WithdrawalPayoutReview[상태="+status+"]";}
    }
    public record Confirmation(Request request,boolean approve,OperatorMfaService.Options authentication){}
    private final BitcoinWithdrawalPayoutBasis basis;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final OperatorSessionPolicy sessions;
    private final OperatorMfaService mfa;
    private final CddCrypto crypto;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public BitcoinWithdrawalPayoutReviews(BitcoinWithdrawalPayoutBasis basis,JdbcTemplate jdbc,UserRepository users,
            OperatorSessionPolicy sessions,OperatorMfaService mfa,CddCrypto crypto,ObjectMapper json,AuditService audit,PlatformTransactionManager manager) {
        this.basis=basis;this.jdbc=jdbc;this.users=users;this.sessions=sessions;this.mfa=mfa;this.crypto=crypto;this.json=json;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Request propose(long actor,UUID sid,UUID transferId,long checker,UUID key,String note) {
        return work(actor,sid,"BTC_PAYOUT_REVIEW_PROPOSE",()->{
            if(transferId==null||key==null||note==null||note.strip().length()<10||note.length()>2000||note.codePoints().anyMatch(Character::isISOControl))throw denied(HttpStatus.BAD_REQUEST);
            var origin=jdbc.queryForList("SELECT * FROM bitcoin_withdrawal_transfer_intents WHERE id=?",transferId);if(origin.size()!=1)throw denied(HttpStatus.NOT_FOUND);
            var source=origin.getFirst();long owner=number(source,"user_id");var people=lock(actor,checker,owner);
            var maker=people.get(actor);sessions.requireFresh(maker,sid,UserRole.OPERATOR);var reviewer=people.get(checker);requireDifferent(maker,reviewer,owner);
            if(number(source,"requester_id")!=actor||!maker.getOperatorPersonId().equals(source.get("requester_person"))||maker.getVersion()!=number(source,"requester_version"))throw denied(HttpStatus.FORBIDDEN);
            var previous=jdbc.queryForList("SELECT id FROM bitcoin_withdrawal_payout_reviews WHERE requester_id=? AND request_key=?",UUID.class,actor,key);
            if(!previous.isEmpty()) {
                var oldMeta=meta(previous.getFirst());
                if(!transferId.equals(oldMeta.get("transfer_intent_id"))||checker!=number(oldMeta,"checker_id"))throw denied(HttpStatus.CONFLICT);
                assigned(actor,sid,oldMeta);var old=read(previous.getFirst());var p=old.payload();
                if(!p.basis().transfer().intentId().equals(transferId)||p.checker().id()!=checker||!p.note().equals(note.strip()))throw denied(HttpStatus.CONFLICT);
                audit.record(actor,"BTC_PAYOUT_REVIEW_REPLAYED","bitcoin-payout-review:"+p.id(),"SUCCESS",old.payloadHash());return old;
            }
            var current=basis.require(people.get(owner),transferId);
            var latest=jdbc.queryForList("SELECT r.id,r.expires_at,r.scope_hash,d.approved FROM bitcoin_withdrawal_payout_reviews r LEFT JOIN bitcoin_withdrawal_payout_decisions d ON d.review_id=r.id WHERE r.withdrawal_id=? ORDER BY r.position DESC LIMIT 1",source.get("withdrawal_id"));
            if(!latest.isEmpty()) {var last=latest.getFirst();if(!Boolean.FALSE.equals(last.get("approved"))&&((Timestamp)last.get("expires_at")).toInstant().isAfter(now())&&current.scopeHash().equals(last.get("scope_hash")))throw denied(HttpStatus.CONFLICT);}
            if(committed((UUID)source.get("withdrawal_id")))throw denied(HttpStatus.CONFLICT);
            UUID id=UUID.randomUUID();
            Instant expiry=now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
            var payload=new Payload(id,current,person(maker),person(reviewer),note.strip(),expiry);String plain=json.writeValueAsString(payload);
            if(plain.getBytes(StandardCharsets.UTF_8).length>16384)throw denied(HttpStatus.CONFLICT);String hash=crypto.fingerprint("btc-payout-review-v1",plain);
            jdbc.update("""
                INSERT INTO bitcoin_withdrawal_payout_reviews(id,withdrawal_id,user_id,transfer_intent_id,transfer_attempt_id,scope_hash,
                 requester_id,requester_person,requester_version,checker_id,checker_person,checker_version,request_key,
                 key_id,ciphertext,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,current.funds().withdrawalId(),owner,transferId,current.transfer().attemptId(),current.scopeHash(),actor,maker.getOperatorPersonId(),maker.getVersion(),checker,reviewer.getOperatorPersonId(),reviewer.getVersion(),key,
                crypto.keyId(),crypto.encrypt(owner,id,plain.getBytes(StandardCharsets.UTF_8)),hash,Timestamp.from(expiry));
            mfa.bindApproval(OperatorMfaService.ApprovalKind.BTC_WITHDRAWAL_PAYOUT,id,actor,maker.getVersion(),hash,expiry);
            audit.record(actor,"BTC_PAYOUT_REVIEW_PROPOSED","bitcoin-payout-review:"+id,"SUCCESS",hash);return read(id);
        });
    }
    /** 지정 인물이 현재 인증으로 읽은 정확한 내용을 기록한다. 고객에게 내부 의견/위험 사유를 반환하지 않는다. */
    public Request inspect(long actor,UUID sid,UUID id) {
        return work(actor,sid,"BTC_PAYOUT_REVIEW_INSPECT",()->{var meta=meta(id);assigned(actor,sid,meta);var request=read(id);UUID view=UUID.randomUUID();var user=users.findById(actor).orElseThrow();
            jdbc.update("INSERT INTO bitcoin_withdrawal_payout_views(id,review_id,actor_id,actor_person,actor_version,session_id,payload_hash) VALUES(?,?,?,?,?,?,?)",view,id,actor,user.getOperatorPersonId(),user.getVersion(),sid,request.payloadHash());
            audit.record(actor,"BTC_PAYOUT_REVIEW_VIEWED","bitcoin-payout-view:"+view,"SUCCESS",request.payloadHash());return request;});
    }
    public Confirmation options(long actor,UUID sid,UUID id,String hash,boolean approve) {
        return work(actor,sid,"BTC_PAYOUT_REVIEW_OPTIONS",()->{var r=check(actor,sid,id,hash);return new Confirmation(r,approve,mfa.approvalOptions(actor,sid,OperatorMfaService.ApprovalKind.BTC_WITHDRAWAL_PAYOUT,id,hash,approve));});
    }
    public Request decide(long actor,UUID sid,UUID id,String hash,boolean approve,OperatorMfaService.AuthorizationProof proof) {
        return work(actor,sid,"BTC_PAYOUT_REVIEW_DECIDE",()->{check(actor,sid,id,hash);mfa.authorizeApproval(actor,sid,OperatorMfaService.ApprovalKind.BTC_WITHDRAWAL_PAYOUT,id,hash,approve,proof);
            jdbc.update("INSERT INTO bitcoin_withdrawal_payout_decisions(review_id,approver_id,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?)",id,actor,sid,approve,hash,proof.challengeId());
            audit.record(actor,"BTC_PAYOUT_REVIEW_DECIDED","bitcoin-payout-review:"+id,"SUCCESS",hash);return read(id);});
    }
    /** 신뢰된 지급 워커가 사용할 내부 경계다. 현재 근거를 다시 검사하고 취소와 같은 고객 잠금에서 한 쪽만 확정한다. */
    public Request commit(UUID id) {
        boundary();return tx.execute(t->{var meta=meta(id);var people=lock(number(meta,"requester_id"),number(meta,"checker_id"),number(meta,"user_id"));
            var r=read(id);var p=r.payload();
            var old=jdbc.queryForList("SELECT review_id FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=?",UUID.class,p.basis().funds().withdrawalId());
            if(!old.isEmpty()){if(!id.equals(old.getFirst()))throw denied(HttpStatus.CONFLICT);return r;}
            requirePeople(p,people);
            if(!"APPROVED".equals(r.status())||!p.expiresAt().isAfter(now())||!latest(meta))throw denied(HttpStatus.CONFLICT);
            var current=basis.require(people.get(p.basis().funds().customerId()),p.basis().transfer().intentId());if(!current.scopeHash().equals(p.basis().scopeHash()))throw denied(HttpStatus.CONFLICT);
            jdbc.update("INSERT INTO bitcoin_withdrawal_payout_commitments(withdrawal_id,review_id,user_id,payload_hash) VALUES(?,?,?,?)",p.basis().funds().withdrawalId(),id,p.basis().funds().customerId(),r.payloadHash());
            audit.record(null,"BTC_PAYOUT_EXECUTION_COMMITTED","bitcoin-withdrawal:"+p.basis().funds().withdrawalId(),"SUCCESS",r.payloadHash());return r;});
    }
    private Request check(long actor,UUID sid,UUID id,String hash) {
        var meta=meta(id);var people=assigned(actor,sid,meta);var r=read(id);var p=r.payload();
        if(actor!=p.checker().id())throw denied(HttpStatus.FORBIDDEN);
        if(!"PENDING".equals(r.status())||!Objects.equals(hash,r.payloadHash())||!latest(meta)||committed(p.basis().funds().withdrawalId()))throw denied(HttpStatus.CONFLICT);
        var current=basis.require(people.get(p.basis().funds().customerId()),p.basis().transfer().intentId());if(!current.scopeHash().equals(p.basis().scopeHash()))throw denied(HttpStatus.CONFLICT);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_views WHERE review_id=? AND actor_id=? AND actor_person=? AND actor_version=? AND session_id=? AND payload_hash=? AND viewed_at>=clock_timestamp()-interval '5 minutes')",Boolean.class,id,actor,p.checker().person(),p.checker().version(),sid,hash)))throw denied(HttpStatus.CONFLICT);
        return r;
    }
    private Map<Long,User> assigned(long actor,UUID sid,Map<String,Object> meta) {
        var people=lock(actor,number(meta,"requester_id"),number(meta,"checker_id"),number(meta,"user_id"));sessions.requireFresh(people.get(actor),sid,UserRole.OPERATOR,UserRole.APPROVER);
        // 암호 원문을 열기 전에 현재 지정 인물의 열람 목적부터 검사한다.
        if(actor!=number(meta,"requester_id")&&actor!=number(meta,"checker_id"))throw denied(HttpStatus.FORBIDDEN);
        for(String prefix:List.of("requester","checker")) {
            var user=people.get(number(meta,prefix+"_id"));
            if(user==null||!user.isAuthenticationAllowed()||!Objects.equals(user.getOperatorPersonId(),meta.get(prefix+"_person"))||user.getVersion()!=number(meta,prefix+"_version"))throw denied(HttpStatus.FORBIDDEN);
        }
        var r=read((UUID)meta.get("id"));requirePeople(r.payload(),people);if(actor!=r.payload().requester().id()&&actor!=r.payload().checker().id())throw denied(HttpStatus.FORBIDDEN);return people;
    }
    private void requirePeople(Payload p,Map<Long,User> people) {
        var maker=people.get(p.requester().id());var checker=people.get(p.checker().id());requireDifferent(maker,checker,p.basis().funds().customerId());
        if(!person(maker).equals(p.requester())||!person(checker).equals(p.checker()))throw denied(HttpStatus.FORBIDDEN);
    }
    private void requireDifferent(User maker,User checker,long customer) {
        if(maker==null||checker==null||!maker.isAuthenticationAllowed()||!checker.isAuthenticationAllowed()||maker.getRole()!=UserRole.OPERATOR||checker.getRole()!=UserRole.APPROVER
            ||maker.getOperatorPersonId()==null||checker.getOperatorPersonId()==null||maker.getId().equals(checker.getId())||maker.getOperatorPersonId().equals(checker.getOperatorPersonId())
            ||maker.getId()==customer||checker.getId()==customer)throw denied(HttpStatus.FORBIDDEN);
    }
    private Request read(UUID id) {
        var row=meta(id);try {
            if(!crypto.keyId().equals(row.get("key_id")))throw denied(HttpStatus.SERVICE_UNAVAILABLE);
            String raw=new String(crypto.decrypt(number(row,"user_id"),id,(byte[])row.get("ciphertext")),StandardCharsets.UTF_8);
            if(!crypto.fingerprint("btc-payout-review-v1",raw).equals(row.get("payload_hash")))throw denied(HttpStatus.SERVICE_UNAVAILABLE);
            var p=json.readValue(raw,Payload.class);
            if(!p.id().equals(id)||!p.basis().funds().withdrawalId().equals(row.get("withdrawal_id"))||p.basis().funds().customerId()!=number(row,"user_id")
                ||!p.basis().scopeHash().equals(row.get("scope_hash"))||!p.basis().transfer().intentId().equals(row.get("transfer_intent_id"))||!p.basis().transfer().attemptId().equals(row.get("transfer_attempt_id"))
                ||p.requester().id()!=number(row,"requester_id")||p.checker().id()!=number(row,"checker_id")||!p.requester().person().equals(row.get("requester_person"))||!p.checker().person().equals(row.get("checker_person"))
                ||p.requester().version()!=number(row,"requester_version")||p.checker().version()!=number(row,"checker_version")||!p.expiresAt().equals(((Timestamp)row.get("expires_at")).toInstant()))throw denied(HttpStatus.SERVICE_UNAVAILABLE);
            return new Request(p,(String)row.get("payload_hash"),row.get("approved")==null?(p.expiresAt().isAfter(now())?"PENDING":"EXPIRED"):Boolean.TRUE.equals(row.get("approved"))?"APPROVED":"REJECTED");
        }catch(RuntimeException invalid){throw denied(HttpStatus.SERVICE_UNAVAILABLE);}
    }
    private Map<String,Object> meta(UUID id){var rows=jdbc.queryForList("SELECT r.*,d.approved FROM bitcoin_withdrawal_payout_reviews r LEFT JOIN bitcoin_withdrawal_payout_decisions d ON d.review_id=r.id WHERE r.id=?",id);if(rows.size()!=1)throw denied(HttpStatus.NOT_FOUND);return rows.getFirst();}
    private boolean latest(Map<String,Object> row){return !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_reviews WHERE withdrawal_id=? AND position>?)",Boolean.class,row.get("withdrawal_id"),row.get("position")));}
    private boolean committed(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM bitcoin_withdrawal_payout_commitments WHERE withdrawal_id=?)",Boolean.class,id));}
    private Map<Long,User> lock(long...ids){var people=new HashMap<Long,User>();for(long id:Arrays.stream(ids).distinct().sorted().toArray())users.lockById(id).ifPresent(u->people.put(id,u));return people;}
    private Instant now(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private static Person person(User u){return new Person(u.getId(),u.getOperatorPersonId(),u.getVersion());}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private <T>T work(long actor,UUID sid,String action,Supplier<T> callback) {
        boundary();try{return tx.execute(t->callback.get());}catch(ResponseStatusException rejected){
            if(rejected.getStatusCode().value()==404)tx.executeWithoutResult(t->sessions.requireFresh(users.lockById(actor).orElse(null),sid,UserRole.OPERATOR,UserRole.APPROVER));
            audit.denied(actor,action,"bitcoin-payout-review-restricted","POLICY_"+rejected.getStatusCode().value());throw rejected;}
    }
    private static void boundary(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("출금 지급 검토는 독립 트랜잭션으로 실행하세요");}
    private static ResponseStatusException denied(HttpStatus status){return new ResponseStatusException(status,"현재 지급 근거·지정 인물·열람·내용 결합 서명을 확인하세요");}
}
