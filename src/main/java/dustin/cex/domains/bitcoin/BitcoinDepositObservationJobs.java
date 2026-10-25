package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import jakarta.annotation.PostConstruct;
import java.util.*;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;

/** 입금 원본의 거래를 영속 임대로 재확인한다. 노드 대기와 작업 행 잠금을 분리하며 늦은 실행의 갱신을 차단한다. */
@Service
@DependsOn("bitcoinSchemaInitializer")
public class BitcoinDepositObservationJobs {
    private record Lease(String txid,UUID id){}
    private final JdbcTemplate jdbc;
    private final BitcoinSettings settings;
    private final BitcoinObservationService observations;
    private final AuditService audit;
    private final BitcoinDepositQualificationService qualification;
    private final TransactionTemplate tx;
    public BitcoinDepositObservationJobs(JdbcTemplate jdbc,BitcoinSettings settings,BitcoinObservationService observations,
            AuditService audit,PlatformTransactionManager manager,BitcoinDepositQualificationService qualification){
        this.jdbc=jdbc;this.settings=settings;this.observations=observations;this.audit=audit;this.qualification=qualification;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @PostConstruct void seedKnownReceipts(){
        if(!settings.configured())return;
        // 재시작의 작업 근거는 불변 입금 원본이다. 기존 작업/금융 기록을 바꾸지 않고 빠진 작업만 재구성한다.
        tx.executeWithoutResult(t->jdbc.update("""
            INSERT INTO bitcoin_deposit_observation_jobs(network,wallet,txid)
             SELECT DISTINCT r.network,a.wallet,r.txid FROM bitcoin_deposit_receipts r JOIN bitcoin_deposit_addresses a ON a.id=r.address_id
              WHERE r.network=? AND a.wallet=? ON CONFLICT(network,wallet,txid) DO NOTHING
            """,settings.network.id,settings.wallet));
    }
    public boolean refreshDue(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("입금 재확인은 외부 트랜잭션 없이 실행하세요");
        Lease work=tx.execute(t->claim());if(work==null)return false;
        BitcoinObservationService.Recorded observed;
        try{
            observed=observations.refresh(work.txid());
            if(!observed.network().equals(settings.network.id)||!observed.txid().equals(work.txid()))throw new IllegalStateException("다른 거래의 관찰입니다");
        }catch(RuntimeException failure){
            // 오류 원문은 노드 응답/개인정보를 포함할 수 있다. 제한된 분류만 확정하고 다른 작업에 기회를 준다.
            String code=failure instanceof ResponseStatusException e?(e.getStatusCode().value()==409?"OBSERVATION_CONFLICT":"NODE_UNAVAILABLE"):"INTERNAL_FAILURE";
            finish(work,null,code);return true;
        }
        // 관찰은 이미 별도 TX에서 확정됐다. 여기서 장애가 나도 그 원본을 지우지 않고 임대 만료 뒤 다시 확인한다.
        finish(work,observed,"OBSERVED");return true;
    }
    private Lease claim(){
        var rows=jdbc.queryForList("""
            SELECT txid FROM bitcoin_deposit_observation_jobs WHERE network=? AND wallet=?
             AND next_attempt_at<=clock_timestamp() AND (lease_until IS NULL OR lease_until<=clock_timestamp())
             ORDER BY next_attempt_at,txid LIMIT 1 FOR UPDATE SKIP LOCKED
            """,settings.network.id,settings.wallet);
        if(rows.isEmpty())return null;
        String txid=(String)rows.getFirst().get("txid");UUID id=UUID.randomUUID();
        jdbc.update("""
            UPDATE bitcoin_deposit_observation_jobs SET lease_id=?,lease_until=clock_timestamp()+interval '2 minutes',last_attempt_at=clock_timestamp()
             WHERE network=? AND wallet=? AND txid=?
            """,id,settings.network.id,settings.wallet,txid);
        audit.record(null,"BTC_DEPOSIT_OBSERVATION_CLAIMED","bitcoin-observation-lease:"+id,"SUCCESS","TX:"+txid);
        return new Lease(txid,id);
    }
    private void finish(Lease work,BitcoinObservationService.Recorded observed,String code){
        tx.executeWithoutResult(t->{
            // 임대 행 잠금을 다시 얻은 뒤 소유권과 만료를 함께 검사한다. 이전 실행이 새 실행의 결과를 지우지 못한다.
            var owns=jdbc.queryForList("""
                SELECT txid FROM bitcoin_deposit_observation_jobs WHERE network=? AND wallet=? AND txid=?
                 AND lease_id=? AND lease_until>clock_timestamp() FOR UPDATE
                """,settings.network.id,settings.wallet,work.txid(),work.id());
            if(owns.isEmpty()){
                audit.record(null,"BTC_DEPOSIT_OBSERVATION_FINISHED","bitcoin-observation-lease:"+work.id(),"DENIED","LEASE_LOST");return;
            }
            if(observed!=null)qualification.evaluate(observed.id());
            jdbc.update("""
                UPDATE bitcoin_deposit_observation_jobs SET lease_id=NULL,lease_until=NULL,next_attempt_at=clock_timestamp()+interval '30 seconds',
                 latest_observation_id=COALESCE(?,latest_observation_id),last_outcome=?,failures=CASE WHEN ? THEN 0 ELSE failures+1 END
                 WHERE network=? AND wallet=? AND txid=?
                """,observed==null?null:observed.id(),code,observed!=null,settings.network.id,settings.wallet,work.txid());
            audit.record(null,"BTC_DEPOSIT_OBSERVATION_FINISHED","bitcoin-observation-lease:"+work.id(),observed==null?"DENIED":"SUCCESS",observed==null?code:"OBSERVATION:"+observed.id());
        });
    }
}
