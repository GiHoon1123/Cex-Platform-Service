package dustin.cex.domains.kyc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import dustin.cex.domains.auth.model.entity.*;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.security.AuditService;

/**
 * 공급사 확인 사실과 거래소 계정 정책을 분리한다. 개인정보 원본을 복사하지 않고 최소 상태·근거만 보관한다.
 * 외부 HTTP는 열린 DB 트랜잭션 밖에서 호출한다. 상태 변경은 항상 사용자 행을 먼저 잠근다.
 */
@Service
public class KycService {
    private final DiditSettings settings;private final DiditClient client;private final DiditWebhookVerifier verifier;
    private final JdbcTemplate jdbc;private final UserRepository users;private final RefreshTokenRepository sessions;
    private final AuditService audit;private final TransactionTemplate tx;private final dustin.cex.domains.cdd.CddService cdd;
    public KycService(DiditSettings settings,DiditClient client,DiditWebhookVerifier verifier,JdbcTemplate jdbc,
            UserRepository users,RefreshTokenRepository sessions,AuditService audit,PlatformTransactionManager manager,dustin.cex.domains.cdd.CddService cdd) {
        this.settings=settings;this.client=client;this.verifier=verifier;this.jdbc=jdbc;this.users=users;this.sessions=sessions;
        this.audit=audit;this.cdd=cdd;tx=new TransactionTemplate(manager);
    }
    public record Status(UUID attemptId,String creationState,String identityStatus,String providerStatus,Instant validUntil,String environment,
            Instant lastConfirmedAt,String reconciliationOutcome) {}
    public record Session(Status status,String verificationUrl) {
        @Override public String toString() {return "Session[확인 링크 생략]";}
    }
    private record Attempt(UUID id,long user,UUID session,byte[] link,String creation,UUID app,String environment,UUID workflow,int version) {}
    private record Start(Attempt attempt,boolean created) {}
    // 호출자가 열어 둔 트랜잭션에 합류하면 HTTP 전에 커밋한다는 계약이 깨지므로 진입부터 거부한다.
    @Transactional(propagation=Propagation.NEVER)
    public Session start(long actor,UUID sid,UUID requestId) {
        configured();if(requestId==null) throw rejected(HttpStatus.BAD_REQUEST);
        var start=tx.execute(status->{
            customer(actor,sid);
            var mappings=jdbc.queryForList("SELECT id FROM kyc_attempts WHERE user_id=? AND request_id=?",actor,requestId);
            var same=mappings.isEmpty()?null:attempt((UUID)mappings.getFirst().get("id"));
            UUID active=active(actor);
            if(same!=null) {
                if(!same.id().equals(active) || !cdd.boundToCurrent(actor,same.id())) throw rejected(HttpStatus.CONFLICT);
                return new Start(same,false);
            }
            if(active!=null) {
                var profile=profile(actor);
                String current=(String)profile.get("identity_status");
                Timestamp until=(Timestamp)profile.get("valid_until");
                var old=Objects.requireNonNull(attempt(active));
                boolean sameConfiguration=old.app().equals(settings.applicationId) && old.environment().equals(settings.environment)
                    && old.workflow().equals(settings.workflowId) && old.version()==settings.workflowVersion;
                boolean fresh=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kyc_reconciliations WHERE attempt_id=? AND fresh_at>clock_timestamp()-interval '15 minutes')",Boolean.class,active));
                boolean tracked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM kyc_reconciliations WHERE attempt_id=?)",Boolean.class,active));
                if(sameConfiguration && cdd.boundToCurrent(actor,active) && ((current.equals("PENDING") && tracked) || (current.equals("VERIFIED") && until!=null && until.toInstant().isAfter(Instant.now()) && fresh)))
                    // 새 키에 옛 시도의 성공 응답을 주면 나중 재시도 때 키의 의미가 달라진다.
                    // 진행 중에는 원래 키를 재사용하거나 조회 API로 이어가도록 명시적으로 충돌시킨다.
                    throw rejected(HttpStatus.CONFLICT);
            }
            if(jdbc.queryForObject("SELECT count(*) FROM kyc_attempts WHERE user_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,actor)>=3)
                throw rejected(HttpStatus.TOO_MANY_REQUESTS);
            // 외부 연계 식별자는 서버가 생성한다. 고객의 중복 요청 키와 다른 고객의 시도는 섞이지 않는다.
            UUID attemptId=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO kyc_attempts(id,user_id,request_id,application_id,environment,workflow_id,workflow_version,creation_state)
                VALUES(?,?,?,?,?,?,?,'CREATING')
                """,attemptId,actor,requestId,settings.applicationId,settings.environment,settings.workflowId,settings.workflowVersion);
            jdbc.update("""
                INSERT INTO kyc_profiles(user_id,active_attempt,identity_status) VALUES(?,?,'PENDING')
                ON CONFLICT(user_id) DO UPDATE SET active_attempt=EXCLUDED.active_attempt,identity_status='PENDING',provider_status=NULL,
                 valid_until=NULL,event_time=-1,status_digest=NULL,conflict_at=NULL
                """,actor,attemptId);
            jdbc.update("INSERT INTO kyc_reconciliations(attempt_id) VALUES(?)",attemptId);
            cdd.bind(actor,attemptId);
            audit.record(actor,"KYC_SESSION_REQUESTED","kyc:"+attemptId,"SUCCESS","ATTEMPT_COMMITTED");
            return new Start(Objects.requireNonNull(attempt(attemptId)),true);
        });
        if(start.created()) {
            try {complete(actor,sid,start.attempt().id(),client.create(start.attempt().id()),false);}
            catch(ResponseStatusException e) {
                // 결과 불명 POST를 재전송하지 않는다. 웹훅이 이미 확정한 상태는 되돌리지 않는다.
                tx.executeWithoutResult(status->{customer(actor,sid);
                    jdbc.update("UPDATE kyc_attempts SET creation_state='UNKNOWN' WHERE id=? AND creation_state='CREATING' AND session_id IS NULL",start.attempt().id());
                    audit.record(actor,"KYC_SESSION_UNKNOWN","kyc:"+start.attempt().id(),"SUCCESS","RECONCILIATION_REQUIRED");});
                throw e;
            }
        }
        return ownSession(actor,sid);
    }
    @Transactional(propagation=Propagation.NEVER)
    public Session reconcile(long actor,UUID sid,UUID id) {
        configured();
        tx.executeWithoutResult(status->{customer(actor,sid);var a=attempt(id);
            if(a==null || a.user()!=actor || !id.equals(active(actor))) throw rejected(HttpStatus.NOT_FOUND);});
        // 읽기 전용 검색만 실행한다. 후보가 없거나 여러 개면 결과 불명 상태를 유지한다.
        complete(actor,sid,id,client.find(id),true);return ownSession(actor,sid);
    }
    private void complete(long actor,UUID sid,UUID id,JsonNode result,boolean reconciled) {
        var binding=validateSessionResult(id,result,reconciled);
        UUID session=binding.session();String url=binding.url();
        tx.executeWithoutResult(status->{
            customer(actor,sid);bindSession(actor,id,session,url,reconciled);
        });
    }
    private record Binding(UUID session,String url) {}
    private Binding validateSessionResult(UUID id,JsonNode result,boolean reconciled) {
        UUID session=uuid(result,"session_id");
        String url=text(result,reconciled?"session_url":"url");validateLink(url);
        if(!id.toString().equals(text(result,"vendor_data")) || (result.has("session_kind") && !result.path("session_kind").asText().equals("user"))) throw rejected(HttpStatus.BAD_GATEWAY);
        if(!reconciled && (!settings.workflowId.equals(uuid(result,"workflow_id")) || (!result.path("workflow_version").isIntegralNumber() || !result.path("workflow_version").canConvertToInt() || result.path("workflow_version").intValue()!=settings.workflowVersion))) throw rejected(HttpStatus.BAD_GATEWAY);
        return new Binding(session,url);
    }
    // 내부 워커는 고객 세션을 꾸며 내지 않는다. 공급사 조회와 현재 시도의 결합만 복원한다.
    @Transactional(propagation=Propagation.NEVER)
    UUID recoverMissingSession(long actor,UUID id,JsonNode result) {
        var binding=validateSessionResult(id,result,true);
        tx.executeWithoutResult(status->{
            users.lockById(actor).orElseThrow(()->rejected(HttpStatus.NOT_FOUND));
            bindSession(actor,id,binding.session(),binding.url(),true);
        });
        return binding.session();
    }
    private void bindSession(long actor,UUID id,UUID session,String url,boolean reconciled) {
            var a=attempt(id);
            if(a==null || a.user()!=actor || !id.equals(active(actor)) || (a.session()!=null && !a.session().equals(session))) throw rejected(HttpStatus.CONFLICT);
            jdbc.update("UPDATE kyc_attempts SET session_id=?,link_cipher=?,creation_state='READY' WHERE id=?",session,encrypt(id,url),id);
            // 웹훅이 HTTP 응답보다 먼저 도착해도 판정·유효기한을 덮어쓰지 않는다.
            audit.record(actor,reconciled?"KYC_SESSION_RECONCILED":"KYC_SESSION_CREATED","kyc:"+id,"SUCCESS","SESSION_BOUND");
    }
    public Session ownSession(long actor,UUID sid) {
        configured();
        return tx.execute(status->{customer(actor,sid);var id=active(actor);
            if(id==null) return new Session(new Status(null,"NOT_STARTED","PENDING",null,null,null,null,null),null);
            var a=Objects.requireNonNull(attempt(id));return new Session(status(actor),a.link()==null?null:decrypt(id,a.link()));});
    }
    public Status status(long actor) {
        var rows=jdbc.queryForList("""
            SELECT p.*,a.creation_state,a.environment,r.fresh_at,r.outcome FROM kyc_profiles p JOIN kyc_attempts a ON a.id=p.active_attempt
             LEFT JOIN kyc_reconciliations r ON r.attempt_id=a.id WHERE p.user_id=?
            """,actor);
        if(rows.isEmpty()) return new Status(null,"NOT_STARTED","PENDING",null,null,null,null,null);
        var p=rows.getFirst();var until=(Timestamp)p.get("valid_until");
        return new Status((UUID)p.get("active_attempt"),(String)p.get("creation_state"),(String)p.get("identity_status"),
            (String)p.get("provider_status"),until==null?null:until.toInstant(),(String)p.get("environment"),
            p.get("fresh_at")==null?null:((Timestamp)p.get("fresh_at")).toInstant(),(String)p.get("outcome"));
    }
    public void webhook(byte[] raw,String signature,String timestamp) {
        JsonNode body;
        try {body=verifier.verify(raw,signature,timestamp);}
        catch(ResponseStatusException e) {
            if(e.getStatusCode().value()==401) audit.denied(null,"KYC_WEBHOOK","didit","SIGNATURE_DENIED");
            throw e;
        }
        acceptEvent(body,false);
    }
    // 호출자는 사용자 잠금·대사 임대·로컬 변경 번호를 검사한 트랜잭션 안에 있어야 한다.
    @Transactional(propagation=Propagation.MANDATORY)
    void recoverEvent(JsonNode body) {acceptEvent(body,true);}
    private void acceptEvent(JsonNode body,boolean recovered) {
        UUID eventId=uuid(body,"event_id"),attemptId=uuid(body,"vendor_data"),sessionId=uuid(body,"session_id");
        var a=attempt(attemptId);if(a==null) throw rejected(HttpStatus.NOT_FOUND);
        String type=text(body,"webhook_type"),providerStatus=text(body,"status");
        if(!Set.of("status.updated","data.updated").contains(type) || providerStatus.length()>40
                || !a.app().equals(uuid(body,"application_id")) || !a.environment().equals(text(body,"environment"))
                || !a.workflow().equals(uuid(body,"workflow_id")) || !body.path("workflow_version").isIntegralNumber()
                || !body.path("workflow_version").canConvertToInt() || a.version()!=body.path("workflow_version").intValue() || (body.has("session_kind") && !body.path("session_kind").asText().equals("user"))
                || (a.environment().equals("live") && !body.path("sandbox_scenario").isNull() && body.has("sandbox_scenario"))) throw rejected(HttpStatus.BAD_REQUEST);
        if(!body.path("created_at").isIntegralNumber() || !body.path("created_at").canConvertToLong()) throw rejected(HttpStatus.BAD_REQUEST);
        long time=body.path("created_at").longValue();if(time<0 || time>body.path("timestamp").longValue()) throw rejected(HttpStatus.BAD_REQUEST);
        var stable=body.deepCopy();((tools.jackson.databind.node.ObjectNode)stable).remove("timestamp");String hash=verifier.digest(stable);
        String state=identity(body,sessionId);String statusDigest=verifier.digest(verifier.json.valueToTree(List.of(providerStatus,body.has("decision")?body.path("decision"):verifier.json.nullNode())));
        tx.executeWithoutResult(transaction->{
            // 주문 접수와 같은 사용자 행에서 순서를 정한다. 외부 이벤트는 운영자의 거래 제한을 해제하지 않는다.
            users.lockById(a.user()).orElseThrow(()->rejected(HttpStatus.NOT_FOUND));
            var current=Objects.requireNonNull(attempt(attemptId));
            if(current.session()!=null && !current.session().equals(sessionId)) throw rejected(HttpStatus.CONFLICT);
            var previous=jdbc.queryForList("SELECT * FROM kyc_events WHERE event_id=?",eventId);
            if(!previous.isEmpty()) {
                if(!hash.equals(previous.getFirst().get("payload_hash")) || !attemptId.equals(previous.getFirst().get("attempt_id"))) {
                    if(attemptId.equals(active(a.user()))) {
                        jdbc.update("UPDATE kyc_profiles SET identity_status='CONFLICT',valid_until=NULL WHERE user_id=?",a.user());
                        touchReconciliation(attemptId,false,true);
                    }
                    audit.record(a.user(),"KYC_EVENT_CONFLICT","kyc:"+eventId,"DENIED","EVENT_ID_CONTENT_CHANGED");
                }
                return;
            }
            jdbc.update("UPDATE kyc_attempts SET session_id=?,creation_state='READY' WHERE id=?",sessionId,attemptId);
            String disposition="SUPERSEDED";
            if(attemptId.equals(active(a.user()))) {
                var profile=profile(a.user());long last=((Number)profile.get("event_time")).longValue();
                if(time<last) disposition="STALE";
                else if(time==last && !statusDigest.equals(profile.get("status_digest"))) {
                    jdbc.update("UPDATE kyc_profiles SET identity_status='CONFLICT',valid_until=NULL,conflict_at=? WHERE user_id=?",time,a.user());disposition="TIME_CONFLICT";
                } else if(profile.get("identity_status").equals("CONFLICT")) disposition="REVIEW_REQUIRED";
                else {
                    Instant until=state.equals("VERIFIED")?Instant.ofEpochSecond(time).plusSeconds(30*86400):null;
                    // 공급사가 더 짧은 만료를 정했다면 로컬 30일 정책으로 그것을 늘리지 않는다.
                    if(until!=null && body.path("decision").hasNonNull("expires_at")) {
                        var providerExpiry=Instant.parse(body.path("decision").path("expires_at").asText());
                        if(providerExpiry.isBefore(until)) until=providerExpiry;
                    }
                    jdbc.update("UPDATE kyc_profiles SET identity_status=?,provider_status=?,valid_until=?,event_time=?,status_digest=? WHERE user_id=?",
                        state,providerStatus,until==null?null:Timestamp.from(until),time,statusDigest,a.user());disposition="APPLIED";
                }
            }
            jdbc.update("INSERT INTO kyc_events(event_id,attempt_id,payload_hash,provider_time,provider_status,disposition) VALUES(?,?,?,?,?,?)",eventId,attemptId,hash,time,providerStatus,disposition);
            // 서명 검증·최신 이벤트 적용·이력 저장이 모두 끝난 뒤 같은 트랜잭션에서 고객 위험을 평가한다.
            if(disposition.equals("APPLIED") && (state.equals("VERIFIED") || reviewableAml(body,sessionId)))
                cdd.assess(a.user(),attemptId,eventId,statusDigest,body.path("decision"));
            if(attemptId.equals(active(a.user()))) touchReconciliation(attemptId,disposition.equals("APPLIED") && !state.equals("CONFLICT"),
                disposition.equals("TIME_CONFLICT") || disposition.equals("REVIEW_REQUIRED") || (disposition.equals("APPLIED") && state.equals("CONFLICT")));
            audit.record(a.user(),recovered?"KYC_EVENT_RECOVERED":"KYC_EVENT_RECEIVED","kyc:"+eventId,"SUCCESS",disposition);
        });
    }
    /**
     * AML 후보 때문에 전체 상태가 검토/거절이어도 조사 근거는 필요하다.
     * 신원 통과로 바꾸지 않고, 같은 세션·상태에 결합된 실제 위험 자료만 CDD 검토로 넘긴다.
     */
    private boolean reviewableAml(JsonNode body,UUID session) {
        if(!Set.of("In Review","Declined").contains(body.path("status").asText()))return false;
        var d=body.path("decision");if(!session.toString().equals(d.path("session_id").asText())||!body.path("status").equals(d.path("status")))return false;
        var checks=d.path("aml_screenings");if(!checks.isArray())return false;
        for(var check:checks)if(Set.of("In Review","Declined").contains(check.path("status").asText())
                || (check.path("total_hits").isNumber()&&check.path("total_hits").decimalValue().signum()>0)
                || (check.path("hits").isArray()&&!check.path("hits").isEmpty())
                || (check.path("warnings").isArray()&&!check.path("warnings").isEmpty()))return true;
        return false;
    }
    private void touchReconciliation(UUID id,boolean fresh,boolean clear) {
        // 중복 이벤트에서는 이 메서드에 도달하지 않는다. 재전송만으로 확인 기한을 연장하지 않는다.
        jdbc.update("""
            INSERT INTO kyc_reconciliations(attempt_id,revision,fresh_at) VALUES(?,1,CASE WHEN ? THEN clock_timestamp() ELSE NULL END)
            ON CONFLICT(attempt_id) DO UPDATE SET revision=kyc_reconciliations.revision+1,
             fresh_at=CASE WHEN ? THEN clock_timestamp() WHEN ? THEN NULL ELSE kyc_reconciliations.fresh_at END
            """,id,fresh,fresh,clear);
    }
    private String identity(JsonNode body,UUID session) {
        String status=text(body,"status");
        if(status.equals("Approved")) {
            var decision=body.path("decision");
            if(decision.hasNonNull("expires_at")) {
                try {if(!decision.path("expires_at").isString()) return "CONFLICT";Instant.parse(decision.path("expires_at").asText());}
                catch(java.time.format.DateTimeParseException e) {return "CONFLICT";}
            }
            if(!session.toString().equals(decision.path("session_id").asText()) || !status.equals(decision.path("status").asText())) return "CONFLICT";
            for(String field:List.of("id_verifications","liveness_checks","face_matches")) {
                var list=decision.path(field);if(!list.isArray() || list.isEmpty()) return "CONFLICT";
                for(var check:list) {
                    if(!check.path("status").asText().equals("Approved")) return "CONFLICT";
                    if(field.equals("id_verifications") && (!check.path("verification_method").asText().equals("document")
                            || !check.path("assurance").asText().equals("documentary"))) return "CONFLICT";
                }
            }
            return "VERIFIED";
        }
        if(Set.of("Not Started","In Progress","In Review","Awaiting User","Resubmitted").contains(status)) return "PENDING";
        if(Set.of("Declined","Expired","Abandoned","Kyc Expired").contains(status)) return "NOT_VERIFIED";
        return "CONFLICT";
    }
    private User customer(long actor,UUID sid) {
        var user=users.lockById(actor).orElseThrow(()->rejected(HttpStatus.UNAUTHORIZED));
        var session=sessions.findBySessionId(sid).orElseThrow(()->rejected(HttpStatus.UNAUTHORIZED));
        if(!user.isEnabled() || user.getRole()!=UserRole.CUSTOMER || session.getUserId()!=actor || session.getRevoked()
                || !session.getExpiresAt().isAfter(LocalDateTime.now())) throw rejected(HttpStatus.FORBIDDEN);
        return user;
    }
    private UUID active(long actor) {var p=profile(actor);return p.isEmpty()?null:(UUID)p.get("active_attempt");}
    private Map<String,Object> profile(long actor) {var rows=jdbc.queryForList("SELECT * FROM kyc_profiles WHERE user_id=?",actor);return rows.isEmpty()?Map.of():rows.getFirst();}
    private Attempt attempt(UUID id) {
        var rows=jdbc.query("SELECT * FROM kyc_attempts WHERE id=?",(row,n)->new Attempt(id,row.getLong("user_id"),row.getObject("session_id",UUID.class),row.getBytes("link_cipher"),row.getString("creation_state"),
            row.getObject("application_id",UUID.class),row.getString("environment"),row.getObject("workflow_id",UUID.class),row.getInt("workflow_version")),id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private byte[] encrypt(UUID id,String value) {
        byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,id,nonce,value.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array();
    }
    private String decrypt(UUID id,byte[] value) {
        if(value.length<28) throw new IllegalStateException("KYC 링크 암호문 손상");
        return new String(crypt(Cipher.DECRYPT_MODE,id,Arrays.copyOf(value,12),Arrays.copyOfRange(value,12,value.length)),StandardCharsets.UTF_8);
    }
    private byte[] crypt(int mode,UUID id,byte[] nonce,byte[] value) {
        try {var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(mode,new SecretKeySpec(settings.encryptionKey,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD(id.toString().getBytes(StandardCharsets.UTF_8));return cipher.doFinal(value);
        } catch(GeneralSecurityException e) {throw new IllegalStateException("KYC 링크 암호화 실패",e);}
    }
    private void validateLink(String raw) {
        try {var url=java.net.URI.create(raw);
            if(raw.length()>2048 || !"https".equals(url.getScheme()) || !"verify.didit.me".equals(url.getHost()) || url.getUserInfo()!=null
                    || url.getFragment()!=null || url.getQuery()!=null || (url.getPort()!=-1 && url.getPort()!=443) || !url.getPath().contains("/session/")) throw rejected(HttpStatus.BAD_GATEWAY);
        } catch(IllegalArgumentException e) {throw rejected(HttpStatus.BAD_GATEWAY);}
    }
    private UUID uuid(JsonNode n,String field) {try{return UUID.fromString(text(n,field));}catch(IllegalArgumentException e){throw rejected(HttpStatus.BAD_REQUEST);}}
    private String text(JsonNode n,String field) {if(!n.path(field).isString() || n.path(field).asText().isBlank()) throw rejected(HttpStatus.BAD_REQUEST);return n.path(field).asText();}
    private void configured() {if(!settings.configured()) throw rejected(HttpStatus.SERVICE_UNAVAILABLE);}
    private ResponseStatusException rejected(HttpStatus status) {return new ResponseStatusException(status,"고객 확인의 인증·상태·연결 정보를 확인하세요");}
}
