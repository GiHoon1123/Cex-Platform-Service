package dustin.cex.domains.travelrule;

import dustin.cex.domains.security.AuditService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 영속 검색 원본을 접수하고 결과를 보관한다. 미전송을 증명한 경우만 재접수하고 불명·중단은 같은 ID로 조회한다. */
@Service
@DependsOn("codeVaspSchemaInitializer")
public class CodeVaspDepositSearchExecution {
    public record State(UUID searchId,Long attemptId,String phase,String status,String failureCode,Instant startedAt,Instant recordedAt,CodeVaspTransactionClient.Result result){}
    private record Start(long id,UUID search,String phase){}
    private final CodeVaspDepositSearches searches;
    private final CodeVaspTransactionClient client;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public CodeVaspDepositSearchExecution(CodeVaspDepositSearches searches,CodeVaspTransactionClient client,JdbcTemplate jdbc,AuditService audit,ObjectMapper json,PlatformTransactionManager manager){
        this.searches=searches;this.client=client;this.jdbc=jdbc;this.audit=audit;this.json=json;
        tx=new TransactionTemplate(manager);tx.setTimeout(10);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public State refresh(UUID id){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 검색 실행은 DB 트랜잭션 밖에서 시작하세요");
        var search=searches.get(id);Start started=tx.execute(t->begin(search.id()));
        CodeVaspTransactionClient.Result result=null;String status,failure=null,payload=null;
        try{
            // 외부 접수/조회 동안 시작 TX와 그 잠금은 이미 끝났다. 서명 키·주소를 오류 기록에 넣지 않는다.
            if(started.phase().equals("SUBMIT")){client.submit(search.request());status="SUBMIT_ACKNOWLEDGED";}
            else{
                result=client.result(search.id());
                if(result==null||!search.id().equals(result.requestId())||result.status()==null){status="POLL_FAILED";failure="INVALID_SOURCE";result=null;}
                else{status=result.status().name();payload=json.writeValueAsString(result);if(payload.getBytes(StandardCharsets.UTF_8).length>4194304){status="POLL_FAILED";failure="INVALID_SOURCE";payload=null;result=null;}}
            }
        }catch(CodeVaspSubmissionFailure admission){
            status=started.phase().equals("SUBMIT")?(admission.admission()==CodeVaspSubmissionFailure.Admission.NOT_SENT?"SUBMIT_NOT_SENT":"SUBMIT_UNKNOWN"):"POLL_FAILED";
            failure="PROVIDER_UNAVAILABLE";payload=null;result=null;
        }catch(ResponseStatusException unavailable){status=started.phase().equals("SUBMIT")?"SUBMIT_UNKNOWN":"POLL_FAILED";failure="PROVIDER_UNAVAILABLE";payload=null;result=null;}
        catch(RuntimeException unexpected){status=started.phase().equals("SUBMIT")?"SUBMIT_UNKNOWN":"POLL_FAILED";failure="INTERNAL_FAILURE";payload=null;result=null;}
        String outcome=status,code=failure,body=payload;var value=result;
        // 완료 DB/감사 실패는 공급사 오류로 다시 저장하지 않는다. 시작만 남으면 임대 후 결과 조회로 회복한다.
        return tx.execute(t->{
            jdbc.update("INSERT INTO codevasp_search_results(attempt_id,status,failure_code,payload,payload_hash) VALUES(?,?,?,?,?)",started.id(),outcome,code,body,body==null?null:hash(body));
            audit.record(null,"CODE_DEPOSIT_SEARCH_FINISHED","codevasp-search:"+id,code==null?"SUCCESS":"DENIED","ATTEMPT:"+started.id()+":PHASE:"+started.phase()+":STATUS:"+outcome+":CODE:"+code);
            return state(started,value);
        });
    }

    private Start begin(UUID id){
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"code-search-execution-v1:"+id);
        var blocked=jdbc.queryForList("""
            SELECT a.id FROM codevasp_search_heads h JOIN codevasp_search_attempts a ON a.id=h.attempt_id
             LEFT JOIN codevasp_search_results r ON r.attempt_id=a.id WHERE h.search_id=?
              AND ((r.attempt_id IS NULL AND a.started_at>clock_timestamp()-interval '2 minutes')
               OR(r.attempt_id IS NOT NULL AND r.recorded_at>clock_timestamp()-interval '30 seconds'))
            """,id);
        if(!blocked.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"진행 중이거나 재조회 간격이 지나지 않은 검색입니다");
        boolean prior=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM codevasp_search_attempts WHERE search_id=?)",Boolean.class,id));
        boolean notSent=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM codevasp_search_heads h JOIN codevasp_search_results r ON r.attempt_id=h.attempt_id WHERE h.search_id=? AND r.status='SUBMIT_NOT_SENT')",Boolean.class,id));
        String phase=!prior||notSent?"SUBMIT":"POLL";
        long attempt=jdbc.queryForObject("INSERT INTO codevasp_search_attempts(search_id,phase) VALUES(?,?) RETURNING id",Long.class,id,phase);
        jdbc.update("INSERT INTO codevasp_search_heads(search_id,attempt_id) VALUES(?,?) ON CONFLICT(search_id) DO UPDATE SET attempt_id=excluded.attempt_id",id,attempt);
        audit.record(null,"CODE_DEPOSIT_SEARCH_STARTED","codevasp-search:"+id,"SUCCESS","ATTEMPT:"+attempt+":PHASE:"+phase);
        return new Start(attempt,id,phase);
    }

    /** 같은 TX에서 최신 위치를 고정해 읽는다. 상태는 기술 검색 결과이며 금융 승인 토큰이 아니다. */
    public State current(UUID id){
        searches.get(id);
        return tx.execute(t->{
            var heads=jdbc.queryForList("SELECT attempt_id FROM codevasp_search_heads WHERE search_id=? FOR SHARE",Long.class,id);
            if(heads.isEmpty())return new State(id,null,null,"UNSTARTED",null,null,null,null);
            var row=jdbc.queryForMap("SELECT phase FROM codevasp_search_attempts WHERE id=?",heads.getFirst());
            return state(new Start(heads.getFirst(),id,(String)row.get("phase")),null);
        });
    }
    private State state(Start start,CodeVaspTransactionClient.Result known){
        var row=jdbc.queryForMap("SELECT a.started_at,r.status,r.failure_code,r.recorded_at,r.payload,r.payload_hash FROM codevasp_search_attempts a LEFT JOIN codevasp_search_results r ON r.attempt_id=a.id WHERE a.id=? AND a.search_id=?",start.id(),start.search());
        CodeVaspTransactionClient.Result result=known;String payload=(String)row.get("payload");
        if(payload!=null){
            if(!hash(payload).equals(row.get("payload_hash")))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"저장된 검색 결과를 검증할 수 없습니다");
            if(result==null)result=json.readValue(payload,CodeVaspTransactionClient.Result.class);
        }
        return new State(start.search(),start.id(),start.phase(),row.get("status")==null?"RUNNING":(String)row.get("status"),(String)row.get("failure_code"),((Timestamp)row.get("started_at")).toInstant(),row.get("recorded_at")==null?null:((Timestamp)row.get("recorded_at")).toInstant(),result);
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException failure){throw new IllegalStateException("검색 결과 해시를 계산할 수 없습니다",failure);}}
}
