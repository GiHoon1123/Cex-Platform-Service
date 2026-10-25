package dustin.cex.domains.travelrule;

import dustin.cex.domains.security.AuditService;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 검색이 끝난 보류 입금의 정보 교환을 연결한다. 작업 일정은 실행 근거/금융 승인이 아니며 원본은 별도 불변 표에 있다. */
@Service
@DependsOn("codeVaspSchemaInitializer")
public class CodeVaspTransferProcessing {
    public record Report(UUID searchId,UUID intentId,String status,String failureCode){}
    private record Claim(UUID search,UUID receipt,UUID lease){}
    private record Outcome(String status,String reason){}
    private final CodeVaspDirectoryService directory;
    private final CodeVaspTransferIntents intents;
    private final CodeVaspTransferExecution execution;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public CodeVaspTransferProcessing(CodeVaspDirectoryService directory,CodeVaspTransferIntents intents,CodeVaspTransferExecution execution,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){
        this.directory=directory;this.intents=intents;this.execution=execution;this.jdbc=jdbc;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Optional<Report> processNext(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("자동 정보 교환은 DB 트랜잭션 밖에서 시작하세요");
        String source=directory.configuredSourceId();Claim claim=tx.execute(t->{seed(source);return claim(source);});if(claim==null)return Optional.empty();
        UUID intent=null;Outcome outcome;
        try{
            intent=intents.reserve(claim.search()).id();var known=execution.current(intent);
            // 이미 확정한 수신/불명 원본은 읽어 일정만 회복한다. 같은 외부 PUT를 다시 하지 않는다.
            outcome=classify(Set.of("UNSTARTED","NOT_SENT").contains(known.status())?execution.execute(intent):known);
        }catch(ResponseStatusException held){outcome=recover(claim,intent,new Outcome("HELD",switch(held.getStatusCode().value()){case 503->"CURRENT_SOURCE_UNAVAILABLE";case 404->"SOURCE_MISSING";default->"CURRENT_CONDITIONS_NOT_MET";}));}
        catch(RuntimeException failed){outcome=recover(claim,intent,new Outcome("HELD","LOCAL_FAILURE"));}
        UUID reference=intent;Outcome finished=outcome;
        // 최종 저장/감사 실패는 위의 외부 실패 처리로 다시 잡지 않는다. 임대만 남으면 만료 후 원본 상태를 읽어 회복한다.
        return tx.execute(t->finish(claim,reference,finished));
    }
    private void seed(String source){
        var inserted=jdbc.queryForList("""
            INSERT INTO codevasp_transfer_jobs(search_id)
             SELECT s.id FROM codevasp_deposit_searches s JOIN bitcoin_pending_deposits p ON p.receipt_id=s.receipt_id
              JOIN codevasp_search_heads h ON h.search_id=s.id JOIN codevasp_search_results r ON r.attempt_id=h.attempt_id AND r.status='FOUND'
             WHERE s.source_id=? AND NOT EXISTS(SELECT 1 FROM codevasp_transfer_jobs j WHERE j.search_id=s.id)
             ORDER BY s.created_at,s.id LIMIT 1 ON CONFLICT(search_id) DO NOTHING RETURNING search_id
            """,UUID.class,source);
        if(!inserted.isEmpty())audit.record(null,"CODE_TRANSFER_JOB_CREATED","codevasp-search:"+inserted.getFirst(),"SUCCESS","ORIGIN_SEARCH_FOUND");
    }
    private Claim claim(String source){
        var due=jdbc.queryForList("""
            SELECT j.search_id,s.receipt_id FROM codevasp_transfer_jobs j JOIN codevasp_deposit_searches s ON s.id=j.search_id
             WHERE s.source_id=? AND j.state IN ('PENDING','RETRY','HELD') AND j.next_attempt_at<=clock_timestamp()
              AND(j.lease_until IS NULL OR j.lease_until<=clock_timestamp())
             ORDER BY j.next_attempt_at,j.search_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
            """,source);if(due.isEmpty())return null;var row=due.getFirst();UUID search=(UUID)row.get("search_id"),lease=UUID.randomUUID();
        jdbc.update("UPDATE codevasp_transfer_jobs SET lease_id=?,lease_until=clock_timestamp()+interval '2 minutes' WHERE search_id=?",lease,search);
        audit.record(null,"CODE_TRANSFER_JOB_CLAIMED","codevasp-search:"+search,"SUCCESS","LEASE:"+lease);
        return new Claim(search,(UUID)row.get("receipt_id"),lease);
    }
    private Outcome classify(CodeVaspTransferExecution.State state){return switch(state.status()){
        case "DATA_RECEIVED"->new Outcome("DONE","DATA_RECEIVED");
        case "NOT_SENT"->new Outcome("RETRY","NOT_SENT");
        case "RUNNING","OUTCOME_UNKNOWN","REMOTE_ERROR"->new Outcome("REVIEW_REQUIRED",state.status());
        default->new Outcome("HELD","CURRENT_CONDITIONS_NOT_MET");
    };}
    private Outcome recover(Claim claim,UUID intent,Outcome otherwise){
        if(intent!=null){var known=execution.current(intent);if(!Set.of("UNSTARTED","NOT_SENT").contains(known.status()))return classify(known);}
        // 접속 주체가 달랐던 과거 시도도 확인해 같은 입금의 불명을 우회하지 않는다.
        var prior=jdbc.queryForList("SELECT r.status FROM codevasp_transfer_attempts a LEFT JOIN codevasp_transfer_results r ON r.attempt_id=a.id WHERE a.receipt_id=? ORDER BY a.sequence DESC LIMIT 1",claim.receipt());
        if(!prior.isEmpty()&&!"NOT_SENT".equals(prior.getFirst().get("status")))return new Outcome("REVIEW_REQUIRED","PRIOR_TRANSFER");
        return otherwise;
    }
    private Optional<Report> finish(Claim claim,UUID intent,Outcome outcome){
        boolean terminal=Set.of("DONE","REVIEW_REQUIRED").contains(outcome.status());
        int changed=jdbc.update("""
            UPDATE codevasp_transfer_jobs SET intent_id=COALESCE(?,intent_id),state=?,last_reason=?,lease_id=NULL,lease_until=NULL,
             next_attempt_at=CASE WHEN ? THEN NULL ELSE clock_timestamp()+interval '2 minutes' END,updated_at=clock_timestamp()
             WHERE search_id=? AND lease_id=?
            """,intent,outcome.status(),outcome.reason(),terminal,claim.search(),claim.lease());
        // 임대가 바뀌었으면 늦은 완료로 새 작업 상태를 덮지 않는다. 실행 자체의 원본/결과는 이미 따로 보존됐다.
        if(changed==0)return Optional.empty();
        audit.record(null,"CODE_TRANSFER_JOB_FINISHED","codevasp-search:"+claim.search(),terminal&&outcome.status().equals("DONE")?"SUCCESS":"DENIED","STATUS:"+outcome.status()+":REASON:"+outcome.reason());
        return Optional.of(new Report(claim.search(),intent,outcome.status(),outcome.status().equals("DONE")?null:outcome.reason()));
    }
}
