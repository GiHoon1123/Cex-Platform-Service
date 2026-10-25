package dustin.cex.domains.travelrule;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** 예약한 검색의 다음 작업 하나만 고른다. 실행권·단계·완료는 불변 실행 이력이 결정하며 별도 메모리 큐로 복제하지 않는다. */
@Service
public class CodeVaspDepositSearchProcessing {
    private final CodeVaspDirectoryService directory;
    private final CodeVaspDepositSearchExecution execution;
    private final JdbcTemplate jdbc;
    private final CodeVaspDepositSearches searches;
    public CodeVaspDepositSearchProcessing(CodeVaspDirectoryService directory,CodeVaspDepositSearchExecution execution,JdbcTemplate jdbc,CodeVaspDepositSearches searches){
        this.directory=directory;this.execution=execution;this.jdbc=jdbc;this.searches=searches;
    }
    public Optional<CodeVaspDepositSearchExecution.State> processNext(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("자동 검색은 DB 트랜잭션 밖에서 실행하세요");
        String source=directory.configuredSourceId();
        ResponseStatusException reservationHeld=null;
        try{searches.reserveNextPending();}
        catch(ResponseStatusException unavailable){
            // 새 예약의 현재 목록 전제가 실패해도 이미 접수됐을 수 있는 검색의 조회는 계속한다.
            if(unavailable.getStatusCode().value()!=503)throw unavailable;
            reservationHeld=unavailable;
        }
        var candidates=candidates(source,false);
        if(candidates.isEmpty()){
            if(reservationHeld!=null)throw reservationHeld;
            return Optional.empty();
        }
        var candidate=candidates.getFirst();
        // 새 접수는 현재 기술 목록을 확인한다. 이미 접수됐을 수 있는 ID의 조회까지 목록 장애로 막지는 않는다.
        if(Boolean.TRUE.equals(candidate.get("requires_submit"))){
            try{directory.current();}
            catch(ResponseStatusException unavailable){
                if(unavailable.getStatusCode().value()!=503)throw unavailable;
                // 새 접수가 목록 장애로 보류되어도 이미 접수됐을 수 있는 ID의 독립 조회를 앞에서 막지 않는다.
                var polls=candidates(source,true);
                if(polls.isEmpty())throw unavailable;
                candidate=polls.getFirst();
            }
        }
        try{
            // 후보 선택 후 다른 프로세스가 먼저 시작해도 실행 서비스의 DB 임대·간격 검사로 중복 호출을 막는다.
            return Optional.of(execution.refresh((UUID)candidate.get("id")));
        }catch(ResponseStatusException conflict){
            if(conflict.getStatusCode().value()==409)return Optional.empty();
            throw conflict;
        }
    }
    private List<Map<String,Object>> candidates(String source,boolean pollOnly){
        return jdbc.queryForList("""
            SELECT s.id,(h.attempt_id IS NULL OR r.status='SUBMIT_NOT_SENT') requires_submit
             FROM codevasp_deposit_searches s LEFT JOIN codevasp_search_heads h ON h.search_id=s.id
             LEFT JOIN codevasp_search_attempts a ON a.id=h.attempt_id
             LEFT JOIN codevasp_search_results r ON r.attempt_id=a.id
             WHERE s.source_id=?
              AND (NOT ? OR (h.attempt_id IS NOT NULL AND COALESCE(r.status,'')<>'SUBMIT_NOT_SENT'))
              AND (h.attempt_id IS NULL OR (SELECT min(initial.started_at) FROM codevasp_search_attempts initial WHERE initial.search_id=s.id)>clock_timestamp()-interval '15 minutes')
              AND (h.attempt_id IS NULL OR
                (r.attempt_id IS NULL AND a.started_at<=clock_timestamp()-interval '2 minutes') OR
                (r.status IN ('SUBMIT_NOT_SENT','SUBMIT_ACKNOWLEDGED','SUBMIT_UNKNOWN','PROCESSING','POLL_FAILED') AND r.recorded_at<=clock_timestamp()-interval '30 seconds'))
             ORDER BY COALESCE(r.recorded_at,a.started_at,s.created_at),s.id LIMIT 1
            """,source,pollOnly);
    }
}
