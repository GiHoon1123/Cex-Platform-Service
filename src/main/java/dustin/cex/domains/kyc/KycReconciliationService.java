package dustin.cex.domains.kyc;

import dustin.cex.domains.auth.repository.UserRepository;
import dustin.cex.domains.security.AuditService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * 웹훅이 주 경로이고 이 서비스는 누락·재시작 이후의 복원을 담당한다.
 * 공급사 결정의 생성 시각으로 변경 이벤트를 만들어 내지 않는다. 보관된 원래 서명 이벤트를 재사용한다.
 * 사용자 잠금은 짧은 확정 트랜잭션에서만 잡고, 세 번까지 필요한 외부 조회는 모두 그 밖에서 실행한다.
 */
@Service
public class KycReconciliationService {
    private final DiditSettings settings;
    private final DiditClient client;
    private final DiditWebhookVerifier verifier;
    private final KycService kyc;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final AuditService audit;
    private final TransactionTemplate tx;

    private record Work(UUID attempt, long user, UUID session, long revision, UUID lease) {}
    private record Evidence(JsonNode decision, List<JsonNode> events, String hash) {}

    public KycReconciliationService(DiditSettings settings, DiditClient client,
            DiditWebhookVerifier verifier, KycService kyc, JdbcTemplate jdbc,
            UserRepository users, AuditService audit, PlatformTransactionManager manager) {
        this.settings = settings;
        this.client = client;
        this.verifier = verifier;
        this.kyc = kyc;
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.tx = new TransactionTemplate(manager);
    }

    /** 한 번에 한 시도만 처리한다. 여러 프로세스는 DB 임대로 같은 시도를 동시에 확정하지 않는다. */
    @Transactional(propagation = Propagation.NEVER)
    public boolean reconcileDue() {
        if (!settings.configured()) return false;
        Work work = tx.execute(status -> claim());
        if (work == null) return false;
        try {
            UUID session = work.session();
            if (session == null) {
                // 생성 응답도 누락된 경우 정확 검색으로 연결만 되찾는다. POST를 재실행하지 않는다.
                session = kyc.recoverMissingSession(work.user(), work.attempt(), client.find(work.attempt()));
            }
            Evidence evidence = readEvidence(work, session);
            finish(work, evidence);
        } catch (RuntimeException failure) {
            // 예외 원문에는 공급사 응답·개인정보가 들어갈 수 있다. 고정된 실패 코드만 저장한다.
            fail(work,failure);
        }
        return true;
    }

    private Work claim() {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM kyc_provider_cooldowns WHERE application_id=? AND not_before>clock_timestamp())
                """, Boolean.class, settings.applicationId))) return null;
        // 생성·웹훅 트랜잭션이 남긴 작업만 읽는다. 과거 시도에 새 근거를 채우거나 전체 고객을 매초 스캔하지 않는다.
        var rows = jdbc.queryForList("""
            SELECT r.attempt_id,r.revision,a.user_id,a.session_id FROM kyc_reconciliations r
             JOIN kyc_attempts a ON a.id=r.attempt_id JOIN kyc_profiles p ON p.active_attempt=a.id
             JOIN users u ON u.id=a.user_id
             WHERE r.next_attempt_at<=clock_timestamp() AND (r.lease_until IS NULL OR r.lease_until<=clock_timestamp())
              AND a.application_id=? AND a.environment=? AND a.workflow_id=? AND a.workflow_version=?
              AND u.enabled=true AND u.role='CUSTOMER' AND p.identity_status<>'CONFLICT'
             ORDER BY r.next_attempt_at,r.attempt_id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
            """, settings.applicationId, settings.environment, settings.workflowId, settings.workflowVersion);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        UUID id = (UUID) row.get("attempt_id");
        UUID lease = UUID.randomUUID();
        jdbc.update("UPDATE kyc_reconciliations SET lease_id=?,lease_until=clock_timestamp()+interval '2 minutes' WHERE attempt_id=?", lease, id);
        return new Work(id, ((Number) row.get("user_id")).longValue(), (UUID) row.get("session_id"),
                ((Number) row.get("revision")).longValue(), lease);
    }

    private Evidence readEvidence(Work work, UUID session) {
        var deliveries = client.deliveries(session);
        if (!deliveries.isArray() || deliveries.size() > 100) throw unavailable();
        var events = new ArrayList<JsonNode>();
        for (var delivery : deliveries) {
            if (!session.toString().equals(delivery.path("session_id").asText())) throw unavailable();
            String type = delivery.path("webhook_type").asText();
            if (!Set.of("status.updated", "data.updated").contains(type)) continue;
            if (!delivery.path("request_body").isString() || !delivery.path("request_header").isString()) throw unavailable();
            var headers = verifier.json.readTree(delivery.path("request_header").asText());
            byte[] raw = delivery.path("request_body").asText().getBytes(StandardCharsets.UTF_8);
            var body = verifier.verifyArchived(raw, header(headers, "X-Signature-V2"), header(headers, "X-Timestamp"));
            if (!body.path("vendor_data").asText().equals(work.attempt().toString())
                    || !body.path("session_id").asText().equals(session.toString())
                    || !body.path("webhook_type").asText().equals(type)
                    || !body.path("created_at").isIntegralNumber() || !body.path("created_at").canConvertToLong()) throw unavailable();
            events.add(body);
        }
        // 전송 이력의 생성 시각이 아니라 원래 상태 변경 시각으로 재생한다.
        events.sort(Comparator.comparingLong(event -> event.path("created_at").longValue()));
        var decision = client.decision(session);
        if (!decision.isObject() || !decision.path("session_kind").asText().equals("user")
                || !decision.path("session_id").asText().equals(session.toString())
                || !decision.path("vendor_data").asText().equals(work.attempt().toString())
                || !decision.path("workflow_id").asText().equals(settings.workflowId.toString())
                || !decision.path("environment").asText().equals(settings.environment)) throw unavailable();
        return new Evidence(decision, events, verifier.digest(projection(decision)));
    }

    private String header(JsonNode headers, String expected) {
        String value = null;
        for (var name : headers.propertyNames()) {
            if (!name.equalsIgnoreCase(expected)) continue;
            if (value != null || !headers.path(name).isString()) throw unavailable();
            value = headers.path(name).asText();
        }
        if (value == null) throw unavailable();
        return value;
    }

    /** 개인정보·미디어 주소 변화와 거래 허용 근거를 구분해 필요한 검사 결과만 비교한다. */
    private JsonNode projection(JsonNode decision) {
        var result = verifier.json.createObjectNode();
        result.put("status", decision.path("status").asText());
        if (decision.path("status").asText().equals("Approved")) {
            if (decision.hasNonNull("expires_at")) result.put("expires_at",Instant.parse(decision.path("expires_at").asText()).toString());
            else result.putNull("expires_at");
            for (String field : List.of("id_verifications", "liveness_checks", "face_matches")) {
                var values = result.putArray(field);
                var checks = decision.path(field);
                if (!checks.isArray() || checks.isEmpty()) throw unavailable();
                for (var check : checks) {
                    var item = values.addObject();
                    item.put("status", check.path("status").asText());
                    if (field.equals("id_verifications")) {
                        item.put("verification_method", check.path("verification_method").asText());
                        item.put("assurance", check.path("assurance").asText());
                    }
                }
            }
        }
        return result;
    }

    private void finish(Work work, Evidence evidence) {
        tx.executeWithoutResult(status -> {
            // 웹훅·주문과 같은 사용자부터 잠근다. 작업 행부터 잠그면 반대 순서로 교착할 수 있다.
            users.lockById(work.user()).orElseThrow(this::unavailable);
            if (!ownsLease(work)) return;
            if (!unchanged(work)) {
                release(work, "STALE_READ", null, 0, false, 60);
                return;
            }
            var events = evidence.events();
            long knownTime=jdbc.queryForObject("SELECT event_time FROM kyc_profiles WHERE user_id=?",Long.class,work.user());
            boolean older= !events.isEmpty() && events.getLast().path("created_at").longValue()<knownTime;
            if (events.isEmpty() || older || (!matchesLatest(evidence) && !conflictingLatest(evidence))) {
                // 현재 결정과 원래 변경 근거가 다르면 확인 권한만 닫고 다음 대사를 기다린다.
                // 이 상태는 충돌 판정과 달라서 올바른 근거가 나중 도착하면 정상 복원할 수 있다.
                jdbc.update("UPDATE kyc_reconciliations SET fresh_at=NULL WHERE attempt_id=?", work.attempt());
                release(work, "EVIDENCE_MISMATCH", evidence.hash(), events.size(), false, 60);
                return;
            }
            for (var event : events) kyc.recoverEvent(event);
            boolean conflict = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT identity_status='CONFLICT' FROM kyc_profiles WHERE user_id=?", Boolean.class, work.user()));
            if (!conflict) jdbc.update("UPDATE kyc_reconciliations SET fresh_at=clock_timestamp() WHERE attempt_id=?", work.attempt());
            release(work, conflict ? "CONFLICT" : "CONFIRMED", evidence.hash(), events.size(), true, 300);
        });
    }

    private boolean matchesLatest(Evidence evidence) {
        var events = evidence.events();
        long latest = events.getLast().path("created_at").longValue();
        for (var event : events) {
            if (event.path("created_at").longValue() != latest) continue;
            var source = event.path("status").asText().equals("Approved") ? event.path("decision") : event;
            if (!verifier.digest(projection(source)).equals(evidence.hash())) return false;
        }
        return true;
    }

    /** 같은 초의 상반된 서명 근거는 기존 이벤트 경로에서 충돌로 확정해 재확인할 수 있게 한다. */
    private boolean conflictingLatest(Evidence evidence) {
        long latest=evidence.events().getLast().path("created_at").longValue();
        String first=null;
        for(var event:evidence.events()) {
            if(event.path("created_at").longValue()!=latest) continue;
            var source=event.path("status").asText().equals("Approved")?event.path("decision"):event;
            String hash=verifier.digest(projection(source));
            if(first!=null && !first.equals(hash)) return true;
            first=hash;
        }
        return false;
    }

    private boolean ownsLease(Work work) {
        return !jdbc.queryForList("""
            SELECT attempt_id FROM kyc_reconciliations WHERE attempt_id=? AND lease_id=?
             AND lease_until>clock_timestamp() FOR UPDATE
            """, work.attempt(), work.lease()).isEmpty();
    }

    private boolean unchanged(Work work) {
        // 조회 중 도착한 웹훅이나 새 시도를 오래된 읽기로 덮어쓰지 않는다.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM kyc_profiles p JOIN kyc_reconciliations r ON r.attempt_id=p.active_attempt
             JOIN users u ON u.id=p.user_id
             WHERE p.user_id=? AND p.active_attempt=? AND r.revision=? AND u.enabled=true AND u.role='CUSTOMER')
            """, Boolean.class, work.user(), work.attempt(), work.revision()));
    }

    private void fail(Work work,RuntimeException failure) {
        tx.executeWithoutResult(status -> {
            users.lockById(work.user()).orElseThrow(this::unavailable);
            if (!ownsLease(work)) return;
            int failures = jdbc.queryForObject("SELECT failures FROM kyc_reconciliations WHERE attempt_id=?", Integer.class, work.attempt());
            // 네트워크 실패로 확인 기한을 연장하지 않는다. 주문 정책이 마지막 확인 이후 15분을 검사한다.
            int delay = Math.min(60 << Math.min(failures, 4),900);
            if (failure instanceof DiditClient.RateLimited limited) {
                delay=Math.max(delay,limited.seconds);
                jdbc.update("""
                    INSERT INTO kyc_provider_cooldowns(application_id,not_before)
                     VALUES(?,clock_timestamp()+(? * interval '1 second'))
                    ON CONFLICT(application_id) DO UPDATE SET not_before=GREATEST(kyc_provider_cooldowns.not_before,EXCLUDED.not_before)
                    """,settings.applicationId,delay);
            }
            release(work, failure instanceof DiditClient.RateLimited ? "RATE_LIMITED" : "FAILED", null, 0, false, delay);
        });
    }

    private void release(Work work, String outcome, String hash, int count, boolean success, int delay) {
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO kyc_reconciliation_runs(id,attempt_id,outcome,decision_hash,delivery_count) VALUES(?,?,?,?,?)",
                run, work.attempt(), outcome, hash, count);
        jdbc.update("""
            UPDATE kyc_reconciliations SET lease_id=NULL,lease_until=NULL,outcome=?,
             failures=CASE WHEN ? THEN 0 ELSE LEAST(failures+1,30) END,
             next_attempt_at=clock_timestamp()+(? * interval '1 second') WHERE attempt_id=? AND lease_id=?
            """, outcome, success, delay, work.attempt(), work.lease());
        audit.record(work.user(), "KYC_RECONCILIATION", "kyc:"+run, success ? "SUCCESS" : "DENIED", outcome);
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "고객 확인 대사 근거를 확정할 수 없습니다");
    }
}
