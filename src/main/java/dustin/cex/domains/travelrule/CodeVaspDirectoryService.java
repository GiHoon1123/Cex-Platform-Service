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

/** 새 조회 시작부터 현재 위치를 전진시킨다. 새 실패/미완료를 과거 성공으로 숨기지 않으며 금융 승인 자체는 제공하지 않는다. */
@Service
@DependsOn("codeVaspSchemaInitializer")
public class CodeVaspDirectoryService {
    // 조회 결과는 금융 허가가 아니다. 외부 업무는 현재 자료와 별도의 고객·수취인·위험 통제를 함께 확인해야 한다.
    public record Report(long attemptId,String sourceId,String status,String failureCode){}
    public record Snapshot(String sourceId,long attemptId,long contentId,Instant startedAt,Instant recordedAt,CodeVaspDirectoryClient.Directory directory){}
    private record Start(long id,String source,Instant at){}
    private final CodeVaspSettings settings;
    private final CodeVaspDirectoryClient client;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public CodeVaspDirectoryService(CodeVaspSettings settings,CodeVaspDirectoryClient client,JdbcTemplate jdbc,ObjectMapper json,AuditService audit,PlatformTransactionManager manager){
        this.settings=settings;this.client=client;this.jdbc=jdbc;this.json=json;this.audit=audit;tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    /** 시작과 완료만 짧은 트랜잭션으로 저장한다. 공급사 응답을 기다리는 동안 DB 연결·행 잠금을 보유하지 않는다. */
    public Report refresh(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("사업자 목록 갱신은 DB 트랜잭션 밖에서 시작하세요");
        requireConfigured();String source=source();Start started=tx.execute(t->start(source));
        CodeVaspDirectoryClient.Directory directory=null;String payload=null,failure=null;
        try{
            directory=client.read();
            // 시각은 접속 시각을 증명하는 서명이 아니다. 잘못된 JVM 시각/반환값을 새 현재 원본으로 저장하지 않는다.
            if(directory==null||directory.readStartedAt()==null||directory.readFinishedAt()==null||directory.readFinishedAt().isBefore(directory.readStartedAt())
                ||directory.readStartedAt().isBefore(started.at().minusSeconds(10))||directory.readFinishedAt().isAfter(Instant.now().plusSeconds(10)))failure="INVALID_SOURCE";
            else{payload=json.writeValueAsString(directory.vasps());if(payload.getBytes(StandardCharsets.UTF_8).length>4194304)failure="INVALID_SOURCE";}
        }catch(ResponseStatusException unavailable){failure="PROVIDER_UNAVAILABLE";}
        catch(RuntimeException unexpected){failure="INTERNAL_FAILURE";}
        // 완료 저장/필수 감사의 실패를 공급사 실패로 다시 잡아 두 번째 결과를 만들지 않는다.
        var result=directory;String content=payload,code=failure;
        return tx.execute(t->finish(started,result,content,code));
    }
    private Start start(String source){
        // 동일 접속 주체의 시작을 직렬화한다. 2분 지난 미완료 조회는 새 시도로 대체하되 원본은 삭제하지 않는다.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"code-directory-v1:"+source);
        var head=jdbc.queryForList("""
            SELECT a.id FROM codevasp_directory_heads h JOIN codevasp_directory_attempts a ON a.id=h.attempt_id
             WHERE h.source_id=? AND a.started_at>clock_timestamp()-interval '2 minutes'
              AND NOT EXISTS(SELECT 1 FROM codevasp_directory_results r WHERE r.attempt_id=a.id)
            """,source);
        if(!head.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 진행 중인 사업자 목록 조회가 있습니다");
        jdbc.update("INSERT INTO codevasp_directory_sources(id,api_origin,entity_id,self_public_key) VALUES(?,?,?,?) ON CONFLICT(id) DO NOTHING",source,origin(),settings.entity,settings.publicKey);
        var descriptor=jdbc.queryForMap("SELECT api_origin,entity_id,self_public_key FROM codevasp_directory_sources WHERE id=?",source);
        if(!descriptor.get("api_origin").equals(origin())||!descriptor.get("entity_id").equals(settings.entity)||!descriptor.get("self_public_key").equals(settings.publicKey))throw unavailable();
        // 현재 위치를 완료 때 옮기면 새 실패를 과거 성공으로 숨기게 된다. 시작 원본과 현재 위치를 함께 확정한다.
        var attempt=jdbc.queryForMap("INSERT INTO codevasp_directory_attempts(source_id) VALUES(?) RETURNING id,started_at",source);long id=((Number)attempt.get("id")).longValue();
        boolean known=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM codevasp_directory_heads WHERE source_id=?)",Boolean.class,source));
        if(known)jdbc.update("UPDATE codevasp_directory_heads SET attempt_id=? WHERE source_id=?",id,source);
        else jdbc.update("INSERT INTO codevasp_directory_heads(source_id,attempt_id) VALUES(?,?)",source,id);
        audit.record(null,"CODE_DIRECTORY_READ_STARTED","codevasp-directory:"+source,"SUCCESS","ATTEMPT:"+id);
        return new Start(id,source,((Timestamp)attempt.get("started_at")).toInstant());
    }
    private Report finish(Start started,CodeVaspDirectoryClient.Directory directory,String payload,String failure){
        String status=failure==null?"SUCCESS":"FAILED";Long contentId=null;
        if(failure==null){
            // 조회 시각은 내용에서 제외해 같은 목록을 공유한다. 해시가 같아도 실제 문자열을 대조해 충돌을 신뢰하지 않는다.
            String hash=hash(payload);
            jdbc.update("INSERT INTO codevasp_directory_contents(source_id,payload_hash,payload) VALUES(?,?,?) ON CONFLICT(source_id,payload_hash) DO NOTHING",started.source(),hash,payload);
            var contents=jdbc.queryForList("SELECT id FROM codevasp_directory_contents WHERE source_id=? AND payload_hash=? AND payload=?",Long.class,started.source(),hash,payload);if(contents.size()!=1)throw unavailable();contentId=contents.getFirst();
        }
        // 늦게 끝난 옛 조회도 이력은 남기되 현재 위치는 되돌리지 않는다. 결과와 필수 감사는 같은 트랜잭션이다.
        jdbc.update("INSERT INTO codevasp_directory_results(attempt_id,source_id,status,failure_code,content_id,client_started_at,client_finished_at) VALUES(?,?,?,?,?,?,?)",
            started.id(),started.source(),status,failure,contentId,failure==null?Timestamp.from(directory.readStartedAt()):null,failure==null?Timestamp.from(directory.readFinishedAt()):null);
        audit.record(null,"CODE_DIRECTORY_READ_FINISHED","codevasp-directory:"+started.source(),failure==null?"SUCCESS":"DENIED","ATTEMPT:"+started.id()+":STATUS:"+status+":CODE:"+failure);
        return new Report(started.id(),started.source(),status,failure);
    }
    /** 호출자의 트랜잭션이 있으면 같은 TX에 참여한다. 자료의 현재 위치는 그 TX가 끝날 때까지 공유 잠금으로 고정한다. */
    public Snapshot current(){
        requireConfigured();String source=source();
        return tx.execute(t->{
            var heads=jdbc.queryForList("SELECT attempt_id FROM codevasp_directory_heads WHERE source_id=? FOR SHARE",Long.class,source);if(heads.size()!=1)throw unavailable();
            // 머리 행을 먼저 고정한 뒤 새 SQL 스냅샷에서 불변 원본을 읽는다. 큰 다중 조인의 잠금 재검사에 현재 위치를 맡기지 않는다.
            var rows=jdbc.queryForList("""
                SELECT a.id,a.started_at,r.status,r.recorded_at,r.client_started_at,r.client_finished_at,c.id content_id,c.payload_hash,c.payload,
                 a.started_at<=clock_timestamp() AND a.started_at>=clock_timestamp()-interval '5 minutes' AND r.recorded_at<=clock_timestamp() fresh
                 FROM codevasp_directory_attempts a LEFT JOIN codevasp_directory_results r ON r.attempt_id=a.id
                 LEFT JOIN codevasp_directory_contents c ON c.id=r.content_id AND c.source_id=a.source_id WHERE a.id=? AND a.source_id=?
                """,heads.getFirst(),source);
            if(rows.size()!=1)throw unavailable();var row=rows.getFirst();if(!"SUCCESS".equals(row.get("status"))||!Boolean.TRUE.equals(row.get("fresh")))throw unavailable();
            String payload=(String)row.get("payload");if(payload==null||!hash(payload).equals(row.get("payload_hash")))throw unavailable();
            try{
                var values=Arrays.asList(json.readValue(payload,CodeVaspDirectoryClient.Vasp[].class));
                var directory=new CodeVaspDirectoryClient.Directory(((Timestamp)row.get("client_started_at")).toInstant(),((Timestamp)row.get("client_finished_at")).toInstant(),values);
                return new Snapshot(source,((Number)row.get("id")).longValue(),((Number)row.get("content_id")).longValue(),((Timestamp)row.get("started_at")).toInstant(),((Timestamp)row.get("recorded_at")).toInstant(),directory);
            }catch(RuntimeException malformed){throw unavailable();}
        });
    }
    private void requireConfigured(){if(!settings.configured())throw unavailable();}
    private String origin(){return settings.api.resolve("/").toString();}
    // 내부 서명 모듈 주소는 운송 경로다. 자료 주체는 공급사 원점·등록 사업자·등록 공개키로 구분한다.
    private String source(){return hash(origin()+"\n"+settings.entity+"\n"+settings.publicKey);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException missing){throw new IllegalStateException("필수 자료 해시를 계산할 수 없습니다",missing);}}
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"현재 사업자 목록 근거를 확인할 수 없습니다");}
}
