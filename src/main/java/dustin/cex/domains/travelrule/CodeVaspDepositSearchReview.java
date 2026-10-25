package dustin.cex.domains.travelrule;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 인가된 입금 심사 TX에서 기술 검색 근거만 투영한다. 네트워크 호출·예약·자금 승인 기능은 제공하지 않는다. */
@Service
public class CodeVaspDepositSearchReview {
    public record Search(UUID id,Long attemptId,String phase,String status,String failureCode,Instant createdAt,
        Instant startedAt,Instant recordedAt,Instant automaticUntil,boolean automaticWindowEnded,String originEntityId){}
    public record Review(boolean providerConfigured,String sourceRef,long historicalSearches,String reason,Instant readAt,Search search){}
    private final CodeVaspSettings settings;
    private final CodeVaspDirectoryService directory;
    private final JdbcTemplate jdbc;
    public CodeVaspDepositSearchReview(CodeVaspSettings settings,CodeVaspDirectoryService directory,JdbcTemplate jdbc){this.settings=settings;this.directory=directory;this.jdbc=jdbc;}

    @Transactional(propagation=Propagation.MANDATORY)
    public Review read(UUID receipt){
        // 호출자는 같은 TX에서 현재 운영 역할/MFA와 입금 소유자를 확인한다. 이 내부 메서드를 단독 HTTP로 노출하지 않는다.
        boolean configured=settings.configured();String source=configured?directory.configuredSourceId():null;
        var row=jdbc.queryForMap("""
            SELECT statement_timestamp() read_at,p.receipt_id pending_id,o.coinbase,
             s.id,s.created_at,h.attempt_id,a.phase,a.started_at,r.status,r.failure_code,r.recorded_at,
             CASE WHEN r.status='FOUND' THEN r.payload::jsonb->>'entityId' ELSE NULL END origin_entity_id,
             r.payload IS NULL OR r.payload_hash=encode(sha256(convert_to(r.payload,'UTF8')),'hex') payload_valid,
             (SELECT min(initial.started_at) FROM codevasp_search_attempts initial WHERE initial.search_id=s.id) first_started_at,
             (SELECT count(*) FROM codevasp_deposit_searches old WHERE old.receipt_id=input.receipt AND (?::varchar IS NULL OR old.source_id<>?)) historical_searches
             FROM (SELECT ?::uuid receipt) input
             LEFT JOIN bitcoin_pending_deposits p ON p.receipt_id=input.receipt
             LEFT JOIN bitcoin_deposit_chain_decisions d ON d.id=p.decision_id
             LEFT JOIN bitcoin_transaction_observations o ON o.id=d.observation_id
             LEFT JOIN codevasp_deposit_searches s ON s.receipt_id=input.receipt AND s.source_id=?
             LEFT JOIN codevasp_search_heads h ON h.search_id=s.id
             LEFT JOIN codevasp_search_attempts a ON a.id=h.attempt_id
             LEFT JOIN codevasp_search_results r ON r.attempt_id=a.id
            """,source,source,receipt,source);
        Instant readAt=at(row,"read_at");Search search=null;String reason;
        if(row.get("id")!=null){
            if(!Boolean.TRUE.equals(row.get("payload_valid")))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"검색 근거를 검증할 수 없습니다");
            String status=row.get("status")==null?(row.get("attempt_id")==null?"UNSTARTED":"RUNNING"):(String)row.get("status");
            Instant first=at(row,"first_started_at"),until=first==null?null:first.plusSeconds(900);boolean ended=until!=null&&!until.isAfter(readAt);
            reason=switch(status){
                // 출처 발견은 기술적 검색 결과다. 금액·제공 목적·상대 경로 판단을 완료했다고 표시하지 않는다.
                case "FOUND"->"ORIGIN_FOUND_REVIEW_REQUIRED";
                case "NOT_FOUND","UNKNOWN_REQUEST","AMBIGUOUS"->"MANUAL_REVIEW_REQUIRED";
                default->ended?"AUTO_WINDOW_EXPIRED":status.equals("UNSTARTED")?"AWAITING_SUBMISSION":status.equals("RUNNING")?"IN_PROGRESS":"AWAITING_RESULT";
            };
            search=new Search((UUID)row.get("id"),number(row,"attempt_id"),(String)row.get("phase"),status,(String)row.get("failure_code"),at(row,"created_at"),at(row,"started_at"),at(row,"recorded_at"),until,ended,(String)row.get("origin_entity_id"));
        }else if(!configured)reason="PROVIDER_NOT_CONFIGURED";
        else if(row.get("pending_id")==null)reason="PENDING_RECOGNITION_REQUIRED";
        else if(Boolean.TRUE.equals(row.get("coinbase")))reason="COINBASE_REVIEW_REQUIRED";
        else reason="AWAITING_RESERVATION";
        // 현재 접속 주체의 검색만 현재란에 둔다. 설정이 바뀌어도 예전 검색 수를 남겨 기록이 사라진 것으로 보이지 않게 한다.
        return new Review(configured,source,((Number)row.get("historical_searches")).longValue(),reason,readAt,search);
    }
    private static Instant at(Map<String,Object> row,String key){var value=(Timestamp)row.get(key);return value==null?null:value.toInstant();}
    private static Long number(Map<String,Object> row,String key){var value=(Number)row.get(key);return value==null?null:value.longValue();}
}
