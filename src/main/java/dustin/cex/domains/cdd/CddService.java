package dustin.cex.domains.cdd;

import dustin.cex.domains.auth.model.entity.UserRole;
import dustin.cex.domains.auth.repository.*;
import dustin.cex.domains.security.AuditService;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/**
 * 고객 신고와 서명 검증을 끝낸 공급사 사실을 대조한다. 외부 호출·수동 승인·임의 점수 가중치는 없다.
 * 모든 갱신은 사용자 행 잠금 안에서 한다. 현재 신고와 신원 확인 시도가 달라지면 이전 평가는 사용할 수 없다.
 */
@Service
public class CddService {
    public static final String POLICY="CDD_DOMESTIC_INDIVIDUAL_V1";
    private final dustin.cex.domains.kyc.DiditWebhookVerifier verifier;
    private final CddCrypto crypto;private final JdbcTemplate jdbc;private final ObjectMapper json;
    private final UserRepository users;private final RefreshTokenRepository sessions;private final AuditService audit;
    public CddService(CddCrypto crypto,JdbcTemplate jdbc,ObjectMapper json,UserRepository users,
            RefreshTokenRepository sessions,AuditService audit,dustin.cex.domains.kyc.DiditWebhookVerifier verifier) {
        this.verifier=verifier;this.crypto=crypto;this.jdbc=jdbc;this.json=json;this.users=users;this.sessions=sessions;this.audit=audit;
    }
    public record Status(UUID declarationId,UUID assessmentId,String outcome,List<String> reasons,Instant assessedAt) {}
    @Transactional
    public Status submit(long user,UUID session,UUID request,CustomerDeclaration declaration) {
        crypto.requireConfigured();customer(user,session);
        if(request==null || declaration==null) throw CustomerDeclaration.invalid();
        declaration=declaration.validated();byte[] plain=json.writeValueAsBytes(declaration);
        String mac=crypto.fingerprint("declaration",new String(plain,StandardCharsets.UTF_8));
        var old=jdbc.queryForList("SELECT id,content_mac,key_id FROM cdd_declarations WHERE user_id=? AND request_id=?",user,request);
        if(!old.isEmpty()) {
            var row=old.getFirst();var current=status(user);
            if(!row.get("id").equals(current.declarationId()) || !mac.equals(row.get("content_mac")) || !crypto.keyId().equals(row.get("key_id"))) throw conflict();
            return current;
        }
        if(jdbc.queryForObject("SELECT count(*) FROM cdd_declarations WHERE user_id=? AND created_at>clock_timestamp()-interval '1 day'",Long.class,user)>=3)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"고객 신고 변경 횟수를 초과했습니다");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO cdd_declarations(id,user_id,request_id,ciphertext,content_mac,key_id) VALUES(?,?,?,?,?,?)",
            id,user,request,crypto.encrypt(user,id,plain),mac,crypto.keyId());
        jdbc.update("""
            INSERT INTO cdd_profiles(user_id,declaration_id) VALUES(?,?) ON CONFLICT(user_id)
             DO UPDATE SET declaration_id=EXCLUDED.declaration_id,assessment_id=NULL
            """,user,id);
        audit.record(user,"CDD_DECLARED","cdd:"+id,"SUCCESS","NEW_IDENTITY_VERIFICATION_REQUIRED");return status(user);
    }
    @Transactional
    public Status own(long user,UUID session) {customer(user,session);return status(user);}
    /** 운영자는 신고 원문을 조회하지 않는다. 실제 검토·증거 열람 권한은 별도 업무로 구현해야 한다. */
    public Status status(long user) {
        var rows=jdbc.queryForList("""
            SELECT p.declaration_id,a.id,a.outcome,a.reasons,a.assessed_at FROM cdd_profiles p
             LEFT JOIN cdd_assessments a ON a.id=p.assessment_id WHERE p.user_id=?
            """,user);
        if(rows.isEmpty()) return new Status(null,null,"NOT_DECLARED",List.of(),null);
        var row=rows.getFirst();return new Status((UUID)row.get("declaration_id"),(UUID)row.get("id"),
            row.get("outcome")==null?"PENDING_IDENTITY":(String)row.get("outcome"),
            row.get("reasons")==null?List.of():List.of(((String)row.get("reasons")).split(",")),
            row.get("assessed_at")==null?null:((Timestamp)row.get("assessed_at")).toInstant());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void bind(long user,UUID attempt) {
        jdbc.update("INSERT INTO cdd_attempt_bindings(attempt_id,declaration_id) SELECT ?,declaration_id FROM cdd_profiles WHERE user_id=?",attempt,user);
    }
    /** 미신고 상태도 비교하되 거래 허용 근거로 쓰지 않는다. 신고 변경 후 옛 요청 키를 재사용할 수 없다. */
    public boolean boundToCurrent(long user,UUID attempt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS(SELECT 1 FROM cdd_profiles p WHERE p.user_id=? AND NOT EXISTS(
             SELECT 1 FROM cdd_attempt_bindings b WHERE b.attempt_id=? AND b.declaration_id=p.declaration_id))
            """,Boolean.class,user,attempt));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void assess(long user,UUID attempt,UUID event,String digest,JsonNode decision) {
        var rows=jdbc.queryForList("""
            SELECT d.* FROM cdd_profiles p JOIN cdd_declarations d ON d.id=p.declaration_id
             JOIN cdd_attempt_bindings b ON b.declaration_id=d.id AND b.attempt_id=? WHERE p.user_id=?
            """,attempt,user);
        if(rows.isEmpty() || crypto.keyId().equals("UNCONFIGURED")) return;
        var row=rows.getFirst();if(!crypto.keyId().equals(row.get("key_id"))) return;
        UUID declaration=(UUID)row.get("id");
        CustomerDeclaration declared;
        try {declared=json.readValue(crypto.decrypt(user,declaration,(byte[])row.get("ciphertext")),CustomerDeclaration.class);}
        catch(RuntimeException e) {throw new IllegalStateException("고객 신고 해독 실패");}
        var reasons=new LinkedHashSet<String>();var identity=verifiedIdentity(decision);
        if(identity==null) reasons.add("IDENTITY_EVIDENCE_INCOMPLETE");
        else if(!identity.equals(List.of(declared.fullName(),declared.dateOfBirth().toString(),declared.nationality()))) reasons.add("IDENTITY_MISMATCH");
        if(!amlClear(decision,identity)) reasons.add("AML_REVIEW_REQUIRED");
        if(declared.pep()!=CustomerDeclaration.Pep.NO) reasons.add("PEP_REVIEW_REQUIRED");
        if(!declared.beneficialOwnerSelf()) reasons.add("BENEFICIAL_OWNER_REVIEW_REQUIRED");
        // 초기 자동 처리 범위는 국내 거주 내국인 개인 투자다. 그 밖의 국적을 불법·고위험으로 단정하지 않는다.
        if(!declared.nationality().equals("KOR") || !declared.residenceCountry().equals("KOR")) reasons.add("COUNTRY_POLICY_REVIEW_REQUIRED");
        if(declared.occupation()==CustomerDeclaration.Occupation.OTHER || declared.purpose()!=CustomerDeclaration.Purpose.PERSONAL_INVESTMENT
                || declared.sourceOfFunds()==CustomerDeclaration.SourceOfFunds.OTHER) reasons.add("CUSTOMER_CONTEXT_REVIEW_REQUIRED");
        String identityMac=identity==null?null:crypto.fingerprint("identity",json.writeValueAsString(identity));
        if(reasons.isEmpty()) {
            // 서로 다른 고객의 행 잠금만으로는 동일 지문 경합을 막을 수 없다. 고유 키 삽입 결과로 순서를 확정한다.
            jdbc.update("INSERT INTO cdd_identity_claims(identity_mac,key_id,user_id) VALUES(?,?,?) ON CONFLICT DO NOTHING",identityMac,crypto.keyId(),user);
            long owner=jdbc.queryForObject("SELECT user_id FROM cdd_identity_claims WHERE identity_mac=? AND key_id=?",Long.class,identityMac,crypto.keyId());
            if(owner!=user) reasons.add("POSSIBLE_DUPLICATE_IDENTITY");
        }
        UUID assessment=UUID.randomUUID();String outcome=reasons.isEmpty()?"STANDARD":"REVIEW_REQUIRED";
        jdbc.update("""
            INSERT INTO cdd_assessments(id,user_id,declaration_id,attempt_id,event_id,status_digest,key_id,policy_version,outcome,reasons,identity_mac)
             VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """,assessment,user,declaration,attempt,event,digest,crypto.keyId(),POLICY,outcome,
            reasons.isEmpty()?"INITIAL_CHECKS_PASSED":String.join(",",reasons),identityMac);
        jdbc.update("UPDATE cdd_profiles SET assessment_id=? WHERE user_id=?",assessment,user);
        audit.record(user,"CDD_ASSESSED","cdd:"+assessment,"SUCCESS",outcome+":"+(reasons.isEmpty()?"INITIAL_CHECKS_PASSED":String.join(",",reasons)));
    }
    /** 신규 주문 예약과 같은 트랜잭션에서 호출한다. 옛 시도·옛 신고·옛 키·옛 판정의 평가는 효력이 없다. */
    public boolean permitted(long user) {
        if(crypto.keyId().equals("UNCONFIGURED")) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM cdd_profiles c JOIN cdd_assessments r ON r.id=c.assessment_id
             JOIN kyc_profiles k ON k.user_id=c.user_id JOIN cdd_declarations d ON d.id=c.declaration_id
             JOIN cdd_attempt_bindings b ON b.attempt_id=k.active_attempt AND b.declaration_id=c.declaration_id
             WHERE c.user_id=? AND r.user_id=c.user_id AND d.user_id=c.user_id AND r.declaration_id=c.declaration_id AND r.attempt_id=k.active_attempt
              AND r.status_digest=k.status_digest AND r.outcome='STANDARD' AND r.key_id=? AND d.key_id=? AND r.policy_version=?)
            """,Boolean.class,user,crypto.keyId(),crypto.keyId(),POLICY));
    }
    /** 신원 대조에 사용한 필드만 지문으로 만든다. 대사 실행 기록에 생일·이름의 공개 해시를 남기지 않는다. */
    public String evidenceDigest(JsonNode decision) {
        if(crypto.keyId().equals("UNCONFIGURED")) return "UNCONFIGURED";
        // 누락 노드는 유효 JSON 값이 아니다. 누락을 null로 명시하되 통과 판정은 별도 필수 필드 검사에서 거부한다.
        var evidence=json.createObjectNode();var ids=evidence.putArray("identity");
        for(var id:decision.path("id_verifications")) {
            var n=ids.addObject();for(String field:List.of("full_name","date_of_birth","nationality")) {if(id.has(field)) n.set(field,id.get(field));else n.putNull(field);}
        }
        // AML 전체 응답에는 불필요한 미디어·설명도 있을 수 있다. 판정에 쓰는 구조만 포함한다.
        var checks=evidence.putArray("aml");
        for(var check:decision.path("aml_screenings")) {
            var n=checks.addObject();
            for(String field:List.of("status","total_hits","entity_type","score")) {if(check.has(field)) n.set(field,check.get(field));else n.putNull(field);}
            // 검색 결과의 상세 개인정보와 문서 번호는 대조 지문으로 복사하지 않는다. 판정에 쓰는 비어 있음만 비교한다.
            n.put("hits_empty",check.path("hits").isArray() && check.path("hits").isEmpty());
            n.put("warnings_empty",check.path("warnings").isArray() && check.path("warnings").isEmpty());
            var screened=n.putObject("screened_data");var data=check.path("screened_data");
            for(String field:List.of("full_name","date_of_birth","nationality")) {if(data.has(field)) screened.set(field,data.get(field));else screened.putNull(field);}
        }
        // 보관된 이벤트와 현재 조회의 객체 필드 순서가 달라도 같은 근거여야 한다.
        // 기존 공급사 정규화를 재사용하고 공개 해시 결과도 지문 키로 다시 보호한다.
        return crypto.fingerprint("evidence",verifier.digest(evidence));
    }
    private List<String> verifiedIdentity(JsonNode decision) {
        List<String> identity=null;var ids=decision.path("id_verifications");if(!ids.isArray() || ids.isEmpty()) return null;
        try {for(var id:ids) {
            if(!id.path("status").asText().equals("Approved") || !id.path("full_name").isString() || !id.path("date_of_birth").isString() || !id.path("nationality").isString()) return null;
            var fields=List.of(CustomerDeclaration.name(id.path("full_name").asText()),LocalDate.parse(id.path("date_of_birth").asText()).toString(),CustomerDeclaration.country(id.path("nationality").asText()));
            if(identity!=null && !identity.equals(fields)) return null;identity=fields;
        }} catch(ResponseStatusException | java.time.format.DateTimeParseException e) {return null;}
        return identity;
    }
    private boolean amlClear(JsonNode decision,List<String> identity) {
        if(identity==null) return false;var checks=decision.path("aml_screenings");if(!checks.isArray() || checks.isEmpty()) return false;
        try {for(var check:checks) {
            if(!check.path("status").asText().equals("Approved") || !check.path("entity_type").asText().equals("person")
                    || !check.path("total_hits").isIntegralNumber() || !check.path("total_hits").bigIntegerValue().equals(java.math.BigInteger.ZERO)
                    || !check.path("score").isNumber() || check.path("score").decimalValue().signum()!=0
                    || !check.path("hits").isArray() || !check.path("hits").isEmpty() || !check.path("warnings").isArray() || !check.path("warnings").isEmpty()) return false;
            var data=check.path("screened_data");String nationality=data.path("nationality").asText();
            // 공식 AML 응답의 국적은 두 글자, 신분증 응답은 세 글자다. 명시적인 표준 코드 변환만 허용한다.
            if(!Arrays.asList(Locale.getISOCountries()).contains(nationality)) return false;
            String iso3=new Locale.Builder().setRegion(nationality).build().getISO3Country();
            if(!identity.equals(List.of(CustomerDeclaration.name(data.path("full_name").asText()),
                    LocalDate.parse(data.path("date_of_birth").asText()).toString(),iso3))) return false;
        }} catch(ResponseStatusException | java.time.format.DateTimeParseException e) {return false;}
        return true;
    }
    private void customer(long user,UUID session) {
        var owner=users.lockById(user).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        var token=sessions.findBySessionId(session).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if(!owner.isEnabled() || owner.getRole()!=UserRole.CUSTOMER || token.getUserId()!=user || token.getRevoked() || !token.getExpiresAt().isAfter(LocalDateTime.now()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    private ResponseStatusException conflict() {return new ResponseStatusException(HttpStatus.CONFLICT,"고객 신고 요청 키를 다시 확인하세요");}
}
