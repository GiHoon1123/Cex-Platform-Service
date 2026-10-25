package dustin.cex.domains.bitcoin;

import dustin.cex.domains.security.AuditService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 노드의 검증된 관찰을 이력으로 확정한다. 고객 권한/적립은 이 사실과 별도로 판단한다. */
@Service
public class BitcoinObservationService {
    public record Recorded(long id,String network,String txid,BitcoinDepositReader.Status status,long confirmations,String tipHash,Instant recordedAt){}
    private record Started(Long expected,Instant at){}
    private final BitcoinDepositReader reader;
    private final BitcoinSettings settings;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;
    private final TransactionTemplate tx;
    public BitcoinObservationService(BitcoinDepositReader reader,BitcoinSettings settings,JdbcTemplate jdbc,ObjectMapper json,
            AuditService audit,PlatformTransactionManager manager){
        this.reader=reader;this.settings=settings;this.jdbc=jdbc;this.json=json;this.audit=audit;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Recorded refresh(String txid){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("체인 관찰은 외부 트랜잭션 없이 실행하세요");
        if(txid==null||!txid.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("정확한 거래 식별자가 필요합니다");
        String network=settings.network.id;
        try{
            // 노드를 기다릴 때 잠금/DB 연결을 유지하지 않는다. 시작 버전과 시각은 같은 DB에서 얻는다.
            var started=tx.execute(t->new Started(head(network,txid),jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant()));
            var observation=reader.inspect(txid);
            if(!observation.network().equals(network)||!observation.txid().equals(txid))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"관찰의 거래 원본이 다릅니다");
            String outputs=json.writeValueAsString(observation.outputs());
            return tx.execute(t->{
                // 같은 거래의 확정 구간만 직렬화한다. 해시 충돌은 불필요한 직렬화 비용만 만들고 소유를 섞지 않는다.
                jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",row->{},"btc-observation-v1:"+network+":"+txid);
                if(!Objects.equals(head(network,txid),started.expected()))throw new ResponseStatusException(HttpStatus.CONFLICT,"더 최근 관찰이 확정되어 다시 조회해야 합니다");
                var saved=jdbc.queryForMap("""
                    INSERT INTO bitcoin_transaction_observations(network,wallet,txid,status,confirmations,coinbase,
                      block_hash,block_height,tip_hash,tip_height,outputs,read_started_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?) RETURNING id,recorded_at
                    """,network,settings.wallet,txid,observation.status().name(),observation.confirmations(),observation.coinbase(),
                    observation.blockHash(),observation.blockHeight(),observation.tipHash(),observation.tipHeight(),outputs,Timestamp.from(started.at()));
                long id=((Number)saved.get("id")).longValue();
                // 지갑 사용 근거도 같은 새 관찰 TX에 확정한다. 과거 원본에 false를 추측해 채우지 않는다.
                jdbc.update("INSERT INTO bitcoin_observation_wallet_debits(observation_id,wallet_debit) VALUES(?,?)",id,observation.walletDebit());
                jdbc.update("""
                    INSERT INTO bitcoin_observation_heads(network,txid,observation_id) VALUES(?,?,?)
                    ON CONFLICT(network,txid) DO UPDATE SET observation_id=excluded.observation_id
                    """,network,txid,id);
                audit.record(null,"BTC_TRANSACTION_OBSERVED","bitcoin:"+network+":"+txid,"SUCCESS","OBSERVATION:"+id);
                return new Recorded(id,network,txid,observation.status(),observation.confirmations(),observation.tipHash(),((Timestamp)saved.get("recorded_at")).toInstant());
            });
        }catch(ResponseStatusException e){
            audit.denied(null,"BTC_TRANSACTION_OBSERVATION","bitcoin:"+network+":"+txid,"FAILED_"+e.getStatusCode().value());throw e;
        }
    }
    private Long head(String network,String txid){
        var rows=jdbc.queryForList("SELECT observation_id FROM bitcoin_observation_heads WHERE network=? AND txid=?",network,txid);
        return rows.isEmpty()?null:((Number)rows.getFirst().get("observation_id")).longValue();
    }
}
