package dustin.cex.domains.ethereum;

import dustin.cex.domains.auth.model.entity.User;
import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.AuditService;
import dustin.cex.domains.security.OperatorMfaService;
import dustin.cex.domains.security.OperatorSessionPolicy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 예약된 ETH 출금의 경제적 원본을 담당자와 다른 승인자의 내용 결합 서명으로 확정한다. */
@Service
public class EthereumPayoutReviews {
    public record Person(long id, UUID personId, long version) {}
    public record Payload(UUID id, UUID withdrawalId, long customerId, String network, String address,
                          String amount, String fee, String total, Person requester, Person checker,
                          String reason, Instant expiresAt) {}
    public record Review(Payload payload, String payloadHash, String state) {}
    public record Confirmation(Review review, boolean approve, OperatorMfaService.Options authentication) {}

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final OperatorSessionPolicy sessions;
    private final OperatorMfaService mfa;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public EthereumPayoutReviews(JdbcTemplate jdbc, UserRepository users, OperatorSessionPolicy sessions,
            OperatorMfaService mfa, AuditService audit, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.users = users;
        this.sessions = sessions;
        this.mfa = mfa;
        this.audit = audit;
        this.json = json;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
    }

    public Review propose(long actor, UUID session, UUID withdrawal, long checker, UUID key, String reason) {
        boundary();
        return tx.execute(status -> {
            if (reason == null || reason.strip().length() < 10 || reason.length() > 1000) throw denied(HttpStatus.BAD_REQUEST);
            var people = lock(actor, checker);
            User maker = people.get(actor), reviewer = people.get(checker);
            sessions.requireFresh(maker, session, UserRole.OPERATOR);
            different(maker, reviewer);
            var source = jdbc.queryForList("SELECT w.* FROM ethereum_withdrawals w JOIN ethereum_withdrawal_reservation_events e ON e.withdrawal_id=w.id AND e.event_type='withdrawal_reserved' WHERE w.id=?", withdrawal);
            if (source.size() != 1) throw denied(HttpStatus.CONFLICT);
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ethereum_payout_attempts WHERE withdrawal_id=?)", Boolean.class, withdrawal))) throw denied(HttpStatus.CONFLICT);
            var old = jdbc.queryForList("SELECT id FROM ethereum_payout_reviews WHERE requester_id=? AND request_key=?", UUID.class, actor, key);
            if (!old.isEmpty()) {
                Review previous = read(old.getFirst());
                if (!withdrawal.equals(previous.payload().withdrawalId()) || checker != previous.payload().checker().id()
                        || !reason.strip().equals(previous.payload().reason())) throw denied(HttpStatus.CONFLICT);
                return previous;
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ethereum_payout_reviews r LEFT JOIN ethereum_payout_decisions d ON d.review_id=r.id WHERE r.withdrawal_id=? AND r.expires_at>clock_timestamp() AND (d.review_id IS NULL OR d.approved))", Boolean.class, withdrawal))) throw denied(HttpStatus.CONFLICT);
            var row = source.getFirst();
            UUID id = UUID.randomUUID();
            Instant expires = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant().plusSeconds(600);
            var payload = new Payload(id, withdrawal, ((Number) row.get("user_id")).longValue(), (String) row.get("network"),
                    (String) row.get("address"), row.get("amount").toString(), row.get("fee").toString(), row.get("total").toString(),
                    person(maker), person(reviewer), reason.strip(), expires);
            String raw = json.writeValueAsString(payload), hash = EthereumWithdrawalQuotes.digest(raw);
            jdbc.update("INSERT INTO ethereum_payout_reviews(id,withdrawal_id,requester_id,requester_person,requester_version,checker_id,checker_person,checker_version,request_key,payload,payload_hash,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, withdrawal, actor, maker.getOperatorPersonId(), maker.getVersion(), checker, reviewer.getOperatorPersonId(), reviewer.getVersion(), key, raw, hash, Timestamp.from(expires));
            mfa.bindApproval(OperatorMfaService.ApprovalKind.ETH_WITHDRAWAL_PAYOUT, id, actor, maker.getVersion(), hash, expires);
            audit.record(actor, "ETH_PAYOUT_REVIEW_PROPOSED", "ethereum-payout-review:" + id, "SUCCESS", hash);
            return read(id);
        });
    }

    public Review inspect(long actor, UUID session, UUID id) {
        boundary();
        return tx.execute(status -> {
            var row = meta(id);
            var people = lock(number(row, "requester_id"), number(row, "checker_id"));
            User current = people.get(actor);
            sessions.requireFresh(current, session, UserRole.OPERATOR, UserRole.APPROVER);
            if (actor != number(row, "requester_id") && actor != number(row, "checker_id")) throw denied(HttpStatus.FORBIDDEN);
            Review review = read(id);
            UUID view = UUID.randomUUID();
            jdbc.update("INSERT INTO ethereum_payout_review_views(id,review_id,actor_id,actor_person,actor_version,session_id,payload_hash) VALUES(?,?,?,?,?,?,?)",
                    view, id, actor, current.getOperatorPersonId(), current.getVersion(), session, review.payloadHash());
            audit.record(actor, "ETH_PAYOUT_REVIEW_VIEWED", "ethereum-payout-view:" + view, "SUCCESS", review.payloadHash());
            return review;
        });
    }

    public Confirmation options(long actor, UUID session, UUID id, String hash, boolean approve) {
        boundary();
        return tx.execute(status -> {
            Review review = check(actor, session, id, hash);
            return new Confirmation(review, approve, mfa.approvalOptions(actor, session,
                    OperatorMfaService.ApprovalKind.ETH_WITHDRAWAL_PAYOUT, id, hash, approve));
        });
    }

    public Review decide(long actor, UUID session, UUID id, String hash, boolean approve,
            OperatorMfaService.AuthorizationProof proof) {
        boundary();
        return tx.execute(status -> {
            check(actor, session, id, hash);
            mfa.authorizeApproval(actor, session, OperatorMfaService.ApprovalKind.ETH_WITHDRAWAL_PAYOUT, id, hash, approve, proof);
            jdbc.update("INSERT INTO ethereum_payout_decisions(review_id,approver_id,session_id,approved,payload_hash,authorization_id) VALUES(?,?,?,?,?,?)",
                    id, actor, session, approve, hash, proof.challengeId());
            audit.record(actor, "ETH_PAYOUT_REVIEW_DECIDED", "ethereum-payout-review:" + id, "SUCCESS", hash);
            return read(id);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Review approved(UUID id) {
        Review review = read(id);
        var row = meta(id);
        var people = lock(number(row, "requester_id"), number(row, "checker_id"));
        if (!"APPROVED".equals(review.state()) || !review.payload().expiresAt().isAfter(now())) throw denied(HttpStatus.CONFLICT);
        different(people.get(review.payload().requester().id()), people.get(review.payload().checker().id()));
        return review;
    }

    private Review check(long actor, UUID session, UUID id, String hash) {
        var row = meta(id);
        var people = lock(number(row, "requester_id"), number(row, "checker_id"));
        User reviewer = people.get(actor);
        sessions.requireFresh(reviewer, session, UserRole.APPROVER);
        Review review = read(id);
        if (actor != review.payload().checker().id() || !hash.equals(review.payloadHash()) || !"PENDING".equals(review.state())) throw denied(HttpStatus.CONFLICT);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ethereum_payout_review_views WHERE review_id=? AND actor_id=? AND session_id=? AND payload_hash=? AND viewed_at>=clock_timestamp()-interval '5 minutes')", Boolean.class, id, actor, session, hash))) throw denied(HttpStatus.CONFLICT);
        return review;
    }

    private Review read(UUID id) {
        var row = meta(id);
        String raw = (String) row.get("payload"), hash = (String) row.get("payload_hash");
        if (!EthereumWithdrawalQuotes.digest(raw).equals(hash)) throw new IllegalStateException("ETH 지급 검토 지문이 다릅니다");
        Payload payload = json.readValue(raw, Payload.class);
        String state = row.get("approved") == null ? payload.expiresAt().isAfter(now()) ? "PENDING" : "EXPIRED" : Boolean.TRUE.equals(row.get("approved")) ? "APPROVED" : "REJECTED";
        return new Review(payload, hash, state);
    }

    private Map<String,Object> meta(UUID id) {
        var rows = jdbc.queryForList("SELECT r.*,d.approved FROM ethereum_payout_reviews r LEFT JOIN ethereum_payout_decisions d ON d.review_id=r.id WHERE r.id=?", id);
        if (rows.size() != 1) throw denied(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }

    private Map<Long,User> lock(long... ids) {
        var result = new HashMap<Long,User>();
        for (long id : Arrays.stream(ids).distinct().sorted().toArray()) users.lockById(id).ifPresent(user -> result.put(id, user));
        return result;
    }

    private static void different(User maker, User checker) {
        if (maker == null || checker == null || maker.getRole() != UserRole.OPERATOR || checker.getRole() != UserRole.APPROVER
                || maker.getOperatorPersonId() == null || checker.getOperatorPersonId() == null
                || maker.getId().equals(checker.getId()) || maker.getOperatorPersonId().equals(checker.getOperatorPersonId())) throw denied(HttpStatus.FORBIDDEN);
    }
    private static Person person(User user) { return new Person(user.getId(), user.getOperatorPersonId(), user.getVersion()); }
    private static long number(Map<String,Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }
    private static void boundary() { if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("ETH 지급 검토는 독립 트랜잭션을 사용하세요"); }
    private static ResponseStatusException denied(HttpStatus status) { return new ResponseStatusException(status, "현재 ETH 지급 검토와 지정 승인자를 확인하세요"); }
}
