package dustin.cex.domains.bitcoin;

import dustin.cex.domains.cdd.CddCrypto;
import dustin.cex.domains.security.AuditService;
import dustin.cex.domains.travelrule.CodeVaspDirectoryService;
import dustin.cex.shared.http.EngineDepositClient;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 준비된 입금의 대사/의도/전달을 연결한다. 일정·사전 필터가 금융 권한을 대신하지 않는다. */
@Service
@DependsOn("bitcoinCreditSchemaInitializer")
public class BitcoinCreditProcessing {
    public record Report(UUID receiptId,String state,String reason,EngineDepositClient.Outcome transportOutcome){}
    private record Claim(UUID receipt,UUID lease){}
    private record Known(boolean authorized,String state){}
    private record Result(String state,String reason,EngineDepositClient.Outcome transport){}
    private final JdbcTemplate jdbc;private final BitcoinSettings settings;private final CodeVaspDirectoryService directory;
    private final CddCrypto crypto;private final BitcoinDepositReconciliationService reconciliation;
    private final BitcoinCreditIntents intents;private final BitcoinCreditDispatches dispatch;private final AuditService audit;private final TransactionTemplate tx;
    private static final String FROM="""
         FROM bitcoin_pending_deposits p JOIN bitcoin_deposit_receipts r ON r.id=p.receipt_id
          JOIN bitcoin_deposit_addresses a ON a.id=r.address_id JOIN users u ON u.id=r.user_id
          LEFT JOIN bitcoin_credit_authorizations granted ON granted.receipt_id=r.id
        """;
    // 이 필터는 불필요한 선택/외부 읽기를 줄일 뿐이다. 실제 승인 시에는 현재 근거의 암호문/원장/대사를 다시 검증한다.
    // 이미 실행 권한이 있는 명령은 이후 고객 조건 변화와 분리해 같은 원본의 결과를 계속 확인한다.
    private static final String READY="""
         WHERE r.network=? AND a.wallet=? AND(granted.receipt_id IS NOT NULL OR(
          u.enabled AND u.role='CUSTOMER' AND NOT u.trading_restricted
          AND NOT EXISTS(SELECT 1 FROM relationship_active_exits e WHERE e.user_id=u.id)
          AND EXISTS(SELECT 1 FROM cdd_profiles cp JOIN cdd_assessments ca ON ca.id=cp.assessment_id
           JOIN kyc_profiles kp ON kp.user_id=cp.user_id JOIN kyc_attempts ka ON ka.id=kp.active_attempt
           WHERE cp.user_id=u.id AND ca.outcome='STANDARD' AND ca.key_id=? AND ca.policy_version='CDD_DOMESTIC_INDIVIDUAL_V1'
            AND ca.declaration_id=cp.declaration_id AND ca.attempt_id=kp.active_attempt AND ca.status_digest=kp.status_digest
            AND kp.identity_status='VERIFIED' AND kp.valid_until>clock_timestamp() AND ka.environment='live'
            AND EXISTS(SELECT 1 FROM kyc_reconciliations kr WHERE kr.attempt_id=kp.active_attempt AND kr.fresh_at BETWEEN clock_timestamp()-interval '15 minutes' AND clock_timestamp()))
          AND EXISTS(SELECT 1 FROM bitcoin_deposit_chain_heads ch JOIN bitcoin_deposit_chain_decisions d ON d.id=ch.decision_id
           JOIN bitcoin_observation_heads oh ON oh.observation_id=d.observation_id JOIN bitcoin_transaction_observations o ON o.id=oh.observation_id
           JOIN bitcoin_observation_wallet_debits wd ON wd.observation_id=o.id AND wd.wallet_debit=false
           WHERE ch.receipt_id=r.id AND d.reason='CHAIN_READY' AND d.policy_id='BTC_CHAIN_V1' AND o.coinbase=false
            AND o.status='CONFIRMED' AND o.confirmations>=6 AND o.read_started_at BETWEEN clock_timestamp()-interval '2 minutes' AND clock_timestamp())
          AND EXISTS(SELECT 1 FROM bitcoin_deposit_valuations v WHERE v.receipt_id=r.id AND v.status='VALUED' AND v.policy_id='BTC_PENDING_KRW_REFERENCE_V1')
          AND EXISTS(SELECT 1 FROM deposit_monitoring_heads mh JOIN deposit_monitoring_assessments ma ON ma.id=mh.assessment_id
           WHERE mh.user_id=u.id AND ma.policy_id='NATIVE_SMALL_REFERENCE_INFLOW_V1' AND ma.outcome IN ('NO_SIGNAL','REVIEW_REQUIRED') AND ma.unknown_transfers=0
            AND ma.input_cursor=(SELECT max(i.input_cursor) FROM deposit_monitoring_inputs i WHERE i.user_id=u.id))
          AND NOT EXISTS(SELECT 1 FROM deposit_monitoring_cases c WHERE c.user_id=u.id AND NOT monitoring_case_closed(c.id))
          AND EXISTS(SELECT 1 FROM codevasp_deposit_searches s JOIN codevasp_search_heads sh ON sh.search_id=s.id
           JOIN codevasp_search_results sr ON sr.attempt_id=sh.attempt_id AND sr.status='FOUND'
           JOIN codevasp_transfer_intents ti ON ti.search_id=s.id JOIN codevasp_transfer_heads th ON th.intent_id=ti.id
           JOIN codevasp_transfer_attempts ta ON ta.id=th.attempt_id JOIN codevasp_transfer_results tr ON tr.attempt_id=ta.id AND tr.status='DATA_RECEIVED'
           JOIN codevasp_transfer_detail_assessments td ON td.attempt_id=ta.id AND td.status='CONSISTENT'
           JOIN cdd_profiles cp ON cp.user_id=u.id AND cp.declaration_id=ta.declaration_id AND cp.assessment_id=ta.assessment_id
           WHERE s.receipt_id=r.id AND s.source_id=?)
         ))
        """;
    public BitcoinCreditProcessing(JdbcTemplate jdbc,BitcoinSettings settings,CodeVaspDirectoryService directory,CddCrypto crypto,BitcoinDepositReconciliationService reconciliation,BitcoinCreditIntents intents,BitcoinCreditDispatches dispatch,AuditService audit,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.settings=settings;this.directory=directory;this.crypto=crypto;this.reconciliation=reconciliation;this.intents=intents;this.dispatch=dispatch;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(10);
    }
    public Optional<Report> processNext(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 자동 처리는 독립 TX로 시작하세요");
        String source;try{source=directory.configuredSourceId();}catch(ResponseStatusException unconfigured){source=null;}
        Object[] scope={settings.network.id,settings.wallet,crypto.keyId(),source};
        Claim claim=tx.execute(t->{seed(scope);return claim(scope);});if(claim==null)return Optional.empty();
        Result result=perform(claim);
        // 결과/감사 저장 장애는 외부 실패로 다시 분류하지 않는다. 임대가 남으면 실제 금융 원본에서 복구한다.
        return tx.execute(t->finish(claim,result));
    }
    private void seed(Object[] scope){
        var inserted=jdbc.queryForList("INSERT INTO bitcoin_credit_jobs(receipt_id) SELECT r.id"+FROM+READY+"""
             AND NOT EXISTS(SELECT 1 FROM bitcoin_credited_deposits c WHERE c.receipt_id=r.id)
             AND NOT EXISTS(SELECT 1 FROM bitcoin_credit_jobs j WHERE j.receipt_id=r.id)
             ORDER BY p.recorded_at,r.id LIMIT 20 ON CONFLICT(receipt_id) DO NOTHING RETURNING receipt_id
            """,UUID.class,scope);
        for(UUID receipt:inserted)audit.record(null,"BTC_CREDIT_JOB_CREATED","bitcoin-deposit:"+receipt,"SUCCESS","ACTUAL_PENDING_SOURCE");
    }
    private Claim claim(Object[] scope){
        var rows=jdbc.queryForList("SELECT j.receipt_id"+FROM+" JOIN bitcoin_credit_jobs j ON j.receipt_id=r.id "+READY+"""
             AND j.state IN ('PENDING','RETRY','HELD','WAITING_EVENT') AND j.next_attempt_at<=clock_timestamp()
             AND(j.lease_until IS NULL OR j.lease_until<=clock_timestamp())
             ORDER BY j.next_attempt_at,j.receipt_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
            """,scope);if(rows.isEmpty())return null;
        UUID receipt=(UUID)rows.getFirst().get("receipt_id"),lease=UUID.randomUUID();
        jdbc.update("UPDATE bitcoin_credit_jobs SET lease_id=?,lease_until=clock_timestamp()+interval '2 minutes',updated_at=clock_timestamp() WHERE receipt_id=?",lease,receipt);
        audit.record(null,"BTC_CREDIT_JOB_CLAIMED","bitcoin-deposit:"+receipt,"SUCCESS","LEASE:"+lease);
        return new Claim(receipt,lease);
    }
    private Result perform(Claim claim){
        try{
            var known=known(claim.receipt());if(known.state()!=null)return new Result(known.state(),"RECOVERED_FINANCIAL_FACT",null);
            if(!known.authorized()){
                // 노드 읽기는 임대/고객 잠금과 DB TX 밖이다. 최신 실제 대사 뒤에도 의도/전송은 각자 현재 조건을 재검증한다.
                var proof=reconciliation.reconcile(claim.receipt());
                if(proof.status()!=BitcoinDepositReconciliationService.Status.MATCHED)return new Result("HELD","RECON_"+proof.status(),null);
                intents.reserve(claim.receipt());
            }
            var execution=dispatch.execute(claim.receipt());var after=known(claim.receipt());
            if(after.state()!=null)return new Result(after.state(),"FINANCIAL_FACT_RECORDED",execution.map(BitcoinCreditDispatches.Execution::outcome).orElse(null));
            return new Result("RETRY",execution.isEmpty()?"DISPATCH_BUSY":"ENGINE_RECONFIRM_REQUIRED",execution.map(BitcoinCreditDispatches.Execution::outcome).orElse(null));
        }catch(BitcoinCreditBasis.Held held){return recovered(claim,"HELD_"+held.reason());}
        catch(ResponseStatusException held){return recovered(claim,"CURRENT_SOURCE_UNAVAILABLE");}
        catch(RuntimeException failed){return recovered(claim,"LOCAL_FAILURE");}
    }
    private Result recovered(Claim claim,String reason){var known=known(claim.receipt());return new Result(known.state()==null?"HELD":known.state(),reason,null);}
    private Known known(UUID receipt){
        var row=jdbc.queryForMap("""
            SELECT EXISTS(SELECT 1 FROM bitcoin_credit_authorizations WHERE receipt_id=?) authorized,
             EXISTS(SELECT 1 FROM bitcoin_credited_deposits WHERE receipt_id=?) credited,
             (SELECT r.outcome FROM bitcoin_credit_dispatch_attempts a JOIN bitcoin_credit_dispatch_results r ON r.attempt_id=a.id
               WHERE a.receipt_id=? AND r.outcome IN ('DURABLY_CREDITED','DURABLY_REJECTED') ORDER BY a.position DESC LIMIT 1) confirmed
            """,receipt,receipt,receipt);
        String state=Boolean.TRUE.equals(row.get("credited"))?"SETTLED":"DURABLY_REJECTED".equals(row.get("confirmed"))?"ENGINE_REJECTED":"DURABLY_CREDITED".equals(row.get("confirmed"))?"WAITING_EVENT":null;
        return new Known(Boolean.TRUE.equals(row.get("authorized")),state);
    }
    private Optional<Report> finish(Claim claim,Result result){
        boolean terminal=Set.of("SETTLED","ENGINE_REJECTED").contains(result.state());int base=result.state().equals("HELD")?30:5;
        int updated=jdbc.update("""
            UPDATE bitcoin_credit_jobs SET state=?,last_reason=?,lease_id=NULL,lease_until=NULL,updated_at=clock_timestamp(),
             next_attempt_at=CASE WHEN ? THEN NULL WHEN ?='WAITING_EVENT' THEN clock_timestamp()+interval '30 seconds'
              ELSE clock_timestamp()+make_interval(secs=>least(120,?*power(2,least(failures,5))::integer)) END,
             failures=CASE WHEN ? IN ('HELD','RETRY') THEN failures+1 ELSE 0 END
             WHERE receipt_id=? AND lease_id=? AND lease_until>clock_timestamp()
            """,result.state(),result.reason(),terminal,result.state(),base,result.state(),claim.receipt(),claim.lease());
        if(updated==0)return Optional.empty();
        audit.record(null,"BTC_CREDIT_JOB_FINISHED","bitcoin-deposit:"+claim.receipt(),Set.of("HELD","RETRY","ENGINE_REJECTED").contains(result.state())?"DENIED":"SUCCESS","STATE:"+result.state()+":REASON:"+result.reason());
        return Optional.of(new Report(claim.receipt(),result.state(),result.reason(),result.transport()));
    }
}
