package dustin.cex.domains.travelrule;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.AuditService;
import java.nio.charset.StandardCharsets;
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

/** 시작/현재 근거를 먼저 확정하고 외부 정보 교환 뒤 암호화 결과를 남긴다. 응답 수신은 고객/자금 승인과 구분한다. */
@Service
@DependsOn("codeVaspSchemaInitializer")
public class CodeVaspTransferExecution {
    public record State(UUID intentId,UUID attemptId,String status,String reason,Instant startedAt,Instant recordedAt){}
    private record Started(UUID id,UUID intent,long owner,CodeVaspTransferIntents.Basis basis){}
    private final CodeVaspTransferIntents intents;
    private final CodeVaspTransferDataClient client;
    private final CddCrypto crypto;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final CodeVaspTransferDetailAssessment assessments;
    public CodeVaspTransferExecution(CodeVaspTransferIntents intents,CodeVaspTransferDataClient client,CddCrypto crypto,JdbcTemplate jdbc,ObjectMapper json,AuditService audit,PlatformTransactionManager manager,CodeVaspTransferDetailAssessment assessments){
        this.intents=intents;this.client=client;this.crypto=crypto;this.jdbc=jdbc;this.json=json;this.audit=audit;this.assessments=assessments;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public State execute(UUID intentId){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("정보 교환은 DB 트랜잭션 밖에서 시작하세요");
        Objects.requireNonNull(intentId,"정보 제공 의도가 필요합니다");Started start=tx.execute(t->begin(intentId));
        String status,reason=null,payload=null;CodeVaspTransferDataClient.Data received=null;
        try{
            // 시작 TX는 이미 커밋됐다. 모듈·게이트웨이·복호화 대기에 DB 연결/고객 잠금을 들고 있지 않는다.
            var result=client.exchange(start.basis().prepared().request());
            if(result==null)throw new IllegalStateException();
            if("NORMAL".equals(result.status())){
                var data=result.data();var request=start.basis().prepared().request();
                if(data==null||!request.query().requestId().equals(data.transferId())||!request.peer().entityId().equals(data.originEntityId())
                    ||data.amount()==null||request.expectedAddressAmount().compareTo(data.amount())!=0||data.originator()==null||data.beneficiary()==null)throw new IllegalStateException();
                status="DATA_RECEIVED";received=data;
            }else if("ERROR".equals(result.status())&&result.data()==null&&Set.of("NOT_FOUND_TXID","LACK_OF_INFORMATION","UNKNOWN","UNKNOWN_TRANSFER_ID").contains(result.reason())){status="REMOTE_ERROR";reason=result.reason();}
            else throw new IllegalStateException();
            payload=json.writeValueAsString(result);if(payload.getBytes(StandardCharsets.UTF_8).length>65508)throw new IllegalStateException();
        }catch(CodeVaspSubmissionFailure failure){status=failure.admission()==CodeVaspSubmissionFailure.Admission.NOT_SENT?"NOT_SENT":"OUTCOME_UNKNOWN";reason="PROVIDER_UNAVAILABLE";payload=null;}
        catch(RuntimeException failure){status="OUTCOME_UNKNOWN";reason="INVALID_SOURCE";payload=null;}
        String outcome=status,code=reason,plain=payload;var details=received;
        // 완료 저장/필수 감사 실패를 외부 실패로 다시 처리하지 않는다. 시작만 남으면 재전송하지 않고 미해결로 보존한다.
        return tx.execute(t->{
            jdbc.update("INSERT INTO codevasp_transfer_results(attempt_id,status,reason,key_id,ciphertext,content_mac) VALUES(?,?,?,?,?,?)",start.id(),outcome,code,plain==null?null:crypto.keyId(),plain==null?null:crypto.encrypt(start.owner(),start.id(),plain.getBytes(StandardCharsets.UTF_8)),plain==null?null:crypto.fingerprint("codevasp-transfer-result-v1",plain));
            if("DATA_RECEIVED".equals(outcome))assessments.record(start.id(),start.basis().prepared(),details);
            audit.record(null,"CODE_TRANSFER_FINISHED","codevasp-transfer:"+intentId,plain==null?"DENIED":"SUCCESS","ATTEMPT:"+start.id()+":STATUS:"+outcome+":REASON:"+code);
            return state(intentId);
        });
    }
    private Started begin(UUID intentId){
        var sources=jdbc.queryForList("SELECT receipt_id FROM codevasp_transfer_intents WHERE id=?",UUID.class,intentId);
        if(sources.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"정보 제공 의도가 없습니다");
        UUID receiptId=sources.getFirst();
        // 접속 주체/검색 ID를 바꿔도 같은 입금의 미해결 전송을 우회하지 못하도록 입금 전체를 직렬화한다.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"code-private-transfer-receipt-v1:"+receiptId);
        var prior=jdbc.queryForList("SELECT a.id,r.status,r.recorded_at FROM codevasp_transfer_attempts a LEFT JOIN codevasp_transfer_results r ON r.attempt_id=a.id WHERE a.receipt_id=? ORDER BY a.sequence DESC LIMIT 1",receiptId);
        if(!prior.isEmpty()){
            var last=prior.getFirst();if(!"NOT_SENT".equals(last.get("status"))||last.get("recorded_at")==null||((Timestamp)last.get("recorded_at")).toInstant().isAfter(Instant.now().minusSeconds(30)))throw held();
        }
        // 같은 거래 의도에 결합된 현재 근거를 새로 포착한다. 과거 제공 의도를 현재 허가로 간주하지 않는다.
        var basis=intents.currentForExecution(intentId);var owner=jdbc.queryForMap("SELECT user_id,receipt_id FROM codevasp_transfer_intents WHERE id=?",intentId);long user=((Number)owner.get("user_id")).longValue();UUID id=UUID.randomUUID();
        String plain=json.writeValueAsString(basis);if(plain.getBytes(StandardCharsets.UTF_8).length>65508)throw held();
        jdbc.update("""
            INSERT INTO codevasp_transfer_attempts(id,intent_id,search_id,user_id,receipt_id,search_attempt_id,directory_attempt_id,decision_id,observation_id,
             declaration_id,assessment_id,policy_hash,peer_id,peer_public_key,key_id,ciphertext,content_mac)
             VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,intentId,basis.prepared().request().query().requestId(),user,owner.get("receipt_id"),basis.searchAttempt(),basis.directoryAttempt(),basis.decision(),basis.observation(),basis.declaration(),basis.assessment(),basis.policyHash(),basis.prepared().request().peer().entityId(),basis.prepared().request().key().publicKey(),crypto.keyId(),crypto.encrypt(user,id,plain.getBytes(StandardCharsets.UTF_8)),crypto.fingerprint("codevasp-transfer-attempt-v1",plain));
        jdbc.update("INSERT INTO codevasp_transfer_heads(intent_id,attempt_id) VALUES(?,?) ON CONFLICT(intent_id) DO UPDATE SET attempt_id=excluded.attempt_id",intentId,id);
        audit.record(null,"CODE_TRANSFER_STARTED","codevasp-transfer:"+intentId,"SUCCESS","ATTEMPT:"+id+":SEARCH_ATTEMPT:"+basis.searchAttempt());
        return new Started(id,intentId,user,basis);
    }
    /** 내부 상태 읽기는 개인정보/현재 승인 토큰을 반환하지 않는다. HTTP 노출 시에는 별도 현재 운영 인가가 필요하다. */
    public State current(UUID intentId){return tx.execute(t->state(intentId));}
    private State state(UUID intent){
        var rows=jdbc.queryForList("SELECT a.id,a.created_at,r.status,r.reason,r.recorded_at FROM codevasp_transfer_heads h JOIN codevasp_transfer_attempts a ON a.id=h.attempt_id LEFT JOIN codevasp_transfer_results r ON r.attempt_id=a.id WHERE h.intent_id=?",intent);
        if(rows.isEmpty())return new State(intent,null,"UNSTARTED",null,null,null);var row=rows.getFirst();
        return new State(intent,(UUID)row.get("id"),row.get("status")==null?"RUNNING":(String)row.get("status"),(String)row.get("reason"),((Timestamp)row.get("created_at")).toInstant(),row.get("recorded_at")==null?null:((Timestamp)row.get("recorded_at")).toInstant());
    }
    private static ResponseStatusException held(){return new ResponseStatusException(HttpStatus.CONFLICT,"정보 교환의 현재 결과와 재시도 조건을 확인하세요");}
}
