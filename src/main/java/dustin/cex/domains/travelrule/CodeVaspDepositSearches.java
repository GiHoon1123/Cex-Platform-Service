package dustin.cex.domains.travelrule;

import dustin.cex.domains.security.AuditService;
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

/** 외부 검색 전에 입금 원본과 요청 ID를 확정한다. 이 서비스는 외부 통신·금융 승인·잔액 변경을 하지 않는다. */
@Service
@DependsOn("codeVaspSchemaInitializer")
public class CodeVaspDepositSearches {
    public record Search(UUID id,UUID receiptId,String sourceId,long directoryAttemptId,String network,Instant createdAt,CodeVaspTransactionClient.Request request){}
    private final CodeVaspDirectoryService directory;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public CodeVaspDepositSearches(CodeVaspDirectoryService directory,JdbcTemplate jdbc,AuditService audit,PlatformTransactionManager manager){
        this.directory=directory;this.jdbc=jdbc;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }

    public Search reserve(UUID receiptId){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 검색 예약은 독립 트랜잭션에서 실행하세요");
        Objects.requireNonNull(receiptId,"입금 식별자가 필요합니다");String source=directory.configuredSourceId();
        return tx.execute(t->{
            // 동일 입금·접속 주체의 동시 재시도도 단 하나의 요청 ID를 공유한다. 금융 코어와 무관한 짧은 플랫폼 DB 잠금이다.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"code-deposit-search-v1:"+receiptId+":"+source);
            var existing=jdbc.queryForList("SELECT id FROM codevasp_deposit_searches WHERE receipt_id=? AND source_id=?",UUID.class,receiptId,source);
            if(!existing.isEmpty())return load(existing.getFirst(),source);
            var snapshot=directory.current();
            var receipts=jdbc.queryForList("SELECT b.txid,d.address FROM bitcoin_deposit_receipts b JOIN bitcoin_deposit_addresses d ON d.id=b.address_id WHERE b.id=? AND b.asset='BTC'",receiptId);
            if(receipts.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"입금 원본을 찾을 수 없습니다");
            UUID id=UUID.randomUUID();
            // 실제 전송할 DTO도 지금 구성해 불량 해시/주소 원본을 전송 가능한 예약으로 남기지 않는다. 원문을 로그에 출력하지 않는다.
            new CodeVaspTransactionClient.Request(id,(String)receipts.getFirst().get("txid"),(String)receipts.getFirst().get("address"));
            jdbc.update("INSERT INTO codevasp_deposit_searches(id,receipt_id,source_id,directory_attempt_id) VALUES(?,?,?,?)",id,receiptId,source,snapshot.attemptId());
            audit.record(null,"CODE_DEPOSIT_SEARCH_RESERVED","codevasp-search:"+id,"SUCCESS","RECEIPT:"+receiptId+":DIRECTORY_ATTEMPT:"+snapshot.attemptId());
            return load(id,source);
        });
    }

    /** 내부 처리기가 원본을 다시 읽는 진입점이다. 고객/운영자 HTTP 인가를 대신하는 공개 조회 API가 아니다. */
    public Search get(UUID id){
        Objects.requireNonNull(id,"검색 식별자가 필요합니다");String source=directory.configuredSourceId();
        return tx.execute(t->load(id,source));
    }

    private Search load(UUID id,String source){
        var rows=jdbc.queryForList("""
            SELECT s.id,s.receipt_id,s.source_id,s.directory_attempt_id,s.created_at,b.network,b.txid,d.address
             FROM codevasp_deposit_searches s JOIN bitcoin_deposit_receipts b ON b.id=s.receipt_id
             JOIN bitcoin_deposit_addresses d ON d.id=b.address_id WHERE s.id=? AND s.source_id=?
            """,id,source);
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"검색 원본을 찾을 수 없습니다");
        var row=rows.getFirst();
        return new Search((UUID)row.get("id"),(UUID)row.get("receipt_id"),(String)row.get("source_id"),((Number)row.get("directory_attempt_id")).longValue(),(String)row.get("network"),((Timestamp)row.get("created_at")).toInstant(),new CodeVaspTransactionClient.Request((UUID)row.get("id"),(String)row.get("txid"),(String)row.get("address")));
    }
}
